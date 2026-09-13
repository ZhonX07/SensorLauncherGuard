package io.github.sensorlaunchguard.xposed

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Instrumentation
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.sensorlaunchguard.data.LaunchDecision
import io.github.sensorlaunchguard.data.LaunchOperation
import io.github.sensorlaunchguard.data.LaunchRequest
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * 阻止“被选中的发起方应用”拉起其它包。
 *
 * 核心语义是 source-based：规则检查当前宿主进程，而不是检查目标包是否也被勾选。
 * 覆盖 Activity、Service/前台 Service、显式广播、IntentSender 与直接 send 的 PendingIntent。
 * 纯 Binder/NDK 调用仍属于应用进程 Java Hook 的边界。
 * bindService 特意放行：WebView/GMS 等基础能力依赖跨包绑定，伪造绑定失败会诱发宿主错误解绑并崩溃。
 *
 * 所有判断都收敛到 [io.github.sensorlaunchguard.data.RuleSnapshot.decide] 这一个纯策略函数，
 * 各钩子只负责「把现场信息整理成 LaunchRequest」，不再各写一套放行逻辑。
 *
 * 命中拦截时：若「跳转询问」开启且目标明确，改为弹通知暂缓启动（见 [LaunchPrompt]）；
 * 否则静默丢弃。两条路径都不放行、也不让宿主崩溃。
 */
internal object LaunchHooks {

    private const val TAG = "SensorLaunchGuard"
    private const val LOG_DEDUP_WINDOW_MS = 5_000L

    /** 系统选择器所属包；不能把整个 `android` 包当作普通白名单放行。 */
    private const val ANDROID_PACKAGE = "android"

    private val lastLogAt = ConcurrentHashMap<String, Long>()

    fun install(module: XposedModule, rules: RulesHolder, prompt: LaunchPrompt) {
        val instrumentation = installInstrumentationHooks(module, rules, prompt)
        val context = installContextHooks(module, rules, prompt)
        val activitySender = installActivityIntentSenderHooks(module, rules, prompt)
        val pendingIntent = installPendingIntentHooks(module, rules, prompt)
        val intentSender = installIntentSenderHooks(module, rules, prompt)
        Hooks.log(
            module,
            "launch hooks installed: instrumentation=$instrumentation context=$context " +
                "activitySender=$activitySender pendingIntent=$pendingIntent intentSender=$intentSender",
        )
    }

