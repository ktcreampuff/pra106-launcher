package com.shikaketegami.pra106launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.graphics.Color
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

    val isEmpty: Boolean get() = items.isEmpty()

    fun start() {
        try { host.startListening() } catch (e: Exception) { }
        if (!restored) {
            restored = true
            restore()
        }
    }

    fun stop() {
        try { host.stopListening() } catch (e: Exception) { }
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).roundToInt()

    private fun restore() {
        items.clear()
        var broken = false
        for ((id, h) in Prefs.getWidgets(activity)) {
            val ok = try { manager.getAppWidgetInfo(id) != null } catch (e: Exception) { false }
            if (ok) items.add(id to h.coerceIn(MIN_H, MAX_H)) else {
                broken = true
                try { host.deleteAppWidgetId(id) } catch (e: Exception) { }
            }
        }
        if (broken) save()
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
                    .setPositiveButton(android.R.string.ok, null).show()
                return
            }
            AlertDialog.Builder(activity)
                .setTitle(R.string.widget_pick_title)
                .setItems(list.map { it.second }.toTypedArray()) { _, which -> begin(list[which].first) }
                .setNegativeButton(R.string.widget_cancel, null)
                .show()
        } catch (e: Exception) { }
    }

    private fun begin(provider: AppWidgetProviderInfo) {
        try {
            val id = host.allocateAppWidgetId()
            pendingId = id
            pendingProvider = provider
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
        pendingId = -1
        pendingProvider = null
        if (id < 0) return
        val minH = p?.minHeight ?: 0
        val h = (minH / activity.resources.displayMetrics.density).roundToInt().coerceIn(MIN_H, MAX_H)
        items.add(id to h)
        save()
        render()
    }

    private fun cancelPending() {
        val id = pendingId
        pendingId = -1
        pendingProvider = null
        if (id >= 0) try { host.deleteAppWidgetId(id) } catch (e: Exception) { }
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
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
        if (items.isEmpty()) {
            container.addView(TextView(activity).apply {
                setText(R.string.widget_empty_hint)
                setTextColor(Color.parseColor("#80FFFFFF"))
                textSize = 13f
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
            return
        }
        val broken = mutableListOf<Int>()
        for ((id, h) in items.toList()) {
            val card = try { buildCard(id, h) } catch (e: Exception) { null }
            if (card == null) broken.add(id) else container.addView(card)
        }
        if (broken.isNotEmpty()) {
            for (id in broken) {
                try { host.deleteAppWidgetId(id) } catch (e: Exception) { }
                views.remove(id)
            }
            items.removeAll { it.first in broken }
            save()
            if (items.isEmpty()) render()
        }
    }

    private fun buildCard(id: Int, heightDp: Int): View? {
        val info = manager.getAppWidgetInfo(id) ?: return null
        val hv = views.getOrPut(id) { host.createView(activity, id, info) }
        (hv.parent as? ViewGroup)?.removeView(hv)
        hv.setAppWidget(id, info)

        val card = LongPressFrame(activity) { showMenu(id) }.apply {
            background = activity.getDrawable(R.drawable.widget_card_bg)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)
            ).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
            addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                val wDp = ((r - l) / activity.resources.displayMetrics.density).toInt()
                val hDp = ((b - t) / activity.resources.displayMetrics.density).toInt()
                if (wDp > 0 && hDp > 0) {
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
    }

    /** 子（AppWidgetHostView）が長押しを奪うため、dispatchTouchEvent で全イベントを見て長押しを検知する。 */
    private class LongPressFrame(context: Context, private val onLong: () -> Unit) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private val fire = Runnable {
            performHapticFeedback(HapticFeedback_LONG)
            onLong()
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
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
