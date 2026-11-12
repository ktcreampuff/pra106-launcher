package com.shikaketegami.pra106launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 自作の「時計」ウィジェット。現在時刻（HH:mm・24時間表記）と日付・曜日を表示し、1分ごとに自動更新する。
 * ランチャー自身が描画する自作ウィジェットで、他アプリのウィジェット（AppWidgetHost経由）とは別物。
 */
class ClockWidgetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val timeView: TextView
    private val dateView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("M月d日(E)", Locale.getDefault())

    private val tick = object : Runnable {
        override fun run() {
            updateNow()
            handler.postDelayed(this, nextDelayMs())
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.widget_clock, this, true)
        timeView = findViewById(R.id.clock_widget_time)
        dateView = findViewById(R.id.clock_widget_date)
        updateNow()
    }

    private fun nextDelayMs(): Long {
        val now = System.currentTimeMillis()
        val rem = 60_000L - (now % 60_000L)
        return if (rem <= 0L) 60_000L else rem
    }

    private fun updateNow() {
        val now = Calendar.getInstance()
        timeView.text = timeFormat.format(now.time)
        dateView.text = dateFormat.format(now.time)
    }

    /** 画面に表示されている間だけ更新する。 */
    fun resume() {
        handler.removeCallbacks(tick)
        updateNow()
        handler.postDelayed(tick, nextDelayMs())
    }

    fun pause() {
        handler.removeCallbacks(tick)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resume()
    }

    override fun onDetachedFromWindow() {
        pause()
        super.onDetachedFromWindow()
    }
}
