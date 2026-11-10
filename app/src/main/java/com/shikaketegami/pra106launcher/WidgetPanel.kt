package com.shikaketegami.pra106launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlin.math.abs
import kotlin.math.roundToInt

/** ホーム画面のウィジェット領域。container（縦LinearLayout）にウィジェットのカードを並べる。 */
class WidgetPanel(private val activity: Activity, private val container: LinearLayout) {
    companion object {
        const val REQ_PICK = 3001
        const val REQ_BIND = 3002
        const val REQ_CONFIGURE = 3003
        const val HOST_ID = 1024
        private const val MIN_H = 120
        private const val MAX_H = 400
    }

    private val host = AppWidgetHost(activity, HOST_ID)
    private val manager = AppWidgetManager.getInstance(activity)
    private val items = mutableListOf<Pair<Int, Int>>() // appWidgetId と高さdp
    private val views = HashMap<Int, AppWidgetHostView>()
    private var restored = false

    // 追加処理の途中状態（バインド／設定の結果待ち）
    private var pendingId = -1
    private var pendingProvider: AppWidgetProviderInfo? = null

    /** 追加・削除のたびに呼ばれる（使わなくてもよい）。 */
    var onChanged: (() -> Unit)? = null

    /** 追加操作が（選択ダイアログのキャンセル、バインド／設定のキャンセルや失敗で）完了せず終わったときに呼ばれる。 */
    var onCancelled: (() -> Unit)? = null

    private var hasPlaceholder = false
    private val placeholderIds = mutableSetOf<Int>()

    private val pendingPrefs by lazy { activity.getSharedPreferences("widget_pending", Context.MODE_PRIVATE) }

    private fun savePending() {
        try {
            pendingPrefs.edit()
                .putInt("id", pendingId)
                .putString("provider", pendingProvider?.provider?.flattenToString())
                .apply()
        } catch (e: Exception) { }
    }

    private fun clearPending() {
        pendingId = -1
        pendingProvider = null
        try { pendingPrefs.edit().clear().apply() } catch (e: Exception) { }
    }

    /** Activity 再生成で失われた追加途中の状態を復元する。 */
    private fun restorePending() {
        if (pendingId >= 0) return
        try {
            val id = pendingPrefs.getInt("id", -1)
            if (id < 0) return
            pendingId = id
            val cn = pendingPrefs.getString("provider", null)?.let { ComponentName.unflattenFromString(it) }
            if (cn != null) pendingProvider = manager.installedProviders.firstOrNull { it.provider == cn }
        } catch (e: Exception) { }
    }

    val isEmpty: Boolean get() = items.isEmpty()

    fun start() {
        try { host.startListening() } catch (e: Exception) { }
        if (!restored) {
            restored = true
            restore()
        } else if (hasPlaceholder) {
            // 起動直後に読めなかったウィジェットが読めるようになったときだけ描き直す
            val recovered = placeholderIds.any {
                try { manager.getAppWidgetInfo(it) != null } catch (e: Exception) { false }
            }
            if (recovered) render()
        }
    }

    fun stop() {
        try { host.stopListening() } catch (e: Exception) { }
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).roundToInt()

    private fun restore() {
        items.clear()
        // 一時的に読み込めないだけの場合があるので、ここでは削除しない（削除は長押しメニューから）
        for ((id, h) in Prefs.getWidgets(activity)) items.add(id to h.coerceIn(MIN_H, MAX_H))
        render()
    }

    private fun save() {
        try { Prefs.setWidgets(activity, items.toList()) } catch (e: Exception) { }
    }

    // ---- 追加 ----

