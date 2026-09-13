package io.github.sensorlaunchguard.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 钩子安装的公共逻辑。
 *
 * 两个约定：
 * 1. 每个钩子独立 try/catch，安装失败只写日志——绝不因为某个重载不存在而拖垮宿主启动；
 * 2. 「结果值合成」：静默丢弃某个方法时，必须返回与其返回类型相容的值，
 *    否则框架/宿主会拿到一个类型不符的对象（例如把 `null` 当作 `int` 结果）。
 */
internal object Hooks {

    const val TAG = "SensorLaunchGuard"

    /**
     * 安装一个钩子。[body] 返回什么，钩子就返回什么；
     * 需要「静默丢弃」时返回其内部标记值即可。
     */
    inline fun install(
        module: XposedModule,
        method: Method,
        label: String,
        crossinline body: (XposedInterface.Chain) -> Any?,
    ): Boolean = try {
        module.hook(method)
            .setPriority(HookDefaults.PRIORITY)
            .intercept { chain -> body(chain) }
        log(module, hookedMessage(method, label))
        true
    } catch (t: Throwable) {
        Log.w(TAG, "install hook $label failed", t)
        false
    }

    /**
     * 按被钩方法的返回类型合成「什么都没发生」的结果。
     *
     * - `void` / 引用类型 → `null`
     * - `boolean` → `false`
     * - 数值类型 → `0`
     *
     * 用于丢弃 Activity 启动：`Context.startActivity` 是 void，
     * `Context.startActivityForResult` 是 int，`Instrumentation.execStartActivity` 是引用类型。
     */
    fun discardedResult(returnType: Class<*>): Any? = when (returnType) {
        Void.TYPE, java.lang.Void::class.java -> null
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }

    private fun hookedMessage(method: Method, label: String): String =
        "hooked ${method.declaringClass.name}#$label(${method.parameterTypes.joinToString(",") { it.simpleName }})"

    fun log(module: XposedModule, message: String) {
        try {
            module.log(Log.INFO, TAG, message)
        } catch (_: Throwable) {
            // 忽略日志失败
        }
    }
}
