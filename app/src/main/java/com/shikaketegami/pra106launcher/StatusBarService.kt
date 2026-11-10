package com.shikaketegami.pra106launcher

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 画面上部に簡易ステータスバー（戻る・ホーム・時計）を重ねて表示するフォアグラウンドサービス。
 * 注意：これは「追加表示」であり、PRA106純正のSystemUIバーを消すものではない
 * （root権限がないため、純正バーの非表示化は不可）。
 */
class StatusBarService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: android.view.View? = null
    private val handler = Handler(Looper.getMainLooper())

    private val clockUpdater = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000L * 10) // 10秒ごとに更新で十分
        }
    }

    companion object {
        private const val CHANNEL_ID = "status_bar_overlay"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, StatusBarService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StatusBarService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
        showOverlay()
        handler.post(clockUpdater)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(clockUpdater)
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // 既に外れている場合は無視
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("InflateParams")
    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        if (overlayView != null) return

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_status_bar, null)

        val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP

        view.findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            BackButtonAccessibilityService.performBack()
        }
        view.findViewById<ImageView>(R.id.btnHome).setOnClickListener {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
        }

        try {
            windowManager.addView(view, params)
            overlayView = view
            updateBluetoothIcon(view)
            updateClock()
        } catch (e: Exception) {
            // オーバーレイ権限が途中で取り消された場合など
        }
    }

    private fun updateClock() {
        val view = overlayView ?: return
        val clock = view.findViewById<TextView>(R.id.clock)
        val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN)
        clock.text = sdf.format(Date())
    }

    @SuppressLint("MissingPermission")
    private fun updateBluetoothIcon(view: android.view.View) {
        try {
            val icon = view.findViewById<ImageView>(R.id.iconBluetooth)
            val adapter = BluetoothAdapter.getDefaultAdapter()
            icon.visibility = if (adapter != null && adapter.isEnabled) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        } catch (e: SecurityException) {
            // BLUETOOTH_CONNECT権限が無い端末では非表示のまま
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ステータスバー表示",
                NotificationManager.IMPORTANCE_MIN
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PRA106 Launcher")
            .setContentText("簡易ステータスバーを表示中")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }
}
