package io.github.sensorlaunchguard

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import com.google.android.material.color.DynamicColors
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.sensorlaunchguard.data.RuleKeys
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 应用进程入口：持有 LSPosed（Vector / LSPosed 等实现）提供的 [XposedService]。
 *
 * 生命周期要点：
 * - [XposedServiceHelper.registerListener] 只允许注册一次，之后框架每次绑定/解绑都会回调；
 * - 服务可能晚于 Activity 到达，因此已注册的监听者会立刻收到一次当前状态；
 * - 服务位于框架进程，任何调用都必须做异常兜底（框架可能被卸载或重启）。
 */
class GuardApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 不启用 DynamicColors.applyToActivitiesIfAvailable()：
        // 动态取色会用系统壁纸派生的颜色**覆盖**我们声明的 brand 色板，
        // 而本应用使用固定的品牌配色（靛蓝 / 薄荷 / 淡紫，与图标一致）。
        // 若要改回跟随系统取色，取消下面这行的注释即可。
        // DynamicColors.applyToActivitiesIfAvailable(this)
        XposedServiceHelper.registerListener(
            object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    Log.i(TAG, "Xposed service bound: ${service.frameworkName}")
                    setService(service)
                }

                override fun onServiceDied(service: XposedService) {
                    Log.i(TAG, "Xposed service died")
                    setService(null)
                }
            },
        )
    }

    private fun setService(service: XposedService?) {
        currentService = service
        for (listener in listeners) {
            // 单个监听者出错不能影响其它监听者，也不能打断框架回调线程。
            runCatching { listener.onServiceChanged(service) }
                .onFailure { Log.w(TAG, "service listener failed", it) }
        }
    }

    /** 已连接的服务；未连接时为 null。 */
    val service: XposedService?
        get() = currentService

    /** 远程规则偏好组；未连接 LSPosed 时返回 null。 */
    fun rulesPreferences(): SharedPreferences? =
        runCatching { currentService?.getRemotePreferences(RuleKeys.GROUP) }
            .onFailure { Log.w(TAG, "getRemotePreferences failed", it) }
            .getOrNull()

    fun addServiceListener(listener: ServiceListener) {
        listeners += listener
        listener.onServiceChanged(currentService)
    }

    fun removeServiceListener(listener: ServiceListener) {
        listeners -= listener
    }

    /** LSPosed 连接状态变化回调。 */
    fun interface ServiceListener {
        fun onServiceChanged(service: XposedService?)
    }

    companion object {
        private const val TAG = "SensorLaunchGuard"

        private val listeners = CopyOnWriteArraySet<ServiceListener>()

        @Volatile
        private var currentService: XposedService? = null

        @Volatile
        private var instance: GuardApplication? = null

        /** 已初始化的应用实例；仅在 [onCreate] 之后可用。 */
        val app: GuardApplication?
            get() = instance
    }
}
