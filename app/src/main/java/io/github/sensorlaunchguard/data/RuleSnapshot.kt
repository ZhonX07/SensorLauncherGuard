package io.github.sensorlaunchguard.data

data class RuleSnapshot(
    val gyroBlocked: Set<String>,
    val launchBlocked: Set<String>,
    /** 全局开关：命中拉起规则时弹通知询问，而不是静默拦截。 */
    val promptLaunch: Boolean = false,
    /** 永久跳转豁免：来源包 → 允许拉起的目标包集合。 */
    val exemptTargets: Map<String, Set<String>> = emptyMap(),
    /** 临时豁免：来源包 → 目标包 → 过期时间（`System.currentTimeMillis()`）。 */
    val exemptUntil: Map<String, Map<String, Long>> = emptyMap(),
    /** 允许使用系统选择器的来源包。 */
    val chooserAllowed: Set<String> = emptySet(),
    /** 全局：目标无法解析时是否放行（兼容模式）。 */
    val allowUnresolved: Boolean = true,
) {
    fun isGyroBlocked(packageName: String): Boolean = packageName in gyroBlocked

    fun isLaunchBlocked(packageName: String): Boolean = packageName in launchBlocked

    /** 该来源是否配置过任何豁免（用于 UI 展示「N 个应用」）。 */
    fun exemptionCount(source: String): Int =
        (exemptTargets[source]?.size ?: 0) + (exemptUntil[source]?.count { it.value > now() } ?: 0)

    /**
     * 跳转裁决：全模块唯一的策略入口。
     *
     * 判定顺序即安全边界，改动顺序前请先想清楚后果：
     * 未受限来源 → 自身组件 → 选择器 → 临时豁免 → 永久豁免 → 无法解析 → 拦截。
     *
     * 注意分支顺序上「临时豁免」先于「永久豁免」：两者都命中时结果相同，
     * 但日志里区分开能说明用户刚刚点过询问通知。
     *
     * 已知限制：目标包为 null 意味着**无法判断它是不是发起方自己的组件**
     * （解析依赖 `Context.packageManager`，钩子内不一定拿得到，且不能做 I/O），
     * 因此未解析的目标只能交给兼容模式统一处理。
     */
    fun decide(request: LaunchRequest): LaunchDecision {
        if (request.sourcePackages.none(::isLaunchBlocked)) {
            return LaunchDecision.ALLOW_UNRESTRICTED
        }

        val target = request.targetPackage
        if (target.isNullOrEmpty()) {
            return if (allowUnresolved) {
                LaunchDecision.ALLOW_UNRESOLVED_COMPATIBILITY
            } else {
                LaunchDecision.BLOCK
            }
        }

        if (request.sourcePackages.any { it == target }) {
            return LaunchDecision.ALLOW_SELF
        }

        if (request.isSystemChooser && request.sourcePackages.any(chooserAllowed::contains)) {
            return LaunchDecision.ALLOW_CHOOSER
        }

        val now = request.timestamp
        val temporary = request.sourcePackages.any { source ->
            (exemptUntil[source]?.get(target) ?: 0L) > now
        }
        if (temporary) return LaunchDecision.ALLOW_TEMPORARY_EXEMPTION

        val permanent = request.sourcePackages.any { source ->
            exemptTargets[source]?.contains(target) == true
        }
        if (permanent) return LaunchDecision.ALLOW_PERMANENT_EXEMPTION

        return LaunchDecision.BLOCK
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        val EMPTY = RuleSnapshot(emptySet(), emptySet(), promptLaunch = false)
    }
}
