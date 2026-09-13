package io.github.sensorlaunchguard.data

import android.annotation.SuppressLint
import android.content.SharedPreferences

/**
 * 规则模型：远程共享偏好组 [GROUP] 中的键。
 *
 * 只存「开启」语义（布尔只写 `true`，关闭时移除键），因此默认状态全部关闭。
 * 模块进程侧与 UI 进程侧都必须使用同一个组名，否则规则无法互通。
 *
 * 键设计遵循 [GPT 建议的按来源分组]：豁免按来源包存一个 `StringSet`，
 * 避免每条规则都编码成一大段 JSON，也让钩子侧只需常数级查表。
 */
object RuleKeys {
    const val GROUP = "rules"

    const val GYRO_PREFIX = "gyro:"
    const val LAUNCH_PREFIX = "launch:"

    /** 全局：拉起拦截时是否弹通知询问（true = 询问，false = 静默拦截）。 */
    const val GLOBAL_PROMPT_LAUNCH = "prompt:launch"

    /** 全局：目标无法解析时是否放行（兼容模式）。 */
    const val GLOBAL_ALLOW_UNRESOLVED = "policy:allow_unresolved"

    /** 配置格式版本，便于以后平滑迁移。 */
    const val SCHEMA_VERSION = "policy_schema_version"

    private const val EXEMPT_TARGETS_PREFIX = "exempt_targets:"
    private const val EXEMPT_UNTIL_PREFIX = "exempt_until:"
    private const val CHOOSER_PREFIX = "exempt_chooser:"

    internal const val TARGETS_PREFIX = EXEMPT_TARGETS_PREFIX
    internal const val UNTIL_PREFIX = EXEMPT_UNTIL_PREFIX
    internal const val CHOOSER_KEY_PREFIX = CHOOSER_PREFIX

    fun gyroKey(packageName: String): String = GYRO_PREFIX + packageName

    fun launchKey(packageName: String): String = LAUNCH_PREFIX + packageName

    /** `exempt_targets:<source>` = `Set<String>`，该来源可永久拉起的目标包。 */
    fun exemptTargetsKey(sourcePackage: String): String = EXEMPT_TARGETS_PREFIX + sourcePackage

    /** `exempt_until:<source>:<target>` = `Long`（`System.currentTimeMillis()` 过期时刻）。 */
    fun exemptUntilKey(sourcePackage: String, targetPackage: String): String =
        "$EXEMPT_UNTIL_PREFIX$sourcePackage:$targetPackage"

    /** `exempt_chooser:<source>` = `true`，允许该来源使用系统选择器。 */
    fun chooserKey(sourcePackage: String): String = CHOOSER_PREFIX + sourcePackage

    fun isExemptUntilKey(key: String): Boolean = key.startsWith(EXEMPT_UNTIL_PREFIX)
}

/**
 * 把远程 [SharedPreferences] 解析成不可变的 [RuleSnapshot]，供 UI 与钩子共享解析逻辑。
 *
 * 解析必须容错：任何单条数据损坏都只丢弃该条，绝不能影响其它规则，
 * 更不能因为豁免数据损坏而意外扩大权限（豁免 fail-closed）。
 */
object RuleStore {

    /** 当前配置格式版本。 */
    const val CURRENT_SCHEMA_VERSION = 2

    fun snapshot(preferences: SharedPreferences?): RuleSnapshot {
        if (preferences == null) return RuleSnapshot.EMPTY
        return snapshot(preferences.all)
    }

    @Suppress("UNCHECKED_CAST")
    fun snapshot(entries: Map<String, *>): RuleSnapshot {
        if (entries.isEmpty()) return RuleSnapshot.EMPTY

        val gyro = HashSet<String>()
        val launch = HashSet<String>()
        val exemptTargets = HashMap<String, MutableSet<String>>()
        val exemptUntil = HashMap<String, MutableMap<String, Long>>()
        val chooserAllowed = HashSet<String>()

        var promptLaunch = false
        // 兼容模式默认开启，与「解析失败就放行」的历史行为保持一致。
        var allowUnresolved = true

        for ((key, value) in entries) {
            when {
                key == RuleKeys.GLOBAL_PROMPT_LAUNCH -> promptLaunch = value == true

                key == RuleKeys.GLOBAL_ALLOW_UNRESOLVED -> allowUnresolved = value != false

                key.startsWith(RuleKeys.GYRO_PREFIX) -> if (value == true) {
                    gyro += key.substring(RuleKeys.GYRO_PREFIX.length)
                }

                key.startsWith(RuleKeys.LAUNCH_PREFIX) -> if (value == true) {
                    launch += key.substring(RuleKeys.LAUNCH_PREFIX.length)
                }

                key.startsWith(RuleKeys.TARGETS_PREFIX) -> {
                    val source = key.substringAfter(':').takeIf(String::isNotEmpty) ?: continue
                    val targets = (value as? Set<*>)?.filterIsInstance<String>().orEmpty()
                    if (targets.isNotEmpty()) {
                        exemptTargets.getOrPut(source) { HashSet() } += targets
                    }
                }

                key.startsWith(RuleKeys.UNTIL_PREFIX) -> {
                    // exempt_until:<source>:<target>；包名不含 ':'，可安全二分。
                    val body = key.substringAfter(':')
                    val separator = body.indexOf(':')
                    if (separator <= 0) continue
                    val source = body.substring(0, separator)
                    val target = body.substring(separator + 1)
                    val expiresAt = (value as? Number)?.toLong() ?: continue
                    if (target.isEmpty() || expiresAt <= 0L) continue
                    exemptUntil.getOrPut(source) { HashMap() }[target] = expiresAt
                }

                key.startsWith(RuleKeys.CHOOSER_KEY_PREFIX) -> if (value == true) {
                    val source = key.substringAfter(':').takeIf(String::isNotEmpty) ?: continue
                    chooserAllowed += source
                }
            }
        }

        return RuleSnapshot(
            gyroBlocked = gyro,
            launchBlocked = launch,
            promptLaunch = promptLaunch,
            exemptTargets = exemptTargets,
            exemptUntil = exemptUntil,
            chooserAllowed = chooserAllowed,
            allowUnresolved = allowUnresolved,
        )
    }

