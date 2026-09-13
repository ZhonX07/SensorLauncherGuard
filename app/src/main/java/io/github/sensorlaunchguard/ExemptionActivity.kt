package io.github.sensorlaunchguard

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.sensorlaunchguard.data.AppInfo
import io.github.sensorlaunchguard.data.AppRepository
import io.github.sensorlaunchguard.data.RuleSnapshot
import io.github.sensorlaunchguard.data.RuleStore
import io.github.sensorlaunchguard.databinding.ActivityExemptionBinding
import io.github.sensorlaunchguard.ui.ExemptionAdapter
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 跳转豁免管理：为某个来源应用指定「允许拉起」的目标应用。
 *
 * 语义是「豁免一条明确的 来源→目标 关系」，不是豁免整个来源应用，
 * 也不是全局放行某个支付应用——因此这里的选择只对该来源生效。
 *
 * 目标应用**不需要**加入 LSPosed 作用域：拦截发生在来源应用进程里。
 */
class ExemptionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExemptionBinding
    private lateinit var repository: AppRepository
    private lateinit var adapter: ExemptionAdapter

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "exemption-scan").apply { isDaemon = true }
    }

    private lateinit var sourcePackage: String
    private var sourceLabel: String = ""

    private var allTargets: List<AppInfo> = emptyList()
    private var currentQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExemptionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sourcePackage = intent?.getStringExtra(EXTRA_SOURCE_PACKAGE).orEmpty()
        sourceLabel = intent?.getStringExtra(EXTRA_SOURCE_LABEL).orEmpty()
        if (sourcePackage.isEmpty()) {
            finish()
            return
        }

        repository = AppRepository(this)
        adapter = ExemptionAdapter(::onToggle)
        binding.targetList.adapter = adapter

        binding.toolbar.title = getString(R.string.exemption_screen_title, sourceLabel)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.sourceTitle.text = sourceLabel
        binding.sourceDetail.text = getString(R.string.exemption_screen_hint)

        binding.searchInput.doAfterTextChanged { text ->
            currentQuery = text?.toString().orEmpty()
            applyView()
        }

        startScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        scanExecutor.shutdownNow()
    }

    // region 数据

    private fun preferences() = GuardApplication.app?.rulesPreferences()

    private fun snapshot(): RuleSnapshot =
        runCatching { RuleStore.snapshot(preferences()) }
            .onFailure { Log.w(TAG, "read rules failed", it) }
            .getOrDefault(RuleSnapshot.EMPTY)

    private fun startScan() {
        binding.loadingIndicator.visibility = View.VISIBLE
        scanExecutor.execute {
            val result = runCatching { repository.labelsAndIcons() }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                binding.loadingIndicator.visibility = View.GONE
                result
                    .onSuccess { targets ->
                        allTargets = targets
                        adapter.setPaymentPackages(targets.mapNotNull { app ->
                            app.packageName.takeIf(::isKnownPaymentApp)
                        }.toSet())
                        applyView()
                    }
                    .onFailure { error ->
                        Log.w(TAG, "load targets failed", error)
                        toast(getString(R.string.load_apps_failed, describe(error)))
                    }
            }
        }
    }

    private fun applyView() {
        val snapshot = snapshot()
        val exempted = snapshot.exemptTargets[sourcePackage].orEmpty()
        adapter.setExempted(exempted)
        adapter.enabled = preferences() != null

        val keyword = currentQuery.trim().lowercase(Locale.getDefault())
        val displayed = if (keyword.isEmpty()) {
            sortTargets(allTargets, exempted)
        } else {
            sortTargets(
                allTargets.filter {
                    it.label.lowercase(Locale.getDefault()).contains(keyword) ||
                        it.packageName.lowercase(Locale.getDefault()).contains(keyword)
                },
                exempted,
            )
        }

        adapter.submitList(displayed)
        binding.emptyView.visibility = if (displayed.isEmpty()) View.VISIBLE else View.GONE
        binding.sourceDetail.text = getString(R.string.exemption_screen_count, exempted.size)
    }

    /** 常用支付应用置顶，其次已豁免的，最后按本地化字母序。 */
    private fun sortTargets(apps: List<AppInfo>, exempted: Set<String>): List<AppInfo> =
        apps.sortedWith(
            compareByDescending<AppInfo> { isKnownPaymentApp(it.packageName) }
                .thenByDescending { it.packageName in exempted }
                .thenBy { it.label.lowercase(Locale.getDefault()) },
        )

    // endregion

    // region 交互

    private fun onToggle(app: AppInfo, exempt: Boolean) {
        val preferences = preferences()
        if (preferences == null) {
            toast(getString(R.string.service_required))
            applyView()
            return
        }

        val ok = runCatching {
            RuleStore.setExemptTarget(preferences, sourcePackage, app.packageName, exempt)
        }.onFailure { Log.w(TAG, "write exemption failed", it) }
            .getOrDefault(false)

        if (!ok) {
            toast(getString(R.string.operation_failed, getString(R.string.unknown_error)))
        } else {
            toast(
                getString(
                    if (exempt) R.string.exemption_added else R.string.exemption_removed,
                    app.label,
                ),
            )
        }
        applyView()
    }

    // endregion

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private fun android.widget.EditText.doAfterTextChanged(action: (Editable?) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) = action(s)
        })
    }

    companion object {
        private const val TAG = "SensorLaunchGuard"

        const val EXTRA_SOURCE_PACKAGE = "source_package"
        const val EXTRA_SOURCE_LABEL = "source_label"

        /**
         * 常用支付应用包名。
         *
         * 仅用于在列表里打「常用支付」标记并置顶，**不参与任何自动放行判断**——
         * 放行永远要求用户显式勾选，避免仅凭包名就把跳转放出去。
         */
        private val PAYMENT_PACKAGES = setOf(
            "com.eg.android.AlipayGphone", // 支付宝
            "com.tencent.mm", // 微信
            "com.unionpay", // 云闪付
            "com.unionpay.tsmservice", // 银联手机支付
            "com.icbc", // 工商银行
            "cmb.pb", // 招商银行
            "com.chinamworld.main", // 建设银行
            "com.android.chrome", // 浏览器中转支付
            "com.microsoft.emmx",
        )

        fun isKnownPaymentApp(packageName: String): Boolean = packageName in PAYMENT_PACKAGES
    }
}