    @SuppressLint("PrivateApi")
    private fun installInstrumentationHooks(
        module: XposedModule,
        rules: RulesHolder,
        prompt: LaunchPrompt,
    ): Int = try {
        Instrumentation::class.java.declaredMethods.count { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name.startsWith("execStartActivit") &&
                hasIntentParameter(method) &&
                installForIntents(module, rules, prompt, method, LaunchOperation.ACTIVITY)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Instrumentation hooks failed", t)
        0
    }

    @SuppressLint("PrivateApi")
    private fun installContextHooks(
        module: XposedModule,
        rules: RulesHolder,
        prompt: LaunchPrompt,
    ): Int = try {
        val target = Class.forName("android.app.ContextImpl", false, null)
        target.declaredMethods.count { method ->
            val operation = contextMethodOperation(method)
            when {
                operation != null -> installForIntents(module, rules, prompt, method, operation)
                !Modifier.isStatic(method.modifiers) &&
                    method.name == "startIntentSender" &&
                    method.parameterTypes.any(IntentSender::class.java::isAssignableFrom) ->
                    installForIntentSender(module, rules, prompt, method)
                else -> false
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "ContextImpl hooks failed", t)
        0
    }

    private fun contextMethodOperation(method: Method): LaunchOperation? {
        if (Modifier.isStatic(method.modifiers) || !hasIntentParameter(method)) return null
        return when {
            method.name in ACTIVITY_METHODS -> LaunchOperation.ACTIVITY
            method.name in SERVICE_METHODS -> LaunchOperation.SERVICE
            method.name.startsWith("sendBroadcast") -> LaunchOperation.BROADCAST
            else -> null
        }
    }

    private fun installForIntents(
        module: XposedModule,
        rules: RulesHolder,
        prompt: LaunchPrompt,
        method: Method,
        operation: LaunchOperation,
    ): Boolean {
        val discarded = Hooks.discardedResult(method.returnType)
        return Hooks.install(module, method, method.name) { chain ->
            val context = findContext(chain)
            val intents = findIntents(chain.args)
            if (intents.isEmpty()) return@install chain.proceed()

            // startActivities(Intent[]) 采用「全有或全无」：任一项未获豁免就整组拦下，
            // 不尝试从数组里摘掉某一项再继续（批量 Activity 栈依赖原始顺序）。
            var blocked: Pair<Intent, Resolution>? = null
            for (intent in intents) {
                val resolution = resolve(intent, operation, context)
                val decision = decide(rules, resolution, intent, operation)
                if (!decision.allowed) {
                    blocked = intent to resolution
                    break
                }
            }

            val (intent, resolution) = blocked ?: return@install chain.proceed()
            block(module, prompt, rules, method.name, operation, resolution, intent)
            discarded
        }
    }

    /** Activity.startIntentSenderForResult 等不会经过 ContextImpl，单独覆盖。 */
    private fun installActivityIntentSenderHooks(
        module: XposedModule,
        rules: RulesHolder,
        prompt: LaunchPrompt,
    ): Int = Activity::class.java.declaredMethods.count { method ->
        !Modifier.isStatic(method.modifiers) &&
            method.name.startsWith("startIntentSender") &&
            method.parameterTypes.any(IntentSender::class.java::isAssignableFrom) &&
            installForIntentSender(module, rules, prompt, method)
    }

    private fun installIntentSenderHooks(
        module: XposedModule,
        rules: RulesHolder,
        prompt: LaunchPrompt,
    ): Int = IntentSender::class.java.declaredMethods.count { method ->
        !Modifier.isStatic(method.modifiers) &&
            method.name == "sendIntent" &&
            installForIntentSender(module, rules, prompt, method)
    }

    private fun installForIntentSender(
        module: XposedModule,
        rules: RulesHolder,
        prompt: LaunchPrompt,
        method: Method,
    ): Boolean {
        val discarded = Hooks.discardedResult(method.returnType)
        return Hooks.install(module, method, method.name) { chain ->
            val context = findContext(chain)
            val intents = findIntents(chain.args)
            val sender = (chain.thisObject as? IntentSender)
                ?: chain.args.filterIsInstance<IntentSender>().firstOrNull()

            // 由别的应用创建的 IntentSender：等价于「跳去那个应用」，按创建者包名裁决。
            val creator = runCatching { sender?.creatorPackage }.getOrNull()
            if (creator != null) {
                val resolution = Resolution(packageName = creator, isChooser = false)
                val decision = decide(rules, resolution, intents.firstOrNull(), LaunchOperation.INTENT_SENDER)
                if (!decision.allowed) {
                    block(module, prompt, rules, method.name, LaunchOperation.INTENT_SENDER, resolution, intents.firstOrNull())
                    return@install discarded
                }
            }

            // 自身创建的 IntentSender：继续看它携带的 fill-in Intent。
            val external = intents.firstOrNull { intent ->
                val resolution = resolve(intent, LaunchOperation.ACTIVITY, context)
                !decide(rules, resolution, intent, LaunchOperation.INTENT_SENDER).allowed
            }
            if (external != null) {
                val resolution = resolve(external, LaunchOperation.ACTIVITY, context)
                block(module, prompt, rules, method.name, LaunchOperation.INTENT_SENDER, resolution, external)
                discarded
            } else {
                chain.proceed()
            }
        }
    }

    private fun installPendingIntentHooks(
        module: XposedModule,
        rules: RulesHolder,
        prompt: LaunchPrompt,
    ): Int = PendingIntent::class.java.declaredMethods.count { method ->
        !Modifier.isStatic(method.modifiers) &&
            method.name in setOf("send", "sendAndReturnResult") &&
            Hooks.install(module, method, method.name) { chain ->
                val pending = chain.thisObject as? PendingIntent
                    ?: return@install chain.proceed()
                val context = findContext(chain)
                val intents = findIntents(chain.args)

                val operation = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        runCatching { pending.isService || pending.isForegroundService }
                            .getOrDefault(false) -> LaunchOperation.SERVICE
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        runCatching { pending.isBroadcast }.getOrDefault(false) -> LaunchOperation.BROADCAST
                    else -> LaunchOperation.PENDING_INTENT
                }

                val creator = runCatching { pending.creatorPackage }.getOrNull()
                if (creator != null) {
                    val resolution = Resolution(packageName = creator, isChooser = false)
                    if (!decide(rules, resolution, intents.firstOrNull(), operation).allowed) {
                        block(module, prompt, rules, method.name, operation, resolution, intents.firstOrNull())
                        return@install Hooks.discardedResult(method.returnType)
                    }
                }

                val external = intents.firstOrNull { intent ->
                    !decide(rules, resolve(intent, operation, context), intent, operation).allowed
                }
                if (external != null) {
                    val resolution = resolve(external, operation, context)
                    block(module, prompt, rules, method.name, operation, resolution, external)
                    Hooks.discardedResult(method.returnType)
                } else {
                    chain.proceed()
                }
            }
    }

    // region 裁决

    private data class Resolution(val packageName: String?, val isChooser: Boolean)

    private fun decide(
        rules: RulesHolder,
        resolution: Resolution,
        intent: Intent?,
        operation: LaunchOperation,
    ): LaunchDecision = rules.decide(
        LaunchRequest(
            sourcePackages = PackageResolver.currentProcessPackages(),
            targetPackage = resolution.packageName,
            operation = operation,
            action = intent?.action,
            scheme = intent?.data?.scheme,
            component = intent?.component?.flattenToShortString(),
            isSystemChooser = resolution.isChooser,
        ),
    )

    private fun isSelf(target: String, context: Context?): Boolean =
        PackageResolver.isCurrentProcessPackage(target) ||
            target == context?.packageName ||
            target == context?.applicationInfo?.packageName

    private fun findContext(chain: XposedInterface.Chain): Context? =
        (chain.thisObject as? Context)
            ?: chain.args.filterIsInstance<Context>().firstOrNull()
            ?: PackageResolver.currentContext()

    private fun hasIntentParameter(method: Method): Boolean = method.parameterTypes.any { type ->
        Intent::class.java.isAssignableFrom(type) ||
            (type.isArray && type.componentType?.let(Intent::class.java::isAssignableFrom) == true)
    }

    private fun findIntents(arguments: List<Any>): List<Intent> = buildList {
        for (argument in arguments) {
            when (argument) {
                is Intent -> add(argument)
                is Array<*> -> addAll(argument.filterIsInstance<Intent>())
            }
        }
    }

    /**
     * 解析目标：显式 component → `setPackage` → selector → `PackageManager` 解析。
     *
     * `android` 包被识别为系统选择器而不是普通目标——否则「允许 android」会连带放行
     * 系统设置、文件选择器、安装器等大量界面。
     */
    @Suppress("DEPRECATION")
    private fun resolve(intent: Intent, operation: LaunchOperation, context: Context?): Resolution {
        intent.component?.packageName?.takeIf(String::isNotEmpty)?.let { component ->
            return Resolution(component, isSystemChooser(intent, component))
        }
        intent.`package`?.takeIf(String::isNotEmpty)?.let { packageName ->
            return Resolution(packageName, isSystemChooser(intent, packageName))
        }
        intent.selector?.takeIf { it !== intent }?.let { selector ->
            val nested = resolve(selector, operation, context)
            if (nested.packageName != null) return nested
        }

        if (isChooserAction(intent)) {
            return Resolution(ANDROID_PACKAGE, isChooser = true)
        }

        val packageManager = context?.packageManager
            ?: return Resolution(null, isChooser = false)
        val resolved = runCatching {
            when (operation) {
                LaunchOperation.SERVICE -> packageManager.resolveService(intent, 0)?.serviceInfo?.packageName
                // 隐式广播可能命中多个接收者；保守当作无法解析，交给兼容模式决定。
                LaunchOperation.BROADCAST -> null
                else -> packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo?.packageName
            }
        }.getOrNull()

        return Resolution(resolved, isSystemChooser(intent, resolved))
    }

    private fun isChooserAction(intent: Intent): Boolean =
        intent.action == Intent.ACTION_CHOOSER || intent.action == "android.intent.action.CHOOSER"

    private fun isSystemChooser(intent: Intent, target: String?): Boolean =
        (target == ANDROID_PACKAGE && isChooserAction(intent)) ||
            intent.component?.className?.contains("ResolverActivity") == true ||
            intent.component?.className?.contains("ChooserActivity") == true

    /**
     * 拦截收口：记录裁决原因，再尝试弹「跳转询问」。
     *
     * 询问不可用时（宿主无通知权限、目标不明确）静默丢弃，保持「不放行」的保守策略。
     */
    private fun block(
        module: XposedModule,
        prompt: LaunchPrompt,
        rules: RulesHolder,
        operation: String,
        launchOperation: LaunchOperation,
        resolution: Resolution,
        intent: Intent?,
    ) {
        logBlock(module, operation, launchOperation, resolution, intent)
        val target = resolution.packageName ?: return
        val source = PackageResolver.currentPackageName().orEmpty()
        prompt.request(source, target, intent, rules)
    }

    private fun logBlock(
        module: XposedModule,
        operation: String,
        launchOperation: LaunchOperation,
        resolution: Resolution,
        intent: Intent?,
    ) {
        // 去重键不含完整 URI，避免日志泄漏订单号等敏感信息。
        val key = "$operation|${launchOperation.name}|${resolution.packageName}|${intent?.action}"
        val now = SystemClock.elapsedRealtime()
        val previous = lastLogAt.put(key, now)
        if (previous != null && now - previous < LOG_DEDUP_WINDOW_MS) return

        val source = PackageResolver.currentPackageName().orEmpty()
        Hooks.log(
            module,
            "launch decision=${LaunchDecision.BLOCK.tag} operation=$operation " +
                "kind=${launchOperation.name} source=$source target=${resolution.packageName} " +
                "chooser=${resolution.isChooser} action=${intent?.action} scheme=${intent?.data?.scheme}",
        )
    }

    // endregion

    private val ACTIVITY_METHODS = setOf(
        "startActivity",
        "startActivityAsUser",
        "startActivityForResult",
        "startActivities",
        "startActivitiesAsUser",
    )

    private val SERVICE_METHODS = setOf(
        "startService",
        "startServiceAsUser",
        "startForegroundService",
        "startForegroundServiceAsUser",
    )
}
