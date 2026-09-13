package io.github.sensorlaunchguard.data

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val label: String,
    val uid: Int,
    val isSystem: Boolean,
    val icon: Drawable?,
    val gyroBlocked: Boolean,
    val launchBlocked: Boolean,
    /** 该应用是否被当前来源豁免为可拉起目标（仅豁免列表使用）。 */
    val launchExempted: Boolean = false,
) {
    val enabled: Boolean
        get() = gyroBlocked || launchBlocked
}
