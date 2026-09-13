package io.github.sensorlaunchguard.xposed

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.sensorlaunchguard.data.RuleStore
import java.util.concurrent.ConcurrentHashMap

/**
 * 跳转询问：被拦截的启动不再静默丢弃，而是弹一条带「禁止 / 允许（本次）」的通知。
 *
 * 为什么用通知而不是应用内对话框：
 * - 钩子同步运行在调用 `startActivity` 的线程上（通常是宿主主线程），无法阻塞等待用户点选，
 *   否则宿主直接 ANR；
 * - 弹通知是非阻塞的，钩子可以立刻返回「什么都没发生」，宿主不崩、不卡。
 *
 * 为什么「允许（本次）」不用来源进程重发：
 * - 通知里的 [PendingIntent] 由 system_server 代发，属通知操作特权，可在后台拉起 Activity；
 * - 通知自带全部信息，**来源进程即使被杀也照样有效**，无需动态注册广播接收器；
 * - 代价：`Intent` 内自定义 `Parcelable` extra 在原进程已死时可能无法反序列化，
 *   但显式 component / data / 系统 extra 都会保留（支付类深链通常是这种）。
 *
 * 「本次」语义天然一次性：不留任何持久豁免状态，点完即止。
 *
 * 注意：**不能使用 `R.string` / `R.drawable`**。本类运行在宿主应用进程里，
 * 那些资源 ID 属于我们的模块 APK，用宿主的 `Context` 去解析会取到错误资源甚至抛异常。
 * 因此文案为内置常量，图标取模块自身的 launcher 图标。
 */
