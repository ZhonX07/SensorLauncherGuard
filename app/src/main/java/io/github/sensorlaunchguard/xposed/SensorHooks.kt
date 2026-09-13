package io.github.sensorlaunchguard.xposed

import android.annotation.SuppressLint
import android.hardware.Sensor
import android.hardware.SensorDirectChannel
import android.hardware.SensorManager
import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 陀螺仪拦截器。
 *
 * 查询、普通监听、事件派发和 DirectChannel 四层都覆盖：即使应用在开关打开前已经注册监听，
 * 事件派发层也会立即停止回调；关闭开关后原监听可继续收到数据。
 * NDK `ASensorManager` 仍属于 Java 模块无法覆盖的边界，详见 README。
 */
internal object SensorHooks {

    private const val TAG = "SensorLaunchGuard"

    /** 标准、未校准、受限轴、受限轴未校准陀螺仪。 */
    private val GYRO_TYPES = setOf(
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
        39, // TYPE_GYROSCOPE_LIMITED_AXES (API 33)
        41, // TYPE_GYROSCOPE_LIMITED_AXES_UNCALIBRATED (API 33)
    )

    private val eventBlockLogged = AtomicBoolean(false)

    fun install(module: XposedModule, rules: RulesHolder) {
        installQueryHooks(module, rules)

        val systemSensorManager = loadClass("android.hardware.SystemSensorManager")
        installRegistrationHooks(module, rules, systemSensorManager)
        installDirectChannelHooks(module, rules, systemSensorManager)
        installEventDispatchHook(module, rules, systemSensorManager)
    }

    private fun installQueryHooks(module: XposedModule, rules: RulesHolder) {
        val sensorManager = SensorManager::class.java

        for (name in arrayOf("getDefaultSensor", "getDefaultSensorInternal")) {
            for (method in sensorManager.declaredMethods.filter { it.name == name }) {
                val typeIndex = method.parameterTypes.indexOfFirst { it == Int::class.javaPrimitiveType }
                if (typeIndex < 0) continue
                Hooks.install(module, method, name) { chain ->
                    val type = chain.args.getOrNull(typeIndex) as? Int
                    if (isBlocked(rules) && isGyro(type)) null else chain.proceed()
                }
            }
        }

        for (name in arrayOf("getSensorList", "getDynamicSensorList")) {
            for (method in sensorManager.declaredMethods.filter { it.name == name }) {
                Hooks.install(module, method, name) { chain ->
                    val result = chain.proceed()
                    if (isBlocked(rules)) stripGyro(result) else result
                }
            }
        }
    }

    private fun installRegistrationHooks(
        module: XposedModule,
        rules: RulesHolder,
        systemSensorManager: Class<*>?,
    ) {
        if (systemSensorManager == null) {
            Hooks.log(module, "registration hooks skipped: SystemSensorManager unavailable")
            return
        }

        var installed = 0
        for (method in systemSensorManager.declaredMethods) {
            if (Modifier.isStatic(method.modifiers) ||
                method.name !in setOf("registerListener", "registerListenerImpl") ||
                method.returnType != java.lang.Boolean.TYPE
            ) continue

            val sensorIndex = method.parameterTypes.indexOfFirst(Sensor::class.java::isAssignableFrom)
            if (sensorIndex < 0) continue

            if (Hooks.install(module, method, method.name) { chain ->
                    val sensor = chain.args.getOrNull(sensorIndex) as? Sensor
                    if (sensor != null && isGyro(sensor.type) && isBlocked(rules)) {
                        Hooks.log(module, "blocked gyroscope registration type=${sensor.type}")
                        false
                    } else {
                        chain.proceed()
                    }
                }
            ) installed++
        }
        Hooks.log(module, "registration hooks installed: $installed")
    }

