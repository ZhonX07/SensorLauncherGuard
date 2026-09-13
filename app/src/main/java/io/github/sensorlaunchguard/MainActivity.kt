package io.github.sensorlaunchguard

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import io.github.libxposed.service.XposedService
import io.github.sensorlaunchguard.data.AppFilter
import io.github.sensorlaunchguard.data.AppInfo
import io.github.sensorlaunchguard.data.AppRepository
import io.github.sensorlaunchguard.data.RuleKeys
import io.github.sensorlaunchguard.data.RuleSnapshot
import io.github.sensorlaunchguard.data.RuleStore
import io.github.sensorlaunchguard.databinding.ActivityMainBinding
import io.github.sensorlaunchguard.ui.AppListAdapter
import io.github.sensorlaunchguard.xposed.LaunchPrompt
import java.util.concurrent.Executors

/**
 * 主界面：展示已安装应用列表，并把「屏蔽陀螺仪 / 禁止拉起其他应用」规则写入 LSPosed 远程偏好。
 *
 * 关键行为：
 * - 开关写入成功后通过远程偏好即时生效，无需重启系统；
 * - 目标应用不在 LSPosed 作用域时先调用 [XposedService.requestScope]，批准后提示重启目标应用；
 *   请求失败则回滚开关状态；
 * - LSPosed 未连接时禁用全部开关，只保留浏览能力。
 */
