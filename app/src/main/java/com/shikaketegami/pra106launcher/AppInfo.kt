package com.shikaketegami.pra106launcher

import android.graphics.drawable.Drawable

data class AppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable
) {
    /** 設定保存用の一意キー */
    val key: String get() = "$packageName/$activityName"
}
