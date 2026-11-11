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
import android.view.View
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 画面右端に常時小さく表示する「つまみ（ハンドル）」と、タップで呼び出す縦型の操作パネル
 * （時計・Wi-Fi再検索・Bluetooth・AutoSkip切替・直前アプリ・ホーム・戻る）を重ねて表示するフォアグラウンドサービス。
 * パネルは一定時間操作がないと自動的にスライドして隠れる。
 * 注意：これは「追加表示」であり、PRA106純正のSystemUIバーを消すものではない
 * （root権限がないため、純正バーの非表示化は不可）。
 */
class StatusBarService : Service() {

    private lateinit var windowManager: WindowManager
    private var handleView: View? = null
    private var panelView: View? = null
    private lateinit var panelParams: WindowManager.LayoutParams
    private var panelVisible = false
    private val handler = Handler(Looper.getMainLooper())

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            panelView?.let { updateBluetoothIcon(it) }
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

    private val hideRunnable = Runnable { hidePanel() }

    companion object {
        private const val CHANNEL_ID = "status_bar_overlay"
        private const val NOTIFICATION_ID = 1
        private const val AUTO_HIDE_DELAY_MS = 4500L
        private const val ANIM_DURATION_MS = 200L
        /** タップ可能領域の幅（見た目の帯より広く取り、誤タップしにくくする） */
        private const val HANDLE_TOUCH_WIDTH_DP = 48
        /** ハンドルの高さ（画面中央付近に限定し、他アプリの端スワイプと競合しないようにする） */
        private const val HANDLE_HEIGHT_DP = 160
        /** 見た目の帯の太さ（従来どおり細いまま） */
        private const val HANDLE_VISUAL_WIDTH_DP = 14

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
        showHandle()
        showPanelWindow()
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
        handler.removeCallbacks(hideRunnable)
        if (btReceiverRegistered) {
            try {
                unregisterReceiver(btReceiver)
            } catch (e: Exception) {
                // 未登録なら無視
            }
            btReceiverRegistered = false
        }
        unregisterNetworkCallback()
        handleView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // 既に外れている場合は無視
            }
        }
        panelView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // 既に外れている場合は無視
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 画面右端・中央付近に常時表示する、細い半透明のつまみ。
     * 見た目の帯は細いままだが、実際のタップ可能領域は48dp幅の透明な枠で広く取る。
     * スワイプでの開閉は誤作動の原因になるため廃止し、タップのみでパネルをトグルする。
     * 高さは画面中央付近の固定値に限定し、残りの右端は他アプリの端スワイプのためにタッチを透過させる。
     */
    private fun showHandle() {
        if (!Settings.canDrawOverlays(this)) return
        if (handleView != null) return

        val density = resources.displayMetrics.density
        val touchWidthPx = (HANDLE_TOUCH_WIDTH_DP * density).toInt()
        val handleHeightPx = (HANDLE_HEIGHT_DP * density).toInt()
        val visualWidthPx = (HANDLE_VISUAL_WIDTH_DP * density).toInt()

        // タップ可能領域（透明）の中に、見た目の帯（視覚的に細い）を右端寄せで重ねる
        val container = FrameLayout(this)
        container.contentDescription = getString(R.string.bar_handle_desc)

        val strip = View(this)
        strip.setBackgroundResource(R.drawable.bar_handle_bg)
        val stripParams = FrameLayout.LayoutParams(visualWidthPx, FrameLayout.LayoutParams.MATCH_PARENT)
        stripParams.gravity = Gravity.END
        container.addView(strip, stripParams)

        container.isClickable = true
        container.setOnClickListener {
            if (panelVisible) hidePanel() else showPanel()
        }

        val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            touchWidthPx,
            handleHeightPx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.END or Gravity.CENTER_VERTICAL

        try {
            windowManager.addView(container, params)
            handleView = container
        } catch (e: Exception) {
            // オーバーレイ権限が途中で取り消された場合など
        }
    }

    /** 操作パネル本体のウィンドウを追加する（最初は非表示・非タッチ状態） */
    @SuppressLint("InflateParams")
    private fun showPanelWindow() {
        if (!Settings.canDrawOverlays(this)) return
        if (panelView != null) return

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_status_bar, null)

        val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        panelParams = params

        setupButtons(view)

        try {
            windowManager.addView(view, params)
            panelView = view
            updateBluetoothIcon(view)
            updateClock()
            // レイアウト確定後に、パネルを右端の外側へ隠しておく
            view.post {
                view.translationX = view.width.toFloat()
            }
        } catch (e: Exception) {
            // オーバーレイ権限が途中で取り消された場合など
        }
    }

    /** パネルをスライドして表示し、自動で隠れるタイマーをセットする */
    private fun showPanel() {
        val view = panelView ?: return
        panelVisible = true
        panelParams.flags = panelParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        try {
            windowManager.updateViewLayout(view, panelParams)
        } catch (e: Exception) {
            // 無視
        }
        view.animate().translationX(0f).setDuration(ANIM_DURATION_MS).start()
        scheduleAutoHide()
    }

    /** パネルをスライドして隠し、タッチを下のアプリへ通すようにする */
    private fun hidePanel() {
        val view = panelView ?: return
        if (!panelVisible) return
        panelVisible = false
        handler.removeCallbacks(hideRunnable)
        view.animate().translationX(view.width.toFloat()).setDuration(ANIM_DURATION_MS)
            .withEndAction {
                panelParams.flags = panelParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                try {
                    windowManager.updateViewLayout(view, panelParams)
                } catch (e: Exception) {
                    // 無視
                }
            }
            .start()
    }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, AUTO_HIDE_DELAY_MS)
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    /**
     * ユーザー補助が無効なら案内Toastを出し、さらに自作ランチャー（MainActivity）を前面に呼び出して
     * 許可バナーの導線につなげる。運転中でも原因と対処がその場で分かるようにするため。
     */
    private fun requireAccessibility(): Boolean {
        if (LauncherAccessibilityService.isRunning) return true
        toast(R.string.bar_need_accessibility)
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            )
        } catch (e: Exception) {
            // 起動できない場合は無視
        }
        return false
    }

    private fun setupButtons(view: View) {
        view.findViewById<View>(R.id.btnBack).setOnClickListener {
            if (requireAccessibility()) LauncherAccessibilityService.performBack()
            scheduleAutoHide()
        }
        val home = view.findViewById<View>(R.id.btnHome)
        home.setOnClickListener {
            goHomeOrAppTop()
            scheduleAutoHide()
        }
        home.setOnLongClickListener {
            openLauncherTop()
            scheduleAutoHide()
            true
        }
        val prev = view.findViewById<View>(R.id.btnPrevApp)
        prev.setOnClickListener {
            if (requireAccessibility() && !LauncherAccessibilityService.launchPreviousApp()) {
                toast(R.string.bar_no_prev_app)
            }
            scheduleAutoHide()
        }
        prev.setOnLongClickListener {
            if (requireAccessibility()) LauncherAccessibilityService.openRecents()
            scheduleAutoHide()
            true
        }

        view.findViewById<View>(R.id.btnSkip).setOnClickListener {
            val next = !Prefs.autoSkipEnabled
            Prefs.setAutoSkip(this, next)
            updateSkipButton(view)
            toast(if (next) R.string.bar_skip_on else R.string.bar_skip_off)
            scheduleAutoHide()
        }
        updateSkipButton(view)

        view.findViewById<View>(R.id.btnWifi).setOnClickListener {
            rescanWifi()
            scheduleAutoHide()
        }

        view.findViewById<View>(R.id.iconBluetooth).setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                // 設定画面が開けない場合は無視
            }
            scheduleAutoHide()
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
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
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
        panelView?.findViewById<ImageView>(R.id.iconWifiImg)?.setImageResource(
            if (connected) R.drawable.bar_ic_wifi_on else R.drawable.bar_ic_wifi_off
        )
    }

    private fun updateSkipButton(view: View) {
        val btn = view.findViewById<View>(R.id.skipDot)
        btn.setBackgroundResource(
            if (Prefs.autoSkipEnabled) R.drawable.bar_skip_on else R.drawable.bar_skip_off
        )
    }

    private fun updateClock() {
        val view = panelView ?: return
        val clock = view.findViewById<TextView>(R.id.clock)
        val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN)
        clock.text = sdf.format(Date())
    }

    @SuppressLint("MissingPermission")
    private fun updateBluetoothIcon(view: View) {
        val icon = view.findViewById<ImageView>(R.id.iconBluetoothImg)
        val on = try {
            val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter?.isEnabled == true
        } catch (e: SecurityException) {
            false
        }
        icon.setImageResource(if (on) R.drawable.bar_ic_bluetooth_on else R.drawable.bar_ic_bluetooth_off)
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
            .setSmallIcon(R.drawable.tile_ic_home)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }
}
