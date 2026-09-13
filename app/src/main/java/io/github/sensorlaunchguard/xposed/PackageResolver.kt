package io.github.sensorlaunchguard.xposed

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 目标进程身份解析。
 *
 * 钩子运行在宿主进程内，需要知道「我是谁」才能判断某条规则是否作用于自己。
 * 包名由 [GuardModule.onPackageReady] 注入（这是框架给出的权威值），
 * 避免依赖 `ActivityThread` 等隐藏 API 在 Android 新版本上的行为差异。
 */
internal object PackageResolver {

    private const val TAG = "SensorLaunchGuard"

    /** 仅用于诊断重复的 onPackageReady；动态加载的 WebView 包不能被误当成进程自身。 */
    private val loadedPackageNames = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var primaryPackageName: String? = null

    @Volatile
    private var identitySnapshot: Set<String> = emptySet()

    private val contextFailureLogged = AtomicBoolean(false)
    private val contextIdentityResolved = AtomicBoolean(false)

    /** 由 `onPackageReady` 调用；同一进程内包名不会变化，首次赋值即生效。 */
    fun addPackageName(name: String) {
        if (name.isBlank()) return
        loadedPackageNames += name
        if (primaryPackageName == null) {
            synchronized(this) {
                if (primaryPackageName == null) {
                    primaryPackageName = name
                    refreshIdentitySnapshot(null)
                }
            }
        }
    }

    /** 当前进程的包名；未注入时返回 `null`（此时所有规则都不生效）。 */
    fun currentPackageName(): String? = primaryPackageName

    /**
     * 当前进程归属的包名集合。
     *
     * 进程名可能带 `:remote` 之类的后缀（例如 `com.example:push`），
     * 剥离后缀后仍视为同一个应用，避免把自身的子进程跳转误判为「拉起别的应用」。
     */
    fun currentProcessPackages(): Set<String> {
        if (!contextIdentityResolved.get()) currentContext()
        return identitySnapshot
    }

    fun isCurrentProcessPackage(packageName: String?): Boolean =
        packageName != null && packageName in currentProcessPackages()

    /**
     * 当前进程的 [Context]，用于读取本进程的 [android.content.pm.ApplicationInfo]。
     *
     * `ActivityThread` 不属于公开 SDK，但它是模块在宿主进程内取得 Application 的唯一途径；
     * 失败时返回 `null`，调用方必须把 `null` 当作「信息缺失」而不是错误。
     */
    @SuppressLint("PrivateApi")
    fun currentContext(): Context? {
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val context = activityThread.getMethod("currentApplication").invoke(null) as? Application
            if (context != null) {
                refreshIdentitySnapshot(context)
                contextIdentityResolved.set(true)
            }
            context
        } catch (t: Throwable) {
            if (contextFailureLogged.compareAndSet(false, true)) {
                Log.w(TAG, "currentContext unavailable", t)
            }
            null
        }
    }

    @Synchronized
    private fun refreshIdentitySnapshot(context: Context?) {
        identitySnapshot = buildSet {
            primaryPackageName?.let(::add)
            context?.let {
                add(it.packageName)
                add(it.applicationInfo.packageName)
                it.applicationInfo.processName
                    ?.substringBefore(':')
                    ?.takeIf(String::isNotEmpty)
                    ?.let(::add)
            }
        }
    }
}