class LaunchPrompt(
    private val module: XposedModule,
    private val enabled: () -> Boolean,
) {
    private val notified = ConcurrentHashMap<String, Long>()

    @Volatile
    private var moduleLabel: String? = null

    @Volatile
    private var moduleIcon: Int = 0

    /** 返回 true 表示「已弹出询问（启动被暂缓）」；false 表示调用方应继续静默拦截。 */
    fun request(source: String, target: String, intent: Intent?, rules: RulesHolder): Boolean {
        if (!enabled()) return false
        if (intent == null) return false
        if (intent.component == null && intent.`package`.isNullOrEmpty()) {
            // 目标不明确（隐式 Intent / 选择器）时无法让用户判断该允许什么，维持静默拦截。
            return false
        }

        val context = PackageResolver.currentContext() ?: return false
        if (notificationsBlocked(context)) return false

        val key = "$source|$target"
        if (!shouldNotify(key)) return false

        return try {
            // 一次性豁免：点「允许（本次）」后既直接拉起目标，也留一个很短的窗口，
            // 让支付 SDK 自己重试同一个 Intent 时同样放行。窗口结束自动失效，不留持久状态。
            grantOneShot(rules, source, target)

            val sourceLabel = appLabel(context, source)
            val targetLabel = appLabel(context, target)

            val allow = PendingIntent.getActivity(
                context,
                requestCode(target),
                Intent(intent).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            // 「禁止」= 打开模块主界面并立即退出（只用于取消通知，不执行任何拉起）。
            val deny = PendingIntent.getActivity(
                context,
                requestCode(target) + 1,
                Intent(ACTION_DENY)
                    .setPackage(modulePackage(context))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val text = "$sourceLabel $TEXT_MIDDLE $targetLabel$TEXT_QUESTION"
            val builder = baseBuilder(context)
                .setContentTitle(moduleLabel(context))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText("$text\n\n$source\n→ $target"))
                .setSmallIcon(moduleIcon(context))
                .setAutoCancel(true)

            actions(TEXT_DENY, deny, TEXT_ALLOW_ONCE, allow).forEach(builder::addAction)

            notificationManager(context)?.notify(notificationId(target), builder.build())
            log("prompted: source=$source target=$target")
            true
        } catch (t: Throwable) {
            // 任何失败都退回静默拦截，绝不因为弹通知失败而放行或让宿主崩溃。
            Log.w(TAG, "request prompt failed", t)
            false
        }
    }

    /** 同一 来源+目标 在短时间内只询问一次，避免连环弹通知。 */
    private fun shouldNotify(key: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        notified.entries.removeAll { now - it.value > NOTIFY_INTERVAL_MS }
        val previous = notified.put(key, now)
        return previous == null
    }

    /**
     * 写入一次性豁免。
     *
     * 这只是让「用户已经点过允许」这件事对**同一条 来源→目标 关系**短暂成立，
     * 不是全局放行；写入失败也不影响通知里的直接拉起（那条路径不经过钩子）。
     */
    private fun grantOneShot(rules: RulesHolder, source: String, target: String) {
        if (source.isEmpty()) return
        val preferences = rules.preferences ?: return
        try {
            RuleStore.setExemptUntil(
                preferences,
                source,
                target,
                System.currentTimeMillis() + ONE_SHOT_WINDOW_MS,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "grant one-shot exemption failed", t)
        }
    }

    private fun baseBuilder(context: Context): Notification.Builder {
        val manager = notificationManager(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply { setShowBadge(false) },
                )
            }
            return Notification.Builder(context, CHANNEL_ID)
        }
        @Suppress("DEPRECATION")
        return Notification.Builder(context)
    }

    private fun notificationManager(context: Context): NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    /** 通知按钮用 [Notification.Action.Builder] 构造，避免 `Builder.addAction(int, …)` 的过时重载。 */
    private fun actions(
        denyLabel: String,
        denyIntent: PendingIntent,
        allowLabel: String,
        allowIntent: PendingIntent,
    ): List<Notification.Action> = listOf(
        Notification.Action.Builder(null, denyLabel, denyIntent).build(),
        Notification.Action.Builder(null, allowLabel, allowIntent).build(),
    )

    /**
     * 通知由**被挂钩的宿主进程**发出，因此受该宿主自身的通知权限约束：
     * Android 13+ 若宿主没有 `POST_NOTIFICATIONS`，通知不会显示。
     */
    private fun notificationsBlocked(context: Context?): Boolean {
        if (context == null) return true
        return try {
            val manager = notificationManager(context) ?: return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                manager.areNotificationsEnabled().not()
            } else {
                false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cannot query notification state", t)
            true
        }
    }

    /** 模块自身的名称 / 图标：只能通过 Xposed 服务返回的 ApplicationInfo 获取。 */
    private fun moduleLabel(context: Context): String {
        moduleLabel?.let { return it }
        val label = try {
            val info = module.moduleApplicationInfo
            context.packageManager.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            Log.w(TAG, "module label unavailable", t)
            null
        } ?: DEFAULT_TITLE
        moduleLabel = label
        return label
    }

    private fun moduleIcon(context: Context): Int {
        if (moduleIcon != 0) return moduleIcon
        val icon = try {
            module.moduleApplicationInfo.icon
        } catch (t: Throwable) {
            Log.w(TAG, "module icon unavailable", t)
            0
        }
        val resolved = icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_alert
        moduleIcon = resolved
        return resolved
    }

    @SuppressLint("DiscouragedApi")
    private fun appLabel(context: Context, packageName: String): String = try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
            .takeIf { it.isNotBlank() }
            ?: packageName
    } catch (_: Throwable) {
        packageName
    }

    /** 模块自身的包名：debug 变体带 `.debug` 后缀，必须取真实值而不是硬编码。 */
    private fun modulePackage(context: Context): String = try {
        module.moduleApplicationInfo.packageName
    } catch (t: Throwable) {
        Log.w(TAG, "module package unavailable", t)
        context.packageName
    }

    private fun notificationId(target: String): Int = target.hashCode() and 0x7FFFFFFF

    /** 稳定且互不相同的请求码，保证同一目标反复触发时复用同一个 [PendingIntent]。 */
    private fun requestCode(target: String): Int = (target.hashCode() and 0x3FFFFFFF) * 2

    private fun log(message: String) {
        try {
            module.log(Log.INFO, TAG, message)
        } catch (_: Throwable) {
            // 忽略日志失败
        }
    }

    companion object {
        const val TAG = "SensorLaunchGuard"

        /** 「禁止」按钮的动作：模块界面收到后立即退出，不执行任何拉起。 */
        const val ACTION_DENY = "io.github.sensorlaunchguard.action.DENY_LAUNCH"

        private const val CHANNEL_ID = "launch_prompt"
        private const val CHANNEL_NAME = "跳转询问"

        private const val DEFAULT_TITLE = "应用行为守卫"
        private const val TEXT_MIDDLE = "正在试图拉起"
        private const val TEXT_QUESTION = "。是否允许？"
        private const val TEXT_DENY = "禁止"
        private const val TEXT_ALLOW_ONCE = "允许（本次）"

        /** 同一 来源+目标 在此时长内只询问一次，避免连环弹通知。 */
        private const val NOTIFY_INTERVAL_MS = 3_000L

        /**
         * 一次性豁免的有效窗口。
         *
         * 取 30 秒：足够覆盖「用户点通知 → 支付 SDK 重试同一个 Intent」，
         * 又不至于让这条豁免在长时间里持续放行。
         */
        private const val ONE_SHOT_WINDOW_MS = 30_000L
    }
}
