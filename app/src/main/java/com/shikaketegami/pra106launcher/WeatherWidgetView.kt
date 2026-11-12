package com.shikaketegami.pra106launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 自作の「天気」ウィジェット。Open-Meteo（APIキー不要）から現在の天気を取得して表示する。
 * 位置情報の権限は使わず、緯度経度は長押しメニューから手動設定する（CustomWidgetStore に保存）。
 * 通信・解析に失敗してもクラッシュせず、ウィジェット内にエラー表示を出す。
 */
class WeatherWidgetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    companion object {
        private const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L // 30分
        private val executor = Executors.newSingleThreadExecutor()
    }

    private val iconView: ImageView
    private val tempView: TextView
    private val messageView: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.widget_weather, this, true)
        iconView = findViewById(R.id.weather_widget_icon)
        tempView = findViewById(R.id.weather_widget_temp)
        messageView = findViewById(R.id.weather_widget_message)
    }

    /** 画面に表示されている間だけ更新する。位置が変更されたあとに呼び直すと即時に取得し直す。 */
    fun resume() {
        handler.removeCallbacks(tick)
        refresh()
        handler.postDelayed(tick, REFRESH_INTERVAL_MS)
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

    private fun refresh() {
        val loc = try { CustomWidgetStore.getWeatherLocation(context) } catch (e: Exception) { null }
        if (loc == null) {
            iconView.setImageResource(R.drawable.ic_weather_location)
            tempView.text = ""
            messageView.setText(R.string.widget_weather_need_location)
            return
        }
        val (lat, lon) = loc
        executor.execute {
            val result = try {
                fetchWeather(lat, lon)
            } catch (e: Exception) {
                null
            }
            handler.post {
                applyResult(result)
            }
        }
    }

    private fun applyResult(result: WeatherResult?) {
        if (!isAttachedToWindow) return
        if (result == null) {
            iconView.setImageResource(R.drawable.ic_weather_error)
            tempView.text = ""
            messageView.setText(R.string.widget_weather_error)
        } else {
            iconView.setImageResource(result.iconRes)
            tempView.text = context.getString(R.string.widget_weather_temp_format, result.temp)
            messageView.text = result.label
        }
    }

    private data class WeatherResult(val temp: Int, val iconRes: Int, val label: String)

    private fun fetchWeather(lat: Double, lon: Double): WeatherResult {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.requestMethod = "GET"
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("http $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val current = json.getJSONObject("current_weather")
            val temp = current.getDouble("temperature")
            val weatherCode = current.getInt("weathercode")
            val (icon, label) = describe(weatherCode)
            return WeatherResult(Math.round(temp).toInt(), icon, label)
        } finally {
            try { conn.disconnect() } catch (e: Exception) { }
        }
    }

    // 絵文字は車載機のシステムフォントがColor Emoji非対応だと文字化け（豆腐表示）する恐れがあるため、
    // ベクターアイコン（R.drawable.ic_weather_*）に変更（ux-critic指摘）
    private fun describe(code: Int): Pair<Int, String> = when (code) {
        0 -> R.drawable.ic_weather_clear to context.getString(R.string.widget_weather_clear)
        1, 2, 3 -> R.drawable.ic_weather_cloudy to context.getString(R.string.widget_weather_cloudy)
        45, 48 -> R.drawable.ic_weather_fog to context.getString(R.string.widget_weather_fog)
        51, 53, 55, 56, 57 -> R.drawable.ic_weather_drizzle to context.getString(R.string.widget_weather_drizzle)
        61, 63, 65, 66, 67, 80, 81, 82 -> R.drawable.ic_weather_rain to context.getString(R.string.widget_weather_rain)
        71, 73, 75, 77, 85, 86 -> R.drawable.ic_weather_snow to context.getString(R.string.widget_weather_snow)
        95, 96, 99 -> R.drawable.ic_weather_thunder to context.getString(R.string.widget_weather_thunder)
        else -> R.drawable.ic_weather_unknown to context.getString(R.string.widget_weather_unknown)
    }
}
