package io.github.sensorlaunchguard.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.sensorlaunchguard.R
import io.github.sensorlaunchguard.data.AppInfo
import io.github.sensorlaunchguard.databinding.ItemAppBinding

/**
 * 应用列表适配器。
 *
 * 复用 [ItemAppBinding]：绑定前必须清空两个 [com.google.android.material.materialswitch.MaterialSwitch]
 * 的监听器，否则 RecyclerView 回收后会触发上一次绑定的应用回调。
 */
class AppListAdapter(
    private val onRuleChanged: (AppInfo, gyro: Boolean?, launch: Boolean?) -> Unit,
    private val onExemptionClicked: (AppInfo) -> Unit,
) : ListAdapter<AppInfo, AppListAdapter.AppViewHolder>(DIFF) {

    /** 正在等待 LSPosed 作用域审批的包名，用于展示行内进度指示。 */
    private val pendingPackages = mutableSetOf<String>()

    /** LSPosed 未连接时禁用所有开关。 */
    var rulesEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_INTERACTIVE)
        }

    /** 来源包 → 已配置的豁免目标数量。 */
    private var exemptionCounts: Map<String, Int> = emptyMap()

    fun setExemptionCounts(counts: Map<String, Int>) {
        if (exemptionCounts == counts) return
        exemptionCounts = counts
        notifyItemRangeChanged(0, itemCount, PAYLOAD_INTERACTIVE)
    }

    fun setPending(packageName: String, pending: Boolean) {
        val changed = if (pending) pendingPackages.add(packageName) else pendingPackages.remove(packageName)
        if (!changed) return

        val position = currentList.indexOfFirst { it.packageName == packageName }
        if (position >= 0) notifyItemChanged(position, PAYLOAD_INTERACTIVE)
    }

    fun isPending(packageName: String): Boolean = packageName in pendingPackages

    fun clearPending() {
        if (pendingPackages.isEmpty()) return
        pendingPackages.clear()
        notifyItemRangeChanged(0, itemCount, PAYLOAD_INTERACTIVE)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            holder.bindInteractive(getItem(position))
        }
    }

    inner class AppViewHolder(private val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(app: AppInfo) {
            val context = binding.root.context

            binding.appIcon.setImageDrawable(app.icon)
            binding.appIcon.contentDescription =
                context.getString(R.string.content_app_icon, app.label)
            binding.appLabel.text = app.label
            binding.packageName.text = app.packageName
            binding.appType.setText(if (app.isSystem) R.string.system_app else R.string.user_app)

            // 必须先清空监听器：否则复用时会以新应用的包名触发旧回调。
            binding.gyroSwitch.setOnCheckedChangeListener(null)
            binding.launchSwitch.setOnCheckedChangeListener(null)
            binding.gyroSwitch.isChecked = app.gyroBlocked
            binding.launchSwitch.isChecked = app.launchBlocked

            binding.gyroSwitch.setOnCheckedChangeListener { _, isChecked ->
                onRuleChanged(app, isChecked, null)
            }
            binding.launchSwitch.setOnCheckedChangeListener { _, isChecked ->
                onRuleChanged(app, null, isChecked)
            }

            bindInteractive(app)
        }

        /** 只刷新与交互状态相关的部分（开关可用性 / 审批指示 / 豁免入口）。 */
        fun bindInteractive(app: AppInfo) {
            val pending = isPending(app.packageName)
            val allowed = rulesEnabled && !pending

            binding.gyroSwitch.isEnabled = allowed
            binding.launchSwitch.isEnabled = allowed
            binding.pendingIndicator.visibility = if (pending) View.VISIBLE else View.GONE
            binding.gyroSwitch.isChecked = app.gyroBlocked
            binding.launchSwitch.isChecked = app.launchBlocked

            bindExemptionRow(app)
        }

        /**
         * 豁免入口的显示规则：
         * - 「禁止拉起其他应用」开启 → 显示可点击入口（数量或「未配置」）；
         * - 规则关闭但已配置豁免 → 显示「已保存但未启用」，提示配置被保留为休眠状态；
         * - 两者都没有 → 隐藏整行。
         */
        private fun bindExemptionRow(app: AppInfo) {
            val context = binding.root.context
            val count = exemptionCounts[app.packageName] ?: 0

            val summary = when {
                app.launchBlocked && count > 0 ->
                    context.getString(R.string.exemption_summary, count)

                app.launchBlocked -> context.getString(R.string.exemption_none)

                count > 0 -> context.getString(R.string.exemption_dormant, count)

                else -> null
            }

            if (summary == null) {
                binding.exemptionRow.visibility = View.GONE
                binding.exemptionRow.setOnClickListener(null)
                return
            }

            binding.exemptionRow.visibility = View.VISIBLE
            binding.exemptionSummary.text = summary
            binding.exemptionRow.setOnClickListener { onExemptionClicked(app) }
        }
    }

    companion object {
        private const val PAYLOAD_INTERACTIVE = "interactive"

        private val DIFF = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
                oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
                oldItem == newItem
        }
    }
}