    /** 写入或清除单个规则键。 */
    @SuppressLint("UseKtx")
    fun setRule(
        preferences: SharedPreferences,
        packageName: String,
        gyro: Boolean? = null,
        launch: Boolean? = null,
    ): Boolean {
        val editor = preferences.edit()
        gyro?.let { value ->
            val key = RuleKeys.gyroKey(packageName)
            if (value) editor.putBoolean(key, true) else editor.remove(key)
        }
        launch?.let { value ->
            val key = RuleKeys.launchKey(packageName)
            if (value) editor.putBoolean(key, true) else editor.remove(key)
        }
        return editor.commit()
    }

    /** 写入或清除全局「拉起时询问」开关。 */
    @SuppressLint("UseKtx")
    fun setPromptLaunch(preferences: SharedPreferences, enabled: Boolean): Boolean {
        val editor = preferences.edit()
        if (enabled) {
            editor.putBoolean(RuleKeys.GLOBAL_PROMPT_LAUNCH, true)
        } else {
            editor.remove(RuleKeys.GLOBAL_PROMPT_LAUNCH)
        }
        return editor.commit()
    }

    /** 新增 / 移除一条永久目标豁免。 */
    @SuppressLint("UseKtx")
    fun setExemptTarget(
        preferences: SharedPreferences,
        sourcePackage: String,
        targetPackage: String,
        exempt: Boolean,
    ): Boolean {
        val key = RuleKeys.exemptTargetsKey(sourcePackage)
        val current = preferences.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        val changed = if (exempt) current.add(targetPackage) else current.remove(targetPackage)
        if (!changed) return true

        val editor = preferences.edit()
        if (current.isEmpty()) {
            editor.remove(key)
        } else {
            // 必须传副本：RemotePreferences 会直接持有这个 Set 的引用。
            editor.putStringSet(key, HashSet(current))
        }
        return editor.commit()
    }

    /** 写入一次性 / 限时豁免。 */
    @SuppressLint("UseKtx")
    fun setExemptUntil(
        preferences: SharedPreferences,
        sourcePackage: String,
        targetPackage: String,
        expiresAt: Long,
    ): Boolean {
        val key = RuleKeys.exemptUntilKey(sourcePackage, targetPackage)
        val editor = preferences.edit()
        if (expiresAt <= System.currentTimeMillis()) {
            editor.remove(key)
        } else {
            editor.putLong(key, expiresAt)
        }
        return editor.commit()
    }

    /** 允许 / 禁止该来源使用系统选择器。 */
    @SuppressLint("UseKtx")
    fun setChooserAllowed(
        preferences: SharedPreferences,
        sourcePackage: String,
        allowed: Boolean,
    ): Boolean {
        val key = RuleKeys.chooserKey(sourcePackage)
        val editor = preferences.edit()
        if (allowed) editor.putBoolean(key, true) else editor.remove(key)
        return editor.commit()
    }

    /** 清理已过期的临时豁免键，返回清理条数。 */
    @SuppressLint("UseKtx")
    fun pruneExpired(preferences: SharedPreferences): Int {
        val now = System.currentTimeMillis()
        val expired = preferences.all.filter { (key, value) ->
            RuleKeys.isExemptUntilKey(key) && ((value as? Number)?.toLong() ?: 0L) <= now
        }.keys
        if (expired.isEmpty()) return 0

        val editor = preferences.edit()
        expired.forEach(editor::remove)
        return if (editor.commit()) expired.size else 0
    }
}
