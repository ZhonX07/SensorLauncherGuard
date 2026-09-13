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
 * 跳转豁免目标选择列表。
 *
 * 复用 `item_app.xml`：只保留「允许拉起」开关作为选择控件，
 * 隐藏陀螺仪开关、豁免入口与分隔线，把类型徽标改作「常用支付」标记。
 */
class ExemptionAdapter(
    private val onToggle: (AppInfo, Boolean) -> Unit,
) : ListAdapter<AppInfo, ExemptionAdapter.TargetViewHolder>(DIFF) {

    /** 常用支付应用包名，用于打标记与置顶提示。 */
    private var paymentPackages: Set<String> = emptySet()

    /** 当前来源已豁免的目标包名集合。 */
    private var exempted: Set<String> = emptySet()

    var enabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_INTERACTIVE)
        }

    fun setPaymentPackages(packages: Set<String>) {
        if (paymentPackages == packages) return
        paymentPackages = packages
        notifyItemRangeChanged(0, itemCount, PAYLOAD_INTERACTIVE)
    }

    fun setExempted(packages: Set<String>) {
        if (exempted == packages) return
        exempted = packages
        notifyItemRangeChanged(0, itemCount, PAYLOAD_INTERACTIVE)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TargetViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TargetViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TargetViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: TargetViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) super.onBindViewHolder(holder, position, payloads) else holder.bindSwitch(getItem(position))
    }

    inner class TargetViewHolder(private val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(app: AppInfo) {
            val context = binding.root.context

            // 豁免列表里用不到陀螺仪与豁免入口。
            binding.gyroSwitch.visibility = View.GONE
            binding.exemptionRow.visibility = View.GONE
            binding.divider.visibility = View.GONE

            binding.appIcon.setImageDrawable(app.icon)
            binding.appIcon.contentDescription =
                context.getString(R.string.content_app_icon, app.label)
            binding.appLabel.text = app.label
            binding.packageName.text = app.packageName

            val isPayment = app.packageName in paymentPackages
            binding.appType.visibility = if (isPayment) View.VISIBLE else View.GONE
            binding.appType.setText(R.string.common_payment_app)

            // 复用 launch_switch 作为「允许拉起」选择控件；勾选状态由 exempted 集合决定，
            // 显示状态需要调用方在换源时用 setExempted 刷新。
            binding.launchSwitch.setText(R.string.exemption_allow_switch)
            binding.launchSwitch.setOnCheckedChangeListener(null)
            binding.launchSwitch.isChecked = app.packageName in exempted
            binding.launchSwitch.setOnCheckedChangeListener { _, checked ->
                onToggle(app, checked)
            }

            bindSwitch(app)
        }

        fun bindSwitch(app: AppInfo) {
            binding.launchSwitch.isEnabled = enabled
            binding.launchSwitch.isChecked = app.packageName in exempted
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
