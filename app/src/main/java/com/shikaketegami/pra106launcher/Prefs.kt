package com.shikaketegami.pra106launcher

import android.content.Context
import android.content.SharedPreferences

/**
 * 設定の保存先（SharedPreferences）。全機能でここを共有する。
 * アプリの識別キーは "パッケージ名/Activity名"（AppInfo.key）。
 */
object Prefs {
    private const val FILE = "launcher_prefs"
    private const val K_ORDER = "grid_order"       // 旧形式の並び順（キーを改行区切り、空きマスなし）。移行用に読み取りのみ残す
    private const val K_GRID_SLOTS = "grid_slots"   // 新形式：マスごとの内容（キー or 空きマス印を改行区切り）
    private const val K_DOCK = "dock_apps"          // ドック（キーを改行区切り、順序あり）
    private const val K_HIDDEN = "hidden_apps"      // 非表示（キーを改行区切り）
    private const val K_COLUMNS = "grid_columns"
    private const val K_AUTOSKIP = "autoskip_enabled"

    /** grid_slots 内で「空きマス」を表す印。パッケージキーは必ず "/" を含むため衝突しない */
    private const val EMPTY_SLOT = "__EMPTY_SLOT__"

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

    /** 旧形式の並び順（空きマスの情報は持たない）。新形式への移行時のフォールバックとしてのみ使う */
    fun getOrder(c: Context) = getList(c, K_ORDER)

    /**
     * グリッドのマスごとの内容。要素はキー、または空きマスなら null。
     * 一度も新形式で保存していない（並べ替えを一度も確定していない）場合は null を返すので、
     * 呼び出し側は getOrder() の旧データにフォールバックすること。
     */
    fun getGridSlots(c: Context): List<String?>? {
        val raw = sp(c).getString(K_GRID_SLOTS, null) ?: return null
        if (raw.isEmpty()) return emptyList()
        return raw.split("\n").map { if (it == EMPTY_SLOT) null else it }
    }

    fun setGridSlots(c: Context, slots: List<String?>) {
        val encoded = slots.joinToString("\n") { it ?: EMPTY_SLOT }
        sp(c).edit().putString(K_GRID_SLOTS, encoded).apply()
    }

    fun getDock(c: Context) = getList(c, K_DOCK)
    fun setDock(c: Context, keys: List<String>) = putList(c, K_DOCK, keys.take(DOCK_MAX))

    fun getHidden(c: Context) = getList(c, K_HIDDEN).toSet()
    fun setHidden(c: Context, keys: Set<String>) = putList(c, K_HIDDEN, keys.toList())

    fun getColumns(c: Context) = sp(c).getInt(K_COLUMNS, 5)
    fun setColumns(c: Context, n: Int) = sp(c).edit().putInt(K_COLUMNS, n).apply()

    /** ランチャー背景（動画または画像）の content:// URI。未設定なら null */
    fun getBackgroundUri(c: Context): String? = sp(c).getString("background_uri", null)
    fun getBackgroundIsVideo(c: Context): Boolean = sp(c).getBoolean("background_is_video", false)
    fun setBackground(c: Context, uri: String?, isVideo: Boolean) =
        sp(c).edit().putString("background_uri", uri).putBoolean("background_is_video", isVideo).apply()

    /**
     * 背景の読み込み中フラグ（画像用・動画用で別キー）。起動時に残っていたら前回落ちたと判断する。
     * 通常は apply()。プロセス死亡の検知に必要な「動画準備開始」だけ sync=true（commit）で書く。
     */
    fun getBackgroundLoading(c: Context, video: Boolean): Boolean =
        sp(c).getBoolean(if (video) "background_loading_video" else "background_loading_image", false)

    fun setBackgroundLoading(c: Context, video: Boolean, on: Boolean, sync: Boolean = false) {
        val e = sp(c).edit().putBoolean(if (video) "background_loading_video" else "background_loading_image", on)
        if (sync) e.commit() else e.apply()
    }

    /** 背景の連続読み込み失敗回数（成功でリセット） */
    fun getBackgroundFailCount(c: Context): Int = sp(c).getInt("background_fail_count", 0)
    fun setBackgroundFailCount(c: Context, n: Int) =
        sp(c).edit().putInt("background_fail_count", n).apply()

    /** アイコンサイズ（dp）。48 / 64 / 80 / 96 のいずれか */
    fun getIconSizeDp(c: Context) = sp(c).getInt("icon_size_dp", 64)
    fun setIconSizeDp(c: Context, dp: Int) = sp(c).edit().putInt("icon_size_dp", dp).apply()

    /** 列数を画面幅とアイコンサイズから自動で決めるか（false なら getColumns を使う） */
    fun getAutoColumns(c: Context) = sp(c).getBoolean("auto_columns", true)
    fun setAutoColumns(c: Context, on: Boolean) = sp(c).edit().putBoolean("auto_columns", on).apply()

    /** ウィジェット：appWidgetId と高さ(dp) を "id:height" の形で順に保存 */
    fun getWidgets(c: Context): List<Pair<Int, Int>> = getList(c, "widgets").mapNotNull {
        val p = it.split(":"); p.getOrNull(0)?.toIntOrNull()?.let { id -> id to (p.getOrNull(1)?.toIntOrNull() ?: 160) }
    }
    fun setWidgets(c: Context, list: List<Pair<Int, Int>>) =
        putList(c, "widgets", list.map { "${it.first}:${it.second}" })

    /** ウィジェット領域（ホーム左側）を表示するか */
    fun getWidgetPanelVisible(c: Context) = sp(c).getBoolean("widget_panel", true)
    fun setWidgetPanelVisible(c: Context, on: Boolean) = sp(c).edit().putBoolean("widget_panel", on).apply()

    fun setAutoSkip(c: Context, on: Boolean) {
        autoSkipEnabled = on
        sp(c).edit().putBoolean(K_AUTOSKIP, on).apply()
    }
}
