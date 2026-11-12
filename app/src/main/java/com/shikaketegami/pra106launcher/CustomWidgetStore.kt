package com.shikaketegami.pra106launcher

import android.content.Context

/**
 * 自作ウィジェット（時計・天気）の配置情報と、天気ウィジェット用の緯度経度設定を保存する。
 * Prefs.kt とは独立した SharedPreferences 領域を持つ（Prefs.kt には触れない）。
 */
object CustomWidgetStore {
    const val TYPE_CLOCK = 0
    const val TYPE_WEATHER = 1

    private const val PREF_NAME = "custom_widget_store"
    private const val KEY_ITEMS = "items"
    private const val KEY_NEXT_ID = "next_id"
    private const val KEY_LAT = "weather_lat"
    private const val KEY_LON = "weather_lon"

    /**
     * 自作ウィジェット1個分の情報。
     * id は負の値を使う（AppWidgetHost が割り当てる他アプリウィジェットの appWidgetId は正の値のため、衝突しない）。
     */
    data class Item(val id: Int, val type: Int, val height: Int)

    private fun sp(c: Context) = c.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getItems(c: Context): List<Item> {
        val raw = try { sp(c).getString(KEY_ITEMS, "") } catch (e: Exception) { "" } ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(",").mapNotNull { entry ->
            val p = entry.split(":")
            val id = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val type = p.getOrNull(1)?.toIntOrNull() ?: TYPE_CLOCK
            val height = p.getOrNull(2)?.toIntOrNull() ?: 160
            Item(id, type, height)
        }
    }

    fun setItems(c: Context, list: List<Item>) {
        try {
            val raw = list.joinToString(",") { "${it.id}:${it.type}:${it.height}" }
            sp(c).edit().putString(KEY_ITEMS, raw).apply()
        } catch (e: Exception) { }
    }

    /** 自作ウィジェット用の新しいIDを発行する（負の値を順に割り当てる）。 */
    fun nextId(c: Context): Int {
        return try {
            val p = sp(c)
            val n = p.getInt(KEY_NEXT_ID, 1)
            p.edit().putInt(KEY_NEXT_ID, n + 1).apply()
            -n
        } catch (e: Exception) {
            -(System.currentTimeMillis() % 100000L).toInt() - 1
        }
    }

    /** 天気ウィジェット用の緯度経度（未設定なら null）。 */
    fun getWeatherLocation(c: Context): Pair<Double, Double>? {
        return try {
            val p = sp(c)
            val lat = p.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
            val lon = p.getString(KEY_LON, null)?.toDoubleOrNull() ?: return null
            lat to lon
        } catch (e: Exception) { null }
    }

    fun setWeatherLocation(c: Context, lat: Double, lon: Double) {
        try {
            sp(c).edit().putString(KEY_LAT, lat.toString()).putString(KEY_LON, lon.toString()).apply()
        } catch (e: Exception) { }
    }

    fun clearWeatherLocation(c: Context) {
        try { sp(c).edit().remove(KEY_LAT).remove(KEY_LON).apply() } catch (e: Exception) { }
    }
}
