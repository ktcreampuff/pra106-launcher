package com.shikaketegami.pra106launcher

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.widget.Toast
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
 * 画面上部に簡易ステータスバー（時計・Wi-Fi再検索・Bluetooth・AutoSkip切替・直前アプリ・ホーム・戻る。操作ボタンは右ハンドル車に合わせて右寄せ）を重ねて表示するフォアグラウンドサービス。
 * 注意：これは「追加表示」であり、PRA106純正のSystemUIバーを消すものではない
 * （root権限がないため、純正バーの非表示化は不可）。
 */
class StatusBarService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: android.view.View? = null
    private val handler = Handler(Looper.getMainLooper())

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            overlayView?.let { updateBluetoothIcon(it) }
        }
    }
    private var btReceiverRegistered = false

    private var netCallback: ConnectivityManager.NetworkCallback? = null

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
        Prefs.load(this)
        showOverlay()
        registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        btReceiverRegistered = true
        registerNetworkCallback()
        handler.post(clockUpdater)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(clockUpdater)
        if (btReceiverRegistered) {
            try {
                unregisterReceiver(btReceiver)
            } catch (e: Exception) {
                // 未登録なら無視
            }
            btReceiverRegistered = false
        }
        unregisterNetworkCallback()
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

        setupButtons(view)

        try {
            windowManager.addView(view, params)
            overlayView = view
            updateBluetoothIcon(view)
            updateClock()
        } catch (e: Exception) {
            // オーバーレイ権限が途中で取り消された場合など
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    /** ユーザー補助が無効なら案内Toastを出して false を返す */
    private fun requireAccessibility(): Boolean {
        if (LauncherAccessibilityService.isRunning) return true
        toast(R.string.bar_need_accessibility)
        return false
    }

    private fun setupButtons(view: android.view.View) {
        view.findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            if (requireAccessibility()) LauncherAccessibilityService.performBack()
        }
        val home = view.findViewById<ImageView>(R.id.btnHome)
        home.setOnClickListener { goHomeOrAppTop() }
        home.setOnLongClickListener {
            openLauncherTop()
            true
        }
        val prev = view.findViewById<ImageView>(R.id.btnPrevApp)
        prev.setOnClickListener {
            if (requireAccessibility() && !LauncherAccessibilityService.launchPreviousApp()) {
                toast(R.string.bar_no_prev_app)
            }
        }
        prev.setOnLongClickListener {
            if (requireAccessibility()) LauncherAccessibilityService.openRecents()
            true
        }

        view.findViewById<TextView>(R.id.btnSkip).setOnClickListener {
            val next = !Prefs.autoSkipEnabled
            Prefs.setAutoSkip(this, next)
            updateSkipButton(view)
            toast(if (next) R.string.bar_skip_on else R.string.bar_skip_off)
        }
        updateSkipButton(view)

        view.findViewById<ImageView>(R.id.btnWifi).setOnClickListener { rescanWifi() }

        view.findViewById<ImageView>(R.id.iconBluetooth).setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // 設定画面が開けない場合は無視
            }
        }
    }

    /** 自作ランチャーのトップを開く（MainActivity は singleTask・onNewIntent で先頭へスクロール） */
    private fun openLauncherTop() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // 起動できない場合は無視
        }
    }

    /** 前面が通常アプリならそのトップ画面へ戻す。通常アプリでなければ自作ランチャーを開く */
    private fun goHomeOrAppTop() {
        val pkg = if (LauncherAccessibilityService.isRunning) {
            LauncherAccessibilityService.currentAppPackage()
        } else null
        val launch = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
        if (launch == null) {
            openLauncherTop()
            return
        }
        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(launch)
        } catch (e: Exception) {
            openLauncherTop()
        }
    }

    /** Wi-Fi再検索：スキャンを要求し、Wi-Fi設定パネルを開く */
    @Suppress("DEPRECATION")
    private fun rescanWifi() {
        toast(R.string.bar_wifi_rescan)
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wm?.startScan()
        } catch (e: Exception) {
            // 権限不足・スロットリング等は無視
        }
        try {
            val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Settings.Panel.ACTION_WIFI
            } else {
                Settings.ACTION_WIFI_SETTINGS
            }
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // 設定画面が開けない場合は無視
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    handler.post { setWifiIconConnected(ok) }
                }

                override fun onLost(network: Network) {
                    handler.post { setWifiIconConnected(false) }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {
            // 権限不足などは無視（アイコンは未接続表示のまま）
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = netCallback ?: return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            // 未登録なら無視
        }
        netCallback = null
    }

    private fun setWifiIconConnected(connected: Boolean) {
        overlayView?.findViewById<ImageView>(R.id.btnWifi)?.alpha = if (connected) 1.0f else 0.35f
    }

    private fun updateSkipButton(view: android.view.View) {
        val btn = view.findViewById<TextView>(R.id.btnSkip)
        btn.setBackgroundResource(
            if (Prefs.autoSkipEnabled) R.drawable.bar_skip_on else R.drawable.bar_skip_off
        )
    }

    private fun updateClock() {
        val view = overlayView ?: return
        val clock = view.findViewById<TextView>(R.id.clock)
        val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN)
        clock.text = sdf.format(Date())
    }

    @SuppressLint("MissingPermission")
    private fun updateBluetoothIcon(view: android.view.View) {
        val icon = view.findViewById<ImageView>(R.id.iconBluetooth)
        val on = try {
            val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter?.isEnabled == true
        } catch (e: SecurityException) {
            false
        }
        icon.alpha = if (on) 1.0f else 0.35f
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