    /** 阻止共享内存 DirectChannel 的新配置；RATE_STOP 必须放行，保证应用仍可正确释放通道。 */
    private fun installDirectChannelHooks(
        module: XposedModule,
        rules: RulesHolder,
        systemSensorManager: Class<*>?,
    ) {
        val candidates = buildList {
            addAll(SensorDirectChannel::class.java.declaredMethods.filter { it.name == "configure" })
            if (systemSensorManager != null) {
                addAll(
                    systemSensorManager.declaredMethods.filter {
                        it.name == "configureDirectChannelImpl" && !Modifier.isStatic(it.modifiers)
                    },
                )
            }
        }

        var installed = 0
        for (method in candidates) {
            val sensorIndex = method.parameterTypes.indexOfFirst(Sensor::class.java::isAssignableFrom)
            val rateIndex = method.parameterTypes.indexOfLast { it == Int::class.javaPrimitiveType }
            if (sensorIndex < 0 || rateIndex < 0) continue

            if (Hooks.install(module, method, method.name) { chain ->
                    val sensor = chain.args.getOrNull(sensorIndex) as? Sensor
                    val rate = chain.args.getOrNull(rateIndex) as? Int
                    if (sensor != null && isGyro(sensor.type) &&
                        rate != SensorDirectChannel.RATE_STOP && isBlocked(rules)
                    ) {
                        Hooks.log(module, "blocked gyroscope direct channel type=${sensor.type}")
                        0
                    } else {
                        chain.proceed()
                    }
                }
            ) installed++
        }
        Hooks.log(module, "direct channel hooks installed: $installed")
    }

    /** 阻止已注册监听继续收到事件，使规则变更真正实时生效。 */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun installEventDispatchHook(
        module: XposedModule,
        rules: RulesHolder,
        systemSensorManager: Class<*>?,
    ) {
        if (systemSensorManager == null) return
        try {
            val queueClass = loadClass("android.hardware.SystemSensorManager\$SensorEventQueue") ?: return
            val baseQueueClass = queueClass.superclass ?: return
            val managerField = baseQueueClass.declaredFields.firstOrNull {
                systemSensorManager.isAssignableFrom(it.type)
            }?.accessible() ?: return
            val handleMapField = systemSensorManager.declaredFields.firstOrNull {
                it.name == "mHandleToSensor"
            }?.accessible() ?: return

            var installed = 0
            for (method in queueClass.declaredMethods.filter { it.name == "dispatchSensorEvent" }) {
                val handleIndex = method.parameterTypes.indexOfFirst { it == Int::class.javaPrimitiveType }
                if (handleIndex < 0) continue
                if (Hooks.install(module, method, method.name) { chain ->
                        if (!isBlocked(rules)) return@install chain.proceed()
                        val handle = chain.args.getOrNull(handleIndex) as? Int
                        val sensor = handle?.let {
                            sensorForHandle(chain.thisObject, it, managerField, handleMapField)
                        }
                        if (sensor != null && isGyro(sensor.type)) {
                            if (eventBlockLogged.compareAndSet(false, true)) {
                                Hooks.log(module, "blocked live gyroscope events type=${sensor.type}")
                            }
                            null
                        } else {
                            chain.proceed()
                        }
                    }
                ) installed++
            }
            Hooks.log(module, "event dispatch hooks installed: $installed")
        } catch (t: Throwable) {
            Log.w(TAG, "event dispatch hook unavailable", t)
        }
    }

    private fun sensorForHandle(
        queue: Any?,
        handle: Int,
        managerField: Field,
        handleMapField: Field,
    ): Sensor? = try {
        val manager = managerField.get(queue) ?: return null
        when (val index = handleMapField.get(manager)) {
            is Map<*, *> -> index[handle] as? Sensor
            null -> null
            else -> index.javaClass.getMethod("get", Int::class.javaPrimitiveType)
                .invoke(index, handle) as? Sensor
        }
    } catch (_: Throwable) {
        null
    }

    private fun Field.accessible(): Field = apply { isAccessible = true }

    @SuppressLint("PrivateApi")
    private fun loadClass(name: String): Class<*>? {
        for (classLoader in arrayOf<ClassLoader?>(null, SensorHooks::class.java.classLoader)) {
            try {
                return Class.forName(name, false, classLoader)
            } catch (_: Throwable) {
                // 尝试下一个类加载器。
            }
        }
        Log.w(TAG, "$name unavailable")
        return null
    }

    private fun isBlocked(rules: RulesHolder): Boolean =
        rules.isGyroBlockedFor(PackageResolver.currentProcessPackages())

    private fun isGyro(sensorType: Int?): Boolean = sensorType != null && sensorType in GYRO_TYPES

    /** 保留原返回容器类型，避免厂商 ROM 返回 Sensor[] 时发生 ClassCastException。 */
    private fun stripGyro(result: Any?): Any? = when (result) {
        is List<*> -> result.filterNot { it is Sensor && isGyro(it.type) }
        is Array<*> -> {
            val kept = result.filterNot { it is Sensor && isGyro(it.type) }
            val component = result.javaClass.componentType ?: return result
            java.lang.reflect.Array.newInstance(component, kept.size).also { copy ->
                kept.forEachIndexed { index, value -> java.lang.reflect.Array.set(copy, index, value) }
            }
        }
        else -> result
    }
}