    fun addWidget() {
        try {
            val pm = activity.packageManager
            val list = manager.installedProviders.map { p ->
                val app = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(p.provider.packageName, 0)).toString()
                } catch (e: Exception) { p.provider.packageName }
                val name = try { p.loadLabel(pm) ?: "" } catch (e: Exception) { "" }
                Triple(p, "$app / $name", app)
            }.sortedBy { it.second.lowercase() }
            if (list.isEmpty()) {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.widget_pick_title)
                    .setMessage(R.string.widget_pick_none)
                    .setPositiveButton(android.R.string.ok, null)
                    .setOnCancelListener { onCancelled?.invoke() }
                    .setOnDismissListener { onCancelled?.invoke() }
                    .show()
                return
            }
            AlertDialog.Builder(activity)
                .setTitle(R.string.widget_pick_title)
                .setItems(list.map { it.second }.toTypedArray()) { _, which -> begin(list[which].first) }
                .setNegativeButton(R.string.widget_cancel) { _, _ -> onCancelled?.invoke() }
                .setOnCancelListener { onCancelled?.invoke() }
                .show()
        } catch (e: Exception) { onCancelled?.invoke() }
    }

    private fun begin(provider: AppWidgetProviderInfo) {
        try {
            val id = host.allocateAppWidgetId()
            pendingId = id
            pendingProvider = provider
            savePending()
            if (manager.bindAppWidgetIdIfAllowed(id, provider.provider)) {
                afterBind()
            } else {
                val i = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
                activity.startActivityForResult(i, REQ_BIND)
            }
        } catch (e: Exception) { cancelPending() }
    }

    private fun afterBind() {
        val id = pendingId
        try {
            val info = manager.getAppWidgetInfo(id) ?: pendingProvider
            if (info?.configure != null) {
                host.startAppWidgetConfigureActivityForResult(activity, id, 0, REQ_CONFIGURE, null)
            } else {
                finishAdd()
            }
        } catch (e: Exception) {
            // 設定画面を開けなくてもウィジェット自体は置けることが多いのでそのまま追加する
            try { finishAdd() } catch (e2: Exception) { cancelPending() }
        }
    }

    private fun finishAdd() {
        val id = pendingId
        val p = pendingProvider
        clearPending()
        if (id < 0) {
            onCancelled?.invoke()
            return
        }
        if (items.any { it.first == id }) {
            // 重複IDは捨てる
            try { host.deleteAppWidgetId(id) } catch (e: Exception) { }
            onCancelled?.invoke()
            return
        }
        val minH = p?.minHeight ?: 0
        val h = (minH / activity.resources.displayMetrics.density).roundToInt().coerceIn(MIN_H, MAX_H)
        items.add(id to h)
        save()
        render()
        onChanged?.invoke()
    }

    private fun cancelPending() {
        val id = pendingId
        clearPending()
        if (id >= 0) try { host.deleteAppWidgetId(id) } catch (e: Exception) { }
        onCancelled?.invoke()
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == REQ_BIND || requestCode == REQ_CONFIGURE) {
            restorePending()
            if (pendingId < 0) {
                // 状態が失われていても、結果にウィジェットIDがあればそれで続行する
                val rid = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
                if (rid >= 0) {
                    pendingId = rid
                    savePending()
                }
            }
        }
        return when (requestCode) {
            REQ_BIND -> {
                if (resultCode == Activity.RESULT_OK) afterBind() else cancelPending()
                true
            }
            REQ_CONFIGURE -> {
                if (resultCode == Activity.RESULT_OK) {
                    try { finishAdd() } catch (e: Exception) { cancelPending() }
                } else cancelPending()
                true
            }
            REQ_PICK -> true // 旧来の選択画面は使わないが、来た場合は無視する
            else -> false
        }
    }

    // ---- 表示 ----

    private fun render() {
        container.removeAllViews()
        hasPlaceholder = false
        placeholderIds.clear()
        if (items.isEmpty()) {
            container.addView(TextView(activity).apply {
                setText(R.string.widget_empty_hint)
                setTextColor(Color.parseColor("#80FFFFFF"))
                textSize = 13f
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
            return
        }
        for ((id, h) in items.toList()) {
            val card = try { buildCard(id, h) } catch (e: Exception) { null } ?: run { hasPlaceholder = true; placeholderIds.add(id); buildPlaceholder(id, h) }
            container.addView(card)
        }
    }

    private fun cardParams(heightDp: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)
    ).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }

    /** 読み込めないウィジェットの代わりに出すカード。保存データは消さず、長押しメニューで削除できる。 */
    private fun buildPlaceholder(id: Int, heightDp: Int): View {
        val card = LongPressFrame(activity) { showMenu(id) }.apply {
            background = activity.getDrawable(R.drawable.widget_card_bg)
            layoutParams = cardParams(heightDp)
        }
        card.addView(TextView(activity).apply {
            setText(R.string.widget_unavailable)
            setTextColor(Color.parseColor("#80FFFFFF"))
            textSize = 13f
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return card
    }

    private fun buildCard(id: Int, heightDp: Int): View? {
        val info = manager.getAppWidgetInfo(id) ?: run { views.remove(id); return null }
        val hv = views.getOrPut(id) { host.createView(activity, id, info) }
        (hv.parent as? ViewGroup)?.removeView(hv)
        hv.setAppWidget(id, info)

        val card = LongPressFrame(activity) { showMenu(id) }.apply {
            background = activity.getDrawable(R.drawable.widget_card_bg)
            clipToOutline = true
            layoutParams = cardParams(heightDp)
            var lastW = -1
            var lastH = -1
            addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                val wDp = ((r - l) / activity.resources.displayMetrics.density).toInt()
                val hDp = ((b - t) / activity.resources.displayMetrics.density).toInt()
                if (wDp > 0 && hDp > 0 && (wDp != lastW || hDp != lastH)) {
                    lastW = wDp
                    lastH = hDp
                    try { hv.updateAppWidgetSize(null, wDp, hDp, wDp, hDp) } catch (e: Exception) { }
                }
            }
        }
        card.addView(hv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return card
    }

    // ---- 長押しメニュー ----

    private fun showMenu(id: Int) {
        val labels = arrayOf(
            activity.getString(R.string.widget_menu_height_small),
            activity.getString(R.string.widget_menu_height_medium),
            activity.getString(R.string.widget_menu_height_large),
            activity.getString(R.string.widget_menu_move_up),
            activity.getString(R.string.widget_menu_move_down),
            activity.getString(R.string.widget_menu_delete)
        )
        try {
            AlertDialog.Builder(activity)
                .setItems(labels) { _, which ->
                    when (which) {
                        0 -> setHeight(id, 120)
                        1 -> setHeight(id, 200)
                        2 -> setHeight(id, 300)
                        3 -> move(id, -1)
                        4 -> move(id, 1)
                        5 -> remove(id)
                    }
                }
                .setNegativeButton(R.string.widget_cancel, null)
                .show()
        } catch (e: Exception) { }
    }

    private fun setHeight(id: Int, h: Int) {
        val i = items.indexOfFirst { it.first == id }
        if (i < 0) return
        items[i] = id to h
        save()
        render()
    }

    private fun move(id: Int, delta: Int) {
        val i = items.indexOfFirst { it.first == id }
        val j = i + delta
        if (i < 0 || j < 0 || j >= items.size) return
        val t = items[i]; items[i] = items[j]; items[j] = t
        save()
        render()
    }

    private fun remove(id: Int) {
        try { host.deleteAppWidgetId(id) } catch (e: Exception) { }
        views.remove(id)
        items.removeAll { it.first == id }
        save()
        render()
        onChanged?.invoke()
    }

    /** 子（AppWidgetHostView）が長押しを奪うため、dispatchTouchEvent で全イベントを見て長押しを検知する。 */
    private class LongPressFrame(context: Context, private val onLong: () -> Unit) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var consumed = false
        private val fire = Runnable {
            performHapticFeedback(HapticFeedback_LONG)
            // 子のタップが発火しないよう、先にキャンセルを送る
            consumed = true
            cancelChildren()
            onLong()
        }

        private fun cancelChildren() {
            val now = SystemClock.uptimeMillis()
            val c = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0)
            try { super.dispatchTouchEvent(c) } catch (e: Exception) { }
            c.recycle()
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) consumed = false
            if (consumed) {
                // 長押し発火後は、指が離れるまで子にイベントを渡さない
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    consumed = false
                }
                return true
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y
                    removeCallbacks(fire)
                    postDelayed(fire, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE ->
                    if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(fire)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(fire)
            }
            return super.dispatchTouchEvent(ev)
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(fire)
            super.onDetachedFromWindow()
        }

        private companion object {
            const val HapticFeedback_LONG = android.view.HapticFeedbackConstants.LONG_PRESS
        }
    }
}
