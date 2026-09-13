package io.github.sensorlaunchguard.data

/**
 * 一次出站跳转的操作类型。
 *
 * 豁免默认只覆盖用户可见的「拉起应用」路径，Service / 广播属于更激进的限制，
 * 需要显式勾选才生效。
 */
enum class LaunchOperation {
    ACTIVITY,
    INTENT_SENDER,
    PENDING_INTENT,
    SERVICE,
    BROADCAST,
}

/** 豁免的判定结果。[reason] 用于日志，能直接说明「为什么放行」。 */
enum class LaunchDecision {
    /** 发起方没有开启限制。 */
    ALLOW_UNRESTRICTED,

    /** 目标是发起方自己的组件。 */
    ALLOW_SELF,

    /** 目标是系统选择器，且该来源允许使用选择器。 */
    ALLOW_CHOOSER,

    /** 命中未过期的临时豁免（含通知里点「允许（本次）」写入的一次性豁免）。 */
    ALLOW_TEMPORARY_EXEMPTION,

    /** 命中永久目标豁免，且操作类型被允许。 */
    ALLOW_PERMANENT_EXEMPTION,

    /** 目标无法解析，按兼容模式放行。 */
    ALLOW_UNRESOLVED_COMPATIBILITY,

    /** 其余情况一律拦截。 */
    BLOCK,
    ;

    val allowed: Boolean
        get() = this != BLOCK

    /** 日志用标签。 */
    val tag: String
        get() = name
}

/**
 * 把一次跳转的全部已知信息收敛成一个不可变请求，交给 [RuleSnapshot.decide] 统一裁决。
 *
 * 只在钩子内构造，字段都来自已经拿到的对象，不做任何 I/O。
 */
data class LaunchRequest(
    val sourcePackages: Set<String>,
    val targetPackage: String?,
    val operation: LaunchOperation,
    val action: String? = null,
    val scheme: String? = null,
    val component: String? = null,
    val isSystemChooser: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)
