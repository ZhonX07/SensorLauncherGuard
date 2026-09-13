package io.github.sensorlaunchguard.xposed

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.sensorlaunchguard.data.LaunchDecision
import io.github.sensorlaunchguard.data.LaunchRequest
import io.github.sensorlaunchguard.data.RuleKeys
import io.github.sensorlaunchguard.data.RuleSnapshot
import io.github.sensorlaunchguard.data.RuleStore

/**
 * 目标进程内的策略缓存。
 *
 * UI 进程通过 LSPosed 远程偏好写入规则，这里监听偏好变化并刷新 volatile 快照，
 * 因此规则与豁免都可以在目标应用运行期间实时生效，无需重启进程。
 *
 * 钩子**只读快照**，不在跳转路径上做任何 I/O 或解析——见 [RuleSnapshot.decide]。
 */
class RulesHolder(
    private val module: XposedModule,
    private val group: String = RuleKeys.GROUP,
) {
    @Volatile
    var snapshot: RuleSnapshot = RuleSnapshot.EMPTY
        private set

    /** 远程偏好句柄；仅在本进程内用于写入一次性豁免。 */
    @Volatile
    var preferences: SharedPreferences? = null
        private set

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refresh()
    }

    /** 绑定远程偏好并首次读取；失败仅日志，规则保持为空（即不拦截）。 */
    fun start() {
        try {
            val preferences = module.getRemotePreferences(group)
            this.preferences = preferences
            preferences.registerOnSharedPreferenceChangeListener(listener)
            refresh()
            log(
                "rules loaded: gyro=${snapshot.gyroBlocked.size} launch=${snapshot.launchBlocked.size} " +
                    "prompt=${snapshot.promptLaunch} exemptSources=${snapshot.exemptTargets.size}",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "cannot bind remote preferences", t)
        }
    }

    fun stop() {
        try {
            preferences?.unregisterOnSharedPreferenceChangeListener(listener)
        } catch (t: Throwable) {
            Log.w(TAG, "cannot unbind remote preferences", t)
        }
        preferences = null
    }

    /** 唯一裁决入口。 */
    fun decide(request: LaunchRequest): LaunchDecision = snapshot.decide(request)

    fun isGyroBlocked(packageName: String?): Boolean =
        packageName != null && snapshot.isGyroBlocked(packageName)

    /** 共享进程中任一归属包开启规则，就按进程整体执行；Android 无法再细分到单次调用者。 */
    fun isGyroBlockedFor(packageNames: Set<String>): Boolean =
        packageNames.any(snapshot::isGyroBlocked)

    /** 命中拉起规则时是否弹通知询问（实时读取快照，开关改动立即生效）。 */
    fun isPromptLaunchEnabled(): Boolean = snapshot.promptLaunch

    private fun refresh() {
        try {
            snapshot = RuleStore.snapshot(preferences)
        } catch (t: Throwable) {
            Log.w(TAG, "refresh rules failed", t)
        }
    }

    private fun log(message: String) {
        try {
            module.log(Log.INFO, TAG, message)
        } catch (_: Throwable) {
            // 日志失败无需处理
        }
    }

    private companion object {
        const val TAG = "SensorLaunchGuard"
    }
}

/** 提供给钩子安装器的公共日志 / 优先级约束。 */
internal object HookDefaults {
    const val PRIORITY: Int = XposedInterface.PRIORITY_DEFAULT
}
