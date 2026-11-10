package com.shikaketegami.pra106launcher

import android.content.Context
import android.content.SharedPreferences

/**
 * 設定の保存先（SharedPreferences）。全機能でここを共有する。
 * アプリの識別キーは "パッケージ名/Activity名"（AppInfo.key）。
 */
object Prefs {
    private const val FILE = "launcher_prefs"
    private const val K_ORDER = "grid_order"       // 並び順（キーを改行区切り）
    private const val K_DOCK = "dock_apps"          // ドック（キーを改行区切り、順序あり）
    private const val K_HIDDEN = "hidden_apps"      // 非表示（キーを改行区切り）
    private const val K_COLUMNS = "grid_columns"
    private const val K_AUTOSKIP = "autoskip_enabled"

    const val DOCK_MAX = 8

    /** AutoSkipのON/OFF。サービスから毎イベント参照するのでメモリに保持 */
    @Volatile var autoSkipEnabled: Boolean = true
        private set

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun getList(c: Context, k: String): List<String> =
        sp(c).getString(k, "")!!.split("\n").filter { it.isNotBlank() }

    private fun putList(c: Context, k: String, v: List<String>) =
        sp(c).edit().putString(k, v.joinToString("\n")).apply()

    fun load(c: Context) {
        autoSkipEnabled = sp(c).getBoolean(K_AUTOSKIP, true)
    }

    fun getOrder(c: Context) = getList(c, K_ORDER)
    fun setOrder(c: Context, keys: List<String>) = putList(c, K_ORDER, keys)

    fun getDock(c: Context) = getList(c, K_DOCK)
    fun setDock(c: Context, keys: List<String>) = putList(c, K_DOCK, keys.take(DOCK_MAX))

    fun getHidden(c: Context) = getList(c, K_HIDDEN).toSet()
    fun setHidden(c: Context, keys: Set<String>) = putList(c, K_HIDDEN, keys.toList())

    fun getColumns(c: Context) = sp(c).getInt(K_COLUMNS, 5)
    fun setColumns(c: Context, n: Int) = sp(c).edit().putInt(K_COLUMNS, n).apply()

    fun setAutoSkip(c: Context, on: Boolean) {
        autoSkipEnabled = on
        sp(c).edit().putBoolean(K_AUTOSKIP, on).apply()
    }
}
