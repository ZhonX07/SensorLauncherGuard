package io.github.sensorlaunchguard.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import java.text.Collator
import java.util.Locale

/** 应用列表过滤维度。 */
enum class AppFilter { ALL, USER, SYSTEM }

/**
 * 已安装应用扫描器。
 *
 * 设计要点：
 * - 一次性读取全量应用（含图标），在后台线程完成，避免主线程抖动；
 * - 使用 [Collator] 做本地化排序；
 * - 已启用任一规则的应用置顶，其余按字母序；
 * - 搜索为应用名/包名的小写包含匹配（不做拼音）。
 *
 * 本类不是线程安全的：只在单一线程（扫描线程或主线程）上被调用。
 */
class AppRepository(context: Context) {

    private val packageManager: PackageManager = context.packageManager

    private val collator: Collator = Collator.getInstance(Locale.getDefault()).apply {
        strength = Collator.SECONDARY
    }

    /** 本模块自身的包名（debug 变体带 `.debug` 后缀），扫描时排除。 */
    private val selfPackages: Set<String> = buildSet {
        add(context.packageName)
        context.applicationInfo?.packageName?.let(::add)
        add("io.github.sensorlaunchguard")
        add("io.github.sensorlaunchguard.debug")
    }

    /**
     * 读取全部已安装应用（已载入图标），并按规则状态 + 本地化字母序排序。
     *
     * 必须在后台线程调用；失败时把异常抛给调用方处理。
     */
    fun load(snapshot: RuleSnapshot): List<AppInfo> {
        val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }
        val result = ArrayList<AppInfo>(installed.size)

        for (info in installed) {
            val packageName = info.packageName ?: continue
            if (packageName in selfPackages) continue

            val label = runCatching { packageManager.getApplicationLabel(info).toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: packageName

            val icon: Drawable? = runCatching { packageManager.getApplicationIcon(info) }.getOrNull()

            result += AppInfo(
                packageName = packageName,
                label = label,
                uid = info.uid,
                isSystem = info.isSystemApp(),
                icon = icon,
                gyroBlocked = snapshot.isGyroBlocked(packageName),
                launchBlocked = snapshot.isLaunchBlocked(packageName),
            )
        }

        sort(result)
        return result
    }

    /** 用最新的规则快照刷新已有列表（不重新读包管理器），并重新排序。 */
    fun applyRules(apps: List<AppInfo>, snapshot: RuleSnapshot): List<AppInfo> {
        val refreshed = apps.map { app ->
            val gyro = snapshot.isGyroBlocked(app.packageName)
            val launch = snapshot.isLaunchBlocked(app.packageName)
            if (gyro == app.gyroBlocked && launch == app.launchBlocked) {
                app
            } else {
                app.copy(gyroBlocked = gyro, launchBlocked = launch)
            }
        }.toMutableList()

        sort(refreshed)
        return refreshed
    }

    /**
     * 只构建应用标签与图标（不读规则），供豁免列表等场景使用。
     *
     * 与 [load] 一样必须在后台线程调用。
     */
    fun labelsAndIcons(): List<AppInfo> {
        val installed = packageManager.getInstalledApplications(0)
        val result = ArrayList<AppInfo>(installed.size)

        for (info in installed) {
            val packageName = info.packageName ?: continue
            if (packageName in selfPackages) continue

            val label = runCatching { packageManager.getApplicationLabel(info).toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: packageName

            val icon: Drawable? = runCatching { packageManager.getApplicationIcon(info) }.getOrNull()

            result += AppInfo(
                packageName = packageName,
                label = label,
                uid = info.uid,
                isSystem = info.isSystemApp(),
                icon = icon,
                gyroBlocked = false,
                launchBlocked = false,
            )
        }

        sort(result)
        return result
    }

    /** 置顶已启用规则的应用，其余按本地化字母序（同序时回退到包名比较，保证稳定）。 */
    private fun sort(apps: MutableList<AppInfo>) {
        apps.sortWith { left, right ->
            if (left.enabled != right.enabled) {
                return@sortWith if (left.enabled) -1 else 1
            }
            val byLabel = collator.compare(left.label, right.label)
            if (byLabel != 0) byLabel else left.packageName.compareTo(right.packageName)
        }
    }

    /** 依据过滤维度与搜索关键字筛选列表。 */
    fun filter(
        apps: List<AppInfo>,
        filter: AppFilter,
        query: String,
    ): List<AppInfo> {
        val keyword = query.trim().lowercase(Locale.getDefault())
        if (keyword.isEmpty() && filter == AppFilter.ALL) return apps

        return apps.filter { app ->
            matchesFilter(app, filter) && matchesQuery(app, keyword)
        }
    }

    private fun matchesFilter(app: AppInfo, filter: AppFilter): Boolean = when (filter) {
        AppFilter.ALL -> true
        AppFilter.USER -> !app.isSystem
        AppFilter.SYSTEM -> app.isSystem
    }

    private fun matchesQuery(app: AppInfo, keyword: String): Boolean {
        if (keyword.isEmpty()) return true
        return app.label.lowercase(Locale.getDefault()).contains(keyword) ||
            app.packageName.lowercase(Locale.getDefault()).contains(keyword)
    }

    /**
     * 系统应用判定：带 `FLAG_SYSTEM`，或带 `FLAG_UPDATED_SYSTEM_APP`（被用户更新的系统应用）。
     */
    private fun ApplicationInfo.isSystemApp(): Boolean {
        val system = flags and ApplicationInfo.FLAG_SYSTEM != 0
        val updatedSystem = flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        return system || updatedSystem
    }
}
