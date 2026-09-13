package io.github.sensorlaunchguard.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模块入口（由 `META-INF/xposed/java_init.list` 声明）。
 *
 * 生命周期：
 * - [onModuleLoaded]：每个进程每次模块加载调用一次，注册远程偏好监听（规则实时生效）；
 * - [onPackageReady]：当前进程的包类加载器就绪时调用，安装陀螺仪与启动拦截钩子。
 *
 * 只有 LSPosed 作用域中勾选的应用进程才会走到 [onPackageReady]，因此无需再做包名判断。
 * 每个钩子独立 try/catch，失败仅记录日志，绝不影响宿主启动。
 */
class GuardModule : XposedModule() {

    private var rules: RulesHolder? = null
    private val hooksInstalled = AtomicBoolean(false)

    @Volatile
    private var systemServer = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        try {
            systemServer = param.isSystemServer
            val holder = RulesHolder(this)
            holder.start()
            rules = holder
            logMessage("module loaded, framework=${frameworkName} api=${apiVersion}")
        } catch (t: Throwable) {
            Log.w(TAG, "onModuleLoaded failed", t)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        PackageResolver.addPackageName(param.packageName)

        // WebView/createPackageContext 会在同一进程再次触发 onPackageReady；重复挂钩会形成多层拦截链。
        if (systemServer || !hooksInstalled.compareAndSet(false, true)) {
            logMessage("additional package ready: ${param.packageName}; hooks already installed")
            return
        }

        val holder = rules
        if (holder == null) {
            Log.w(TAG, "package ready before module loaded: ${param.packageName}")
            return
        }

        installSafely("sensor hooks") { SensorHooks.install(this, holder) }
        installSafely("launch hooks") {
            // 询问开关实时读取规则快照，改动无需重启宿主进程。
            LaunchHooks.install(this, holder, LaunchPrompt(this, holder::isPromptLaunchEnabled))
        }
        logMessage("hooks ready for ${param.packageName}")
    }

    private inline fun installSafely(label: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "install $label failed", t)
        }
    }

    private fun logMessage(message: String) {
        try {
            log(Log.INFO, TAG, message)
        } catch (_: Throwable) {
            // 忽略日志失败
        }
    }

    private companion object {
        const val TAG = "SensorLaunchGuard"
    }
}