class MainActivity : AppCompatActivity(), GuardApplication.ServiceListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: AppRepository
    private lateinit var adapter: AppListAdapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "app-scan").apply { isDaemon = true }
    }

    /** 全量应用（含图标），只被 UI 线程读写。 */
    private var allApps: List<AppInfo> = emptyList()

    private var currentFilter = AppFilter.ALL
    private var currentQuery: String = ""

    private var scanning = false
    private var service: XposedService? = null
    private var rulesPreferences: SharedPreferences? = null

    /** 作用域审批中的包名 → 待写入的规则值，避免审批期间偏好回调覆盖 UI。 */
    private val pendingRules = mutableMapOf<String, PendingRule>()

    private data class PendingRule(val gyro: Boolean?, val launch: Boolean?)

    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        // RemotePreferences 的回调来自 Binder 线程，所有列表与 View 更新必须切回主线程。
        mainHandler.post {
            if (!isFinishing && !isDestroyed) refreshFromPreferences()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 通知里的「禁止」按钮只是用来取消通知：打开本界面后立刻退出，不进入 UI。
        if (intent?.action == LaunchPrompt.ACTION_DENY) {
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = AppRepository(this)

        adapter = AppListAdapter(::onRuleToggled, ::openExemptions).apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
        binding.appList.adapter = adapter

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    startScan()
                    true
                }

                R.id.action_prompt_launch -> {
                    onPromptToggleRequested(item)
                    true
                }

                else -> false
            }
        }

        // 跳转询问依赖通知，Android 13+ 需要运行时授权。
        requestNotificationPermissionIfNeeded()

        binding.searchInput.doAfterTextChanged { text ->
            currentQuery = text?.toString().orEmpty()
            applyView()
        }

        binding.filterGroup.addOnButtonCheckedListener(
            MaterialButtonToggleGroup.OnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@OnButtonCheckedListener
                currentFilter = when (checkedId) {
                    R.id.filter_user -> AppFilter.USER
                    R.id.filter_system -> AppFilter.SYSTEM
                    else -> AppFilter.ALL
                }
                applyView()
            },
        )

        updateStatusCard(null)
        // 即使 LSPosed 尚未连接也允许浏览已安装应用，只禁用规则开关。
        startScan()
        pruneExpiredExemptions()
    }

    /** 顺手清理过期的临时豁免键，避免偏好里无限残留。失败只记为日志。 */
    private fun pruneExpiredExemptions() {
        val preferences = rulesPreferences ?: return
        scanExecutor.execute {
            runCatching { RuleStore.pruneExpired(preferences) }
                .onSuccess { removed ->
                    if (removed > 0) Log.i(TAG, "pruned $removed expired exemption(s)")
                }
                .onFailure { Log.w(TAG, "prune expired exemptions failed", it) }
        }
    }

    override fun onStart() {
        super.onStart()
        GuardApplication.app?.addServiceListener(this)
    }

    override fun onStop() {
        super.onStop()
        GuardApplication.app?.removeServiceListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        rulesPreferences?.unregisterOnSharedPreferenceChangeListener(preferencesListener)
        rulesPreferences = null
        scanExecutor.shutdownNow()
    }

    // region LSPosed 连接状态

    override fun onServiceChanged(service: XposedService?) {
        runOnUiThread {
            val previous = rulesPreferences
            if (previous != null) {
                runCatching { previous.unregisterOnSharedPreferenceChangeListener(preferencesListener) }
            }

            this.service = service
            val preferences = GuardApplication.app?.rulesPreferences()
            rulesPreferences = preferences
            runCatching {
                preferences?.registerOnSharedPreferenceChangeListener(preferencesListener)
            }.onFailure { Log.w(TAG, "register preferences listener failed", it) }

            updateStatusCard(service)
            adapter.rulesEnabled = service != null && preferences != null
            syncPromptMenu()

            if (allApps.isEmpty() && !scanning) {
                startScan()
            } else if (service != null) {
                refreshFromPreferences()
            } else {
                pendingRules.clear()
                adapter.clearPending()
                allApps = repository.applyRules(allApps, RuleSnapshot.EMPTY)
                applyView()
            }
        }
    }

    private fun updateStatusCard(service: XposedService?) {
        val connected = service != null
        val containerAttr = if (connected) R.attr.guardSuccessContainer else R.attr.guardWarningContainer
        val onContainerAttr =
            if (connected) R.attr.guardOnSuccessContainer else R.attr.guardOnWarningContainer

        val container = MaterialColors.getColor(binding.statusCard, containerAttr)
        val onContainer = MaterialColors.getColor(binding.statusTitle, onContainerAttr)

        binding.statusCard.setCardBackgroundColor(container)
        binding.statusIcon.setImageResource(
            if (connected) R.drawable.ic_link else R.drawable.ic_link_off,
        )
        binding.statusIcon.imageTintList = ColorStateList.valueOf(onContainer)
        binding.statusTitle.setTextColor(onContainer)
        binding.statusDetail.setTextColor(onContainer)

        binding.statusTitle.setText(
            if (connected) R.string.status_connected else R.string.status_disconnected,
        )
        binding.statusDetail.text = if (connected) {
            getString(R.string.status_connected_detail, service?.frameworkName.orEmpty())
        } else {
            getString(R.string.status_disconnected_detail)
        }
    }

    // endregion

    // region 扫描与展示

    private fun startScan() {
        if (scanning) return
        scanning = true
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.appCount.setText(R.string.loading_apps)

        val snapshot = readSnapshot()
        scanExecutor.execute {
            val result = runCatching { repository.load(snapshot) }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                scanning = false
                binding.loadingIndicator.visibility = View.GONE
                result
                    .onSuccess { apps ->
                        // 扫描期间服务可能刚好完成绑定，应用最新远程规则，避免展示陈旧开关。
                        allApps = repository.applyRules(apps, readSnapshot())
                        applyView()
                    }
                    .onFailure { error ->
                        Log.w(TAG, "load installed applications failed", error)
                        toast(getString(R.string.load_apps_failed, describe(error)))
                        applyView()
                    }
            }
        }
    }

    private fun refreshFromPreferences() {
        if (pendingRules.isNotEmpty()) return
        val snapshot = readSnapshot()
        allApps = repository.applyRules(allApps, snapshot)
        applyView()
        syncPromptMenu()
    }

    private fun readSnapshot(): RuleSnapshot =
        runCatching { RuleStore.snapshot(rulesPreferences) }
            .onFailure { Log.w(TAG, "read rules failed", it) }
            .getOrDefault(RuleSnapshot.EMPTY)

    private fun applyView() {
        val displayed = repository.filter(allApps, currentFilter, currentQuery)
        adapter.rulesEnabled = service != null && rulesPreferences != null
        adapter.setExemptionCounts(exemptionCounts())
        adapter.submitList(displayed)
        binding.appCount.text = getString(R.string.app_count, displayed.size)
        binding.emptyView.visibility = if (displayed.isEmpty() && !scanning) View.VISIBLE else View.GONE
    }

    /** 来源包 → 豁免目标数量（含未过期的临时豁免）。 */
    private fun exemptionCounts(): Map<String, Int> = readSnapshot().let { snapshot ->
        buildMap {
            for ((source, targets) in snapshot.exemptTargets) {
                if (targets.isNotEmpty()) put(source, targets.size)
            }
            for ((source, temporary) in snapshot.exemptUntil) {
                val alive = temporary.count { it.value > System.currentTimeMillis() }
                if (alive > 0) put(source, (get(source) ?: 0) + alive)
            }
        }
    }

    private fun openExemptions(app: AppInfo) {
        if (rulesPreferences == null) {
            toast(getString(R.string.service_required))
            return
        }
        startActivity(
            Intent(this, ExemptionActivity::class.java)
                .putExtra(ExemptionActivity.EXTRA_SOURCE_PACKAGE, app.packageName)
                .putExtra(ExemptionActivity.EXTRA_SOURCE_LABEL, app.label),
        )
    }

    // endregion

    // region 跳转询问开关（全局）

    private fun syncPromptMenu() {
        binding.toolbar.menu.findItem(R.id.action_prompt_launch)?.isChecked =
            readSnapshot().promptLaunch
    }

    private fun onPromptToggleRequested(item: MenuItem) {
        val preferences = rulesPreferences
        if (preferences == null) {
            // 未连接 LSPosed：把菜单状态还原成真实状态，并说明原因。
            item.isChecked = readSnapshot().promptLaunch
            toast(getString(R.string.service_required))
            return
        }

        val target = !item.isChecked
        val ok = runCatching { RuleStore.setPromptLaunch(preferences, target) }
            .onFailure { Log.w(TAG, "write prompt flag failed", it) }
            .getOrDefault(false)

        if (!ok) {
            item.isChecked = readSnapshot().promptLaunch
            toast(getString(R.string.operation_failed, getString(R.string.unknown_error)))
            return
        }

        item.isChecked = target
        toast(getString(if (target) R.string.prompt_enabled else R.string.prompt_disabled))
        if (target) toast(getString(R.string.prompt_needs_permission))
        refreshFromPreferences()
    }

    /**
     * 请求**我们自己的**通知权限。
     *
     * 注意：这只影响模块自身能否发通知；跳转询问的通知由被限制的宿主应用发出，
     * 是否显示取决于该应用自己的通知权限，我们无法代为授予。
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return

        runCatching {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS,
            )
        }.onFailure { Log.w(TAG, "request notification permission failed", it) }
    }

    // endregion

    // region 规则写入

    private fun onRuleToggled(app: AppInfo, gyro: Boolean?, launch: Boolean?) {
        val requested = app.copy(
            gyroBlocked = gyro ?: app.gyroBlocked,
            launchBlocked = launch ?: app.launchBlocked,
        )

        val service = this.service
        val preferences = this.rulesPreferences
        if (service == null || preferences == null) {
            toast(getString(R.string.service_required))
            revertRule(requested)
            return
        }

        val inScope = runCatching { service.scope.orEmpty().contains(app.packageName) }
            .onFailure { Log.w(TAG, "read scope failed", it) }
            .getOrDefault(false)

        if (inScope) {
            writeRule(requested, gyro, launch)
            return
        }

        // 关闭最后一条规则不需要先把应用加入作用域。
        if (!requested.enabled) {
            writeRule(requested, gyro, launch)
            return
        }

        Log.i(TAG, "request scope for ${app.packageName}")
        pendingRules[app.packageName] = PendingRule(gyro, launch)
        adapter.setPending(app.packageName, true)

        val listener = object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(approved: List<String>) {
                mainHandler.post {
                    val pending = pendingRules.remove(app.packageName)
                    adapter.setPending(app.packageName, false)

                    if (pending == null) {
                        abortPending(app)
                        return@post
                    }
                    if (app.packageName !in approved) {
                        abortPending(app)
                        toast(getString(R.string.scope_not_approved))
                        return@post
                    }

                    writeRule(requested, pending.gyro, pending.launch)
                    toast(getString(R.string.scope_approved_restart))
                }
            }

            override fun onScopeRequestFailed(message: String) {
                mainHandler.post {
                    abortPending(app)
                    toast(getString(R.string.operation_failed, message.ifBlank { getString(R.string.unknown_error) }))
                }
            }
        }

        runCatching { service.requestScope(listOf(app.packageName), listener) }
            .onFailure { error ->
                Log.w(TAG, "requestScope failed", error)
                mainHandler.post {
                    abortPending(app)
                    toast(getString(R.string.operation_failed, describe(error)))
                }
            }
    }

    /** 作用域申请被拒绝 / 失败时，撤销行内状态并回滚开关。 */
    private fun abortPending(app: AppInfo) {
        pendingRules.remove(app.packageName)
        adapter.setPending(app.packageName, false)
        revertRule(app)
    }

    private fun writeRule(app: AppInfo, gyro: Boolean?, launch: Boolean?) {
        val preferences = rulesPreferences
        if (preferences == null) {
            toast(getString(R.string.service_required))
            revertRule(app)
            return
        }

        val ok = runCatching { RuleStore.setRule(preferences, app.packageName, gyro, launch) }
            .onFailure { Log.w(TAG, "write rule failed", it) }
            .getOrDefault(false)

        if (ok) {
            allApps = repository.applyRules(allApps, readSnapshot())
            applyView()
            if (!app.enabled) {
                runCatching {
                    service?.takeIf { app.packageName in it.scope.orEmpty() }
                        ?.removeScope(listOf(app.packageName))
                }.onFailure { Log.w(TAG, "removeScope failed", it) }
            }
            toast(getString(R.string.rule_saved))
        } else {
            toast(getString(R.string.operation_failed, getString(R.string.unknown_error)))
            revertRule(app)
        }
    }

    /** 把某一行恢复到控制器中的真实状态（失败回滚 / 未连接时撤销 UI 上的乐观改动）。 */
    private fun revertRule(app: AppInfo) {
        val actual = allApps.firstOrNull { it.packageName == app.packageName } ?: return
        val position = adapter.currentList.indexOfFirst { it.packageName == app.packageName }
        if (position < 0) return

        val restored = adapter.currentList.toMutableList().apply { this[position] = actual }
        adapter.submitList(restored)
    }

    // endregion

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private fun EditText.doAfterTextChanged(action: (Editable?) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) = action(s)
        })
    }

    private companion object {
        const val TAG = "SensorLaunchGuard"
        const val REQUEST_NOTIFICATIONS = 1001
    }
}
