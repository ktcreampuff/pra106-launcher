package com.shikaketegami.pra106launcher

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * ホーム画面本体。アプリのグリッド・ドック・並べ替え・非表示を扱う。
 * ホームボタンを押した時の挙動・直前アプリへの復帰は、Androidの標準タスク管理に任せる
 * （HOMEカテゴリのActivityとして登録するだけで、通常のAndroidと同じ挙動になる）。
 */
class MainActivity : Activity() {

    private lateinit var appGrid: RecyclerView
    private lateinit var dockList: RecyclerView
    private lateinit var permissionBanner: TextView
    private lateinit var settingsButton: View
    private lateinit var nativeHomeButton: View
    private lateinit var reorderBar: View
    private lateinit var gridAdapter: AppAdapter
    private lateinit var dockAdapter: AppAdapter
    private lateinit var gridLayout: GridLayoutManager
    private lateinit var bgVideo: TextureView
    private lateinit var bgImage: ImageView
    private lateinit var bgScrim: View
    private lateinit var widgetScroll: View
    private var importingBackground = false
    private val loadSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private lateinit var widgetPanel: WidgetPanel

    // 背景動画の再生状態
    private var player: MediaPlayer? = null
    private var playerSurface: Surface? = null
    private var playerPrepared = false
    private var videoW = 0
    private var videoH = 0
    private var resumed = false
    /** onStart〜onStop の間だけ true（プレイヤーはこの間だけ保持する） */
    private var started = false
    /** 動画の最初のフレームが描画済みか */
    private var videoRendering = false
    /** 読み込み失敗を処理済みの世代（同じ世代でToastや回数加算を重ねない） */
    private var bgFailedGen = -1
    /** 背景の読み込み世代（古い非同期結果を捨てるため） */
    private var bgGeneration = 0

    /** 端末にある全アプリ（非表示含む・名前順） */
    private var allApps: List<AppInfo> = emptyList()
    private var reorderMode = false
    private val handler = Handler(Looper.getMainLooper())
    private val reloadRunnable = Runnable { refresh() }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // 連続して届くので少し待ってまとめて再読込
            handler.removeCallbacks(reloadRunnable)
            handler.postDelayed(reloadRunnable, 400)
        }
    }

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
    ) {
        override fun isLongPressDragEnabled(): Boolean = reorderMode

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            gridAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        appGrid = findViewById(R.id.appGrid)
        dockList = findViewById(R.id.dockList)
        permissionBanner = findViewById(R.id.permissionBanner)
        settingsButton = findViewById(R.id.settingsButton)
        nativeHomeButton = findViewById(R.id.nativeHomeButton)
        reorderBar = findViewById(R.id.reorderBar)
        bgVideo = findViewById(R.id.bgVideo)
        bgImage = findViewById(R.id.bgImage)
        bgScrim = findViewById(R.id.bgScrim)
        widgetScroll = findViewById(R.id.widgetScroll)
        widgetPanel = WidgetPanel(this, findViewById<LinearLayout>(R.id.widgetContainer))
        widgetPanel.onChanged = {
            // 追加が完了したので、ここで初めて「表示」設定にする
            if (!widgetPanel.isEmpty) Prefs.setWidgetPanelVisible(this, true)
            applyWidgetVisibility()
        }
        widgetPanel.onCancelled = { applyWidgetVisibility() }
        bgVideo.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                if (started && player == null && bgFailedGen != bgGeneration && videoActive()) startVideo(st)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                updateVideoTransform()
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                releasePlayer()
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        val initialCols = if (Prefs.getAutoColumns(this)) DEFAULT_AUTO_COLUMNS
        else Prefs.getColumns(this).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        gridLayout = GridLayoutManager(this, initialCols)
        appGrid.layoutManager = gridLayout
        gridAdapter = AppAdapter(R.layout.item_app, { launchApp(it) }, { v, app -> showAppMenu(v, app) })
        gridAdapter.iconSizeDp = Prefs.getIconSizeDp(this)
        appGrid.adapter = gridAdapter
        // 画面サイズ・領域の幅が変わったら列数を再計算
        appGrid.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateColumns() }
        touchHelper.attachToRecyclerView(appGrid)

        dockList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        dockAdapter = AppAdapter(R.layout.item_dock, { launchApp(it) }, { v, app -> showDockMenu(v, app) })
        dockAdapter.iconSizeDp = Prefs.getIconSizeDp(this)
        dockAdapter.iconScale = 0.85f
        dockAdapter.iconMaxDp = DOCK_ICON_MAX_DP
        dockAdapter.itemExtraWidthDp = DOCK_ITEM_EXTRA_DP
        dockList.adapter = dockAdapter

        settingsButton.setOnClickListener { showSettingsMenu(it) }
        nativeHomeButton.setOnClickListener { openNativeHome() }
        reorderBar.setOnClickListener { setReorderMode(false) }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter)

        refresh()
        if (Prefs.getBackgroundLoading(this, false) || Prefs.getBackgroundLoading(this, true)) {
            // 前回、背景の読み込み中に落ちた可能性：即削除せず失敗回数を数え、3回で解除する
            Prefs.setBackgroundLoading(this, false, false)
            Prefs.setBackgroundLoading(this, true, false)
            val n = Prefs.getBackgroundFailCount(this) + 1
            if (n >= MAX_BG_FAILS) {
                deleteBackgroundFiles()
                Prefs.setBackground(this, null, false)
                Prefs.setBackgroundFailCount(this, 0)
                Toast.makeText(this, R.string.home_background_crash_disabled, Toast.LENGTH_LONG).show()
            } else {
                Prefs.setBackgroundFailCount(this, n)
            }
        }
        applyBackground()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 画面の再生成はしない。列数の再計算だけ行う（レイアウト確定後にも再計算される）
        appGrid.post { updateColumns() }
    }

    override fun onStart() {
        super.onStart()
        started = true
        widgetPanel.start()
        applyWidgetVisibility()
        // 動画はonStartで準備・再生、onStopで解放する
        if (player == null && bgFailedGen != bgGeneration && videoActive() && bgVideo.isAvailable) {
            bgVideo.surfaceTexture?.let { startVideo(it) }
        }
    }

    override fun onStop() {
        started = false
        releasePlayer()
        widgetPanel.stop()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(reloadRunnable)
        bgGeneration++
        releasePlayer()
        // 読み込み中フラグを下ろす（正常終了をクラッシュと誤判定しない）
        Prefs.setBackgroundLoading(this, false, false)
        Prefs.setBackgroundLoading(this, true, false)
        try {
            unregisterReceiver(packageReceiver)
        } catch (e: IllegalArgumentException) {
            // 未登録なら無視
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // ホームボタン再押下：並べ替え解除して先頭へ
        if (reorderMode) setReorderMode(false)
        appGrid.scrollToPosition(0)
    }

    override fun onBackPressed() {
        if (reorderMode) {
            setReorderMode(false)
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        // 他アプリが前面のときはデコード負荷を避けるため必ず停止
        resumed = false
        try {
            player?.let { if (playerPrepared && it.isPlaying) it.pause() }
        } catch (e: IllegalStateException) {
            // 無視
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        try {
            player?.let { if (playerPrepared && !it.isPlaying) it.start() }
        } catch (e: IllegalStateException) {
            // 無視
        }
        applyWidgetVisibility()
        checkPermissionsAndMaybeStartOverlay()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /**
     * 標準のAndroid機能（イマーシブモード）で、純正ステータスバー／ナビゲーションバーを
     * 隠すリクエストを送る。root権限は使わないため、本体のSystemUI実装によっては
     * 効かない、または一部しか隠れないことがある。
     */
    private fun hideSystemBars() {
        window.setDecorFitsSystemWindows(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    /** アプリ一覧を読み直してグリッドとドックに反映する */
    private fun refresh() {
        allApps = loadInstalledApps()
        applyLists()
    }

    /** 保存済みの並び・ドック・非表示を allApps に適用する */
    private fun applyLists() {
        val hidden = Prefs.getHidden(this)
        val visible = allApps.filter { it.key !in hidden }
        val order = Prefs.getOrder(this)
        val index = HashMap<String, Int>()
        order.forEachIndexed { i, k -> if (!index.containsKey(k)) index[k] = i }
        // 登録済みは保存順、未登録は名前順（allApps は名前順なので安定ソートで末尾に）
        val sorted = visible.sortedBy { index[it.key] ?: Int.MAX_VALUE }
        gridAdapter.submit(sorted)

        val byKey = visible.associateBy { it.key }
        dockAdapter.submit(Prefs.getDock(this).mapNotNull { byKey[it] }.take(Prefs.DOCK_MAX))
    }

    private fun setReorderMode(on: Boolean) {
        if (reorderMode == on) return
        if (!on) saveGridOrder()
        reorderMode = on
        gridAdapter.reorderMode = on
        dockAdapter.reorderMode = on
        reorderBar.visibility = if (on) View.VISIBLE else View.GONE
        settingsButton.visibility = if (on) View.INVISIBLE else View.VISIBLE
        nativeHomeButton.visibility = View.GONE // 純正ホームはクイック設定タイルへ移動
    }

    private fun saveGridOrder() {
        val keys = gridAdapter.items().map { it.key }
        val keySet = keys.toSet()
        // 非表示アプリなど、グリッドに無いキーは後ろに残す
        val remaining = Prefs.getOrder(this).filter { it !in keySet }
        Prefs.setOrder(this, keys + remaining)
    }

    private fun showAppMenu(anchor: View, app: AppInfo) {
        val inDock = Prefs.getDock(this).contains(app.key)
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_DOCK, 0,
            getString(if (inDock) R.string.home_item_dock_remove else R.string.home_item_dock_add))
        menu.menu.add(0, MENU_HIDE, 1, getString(R.string.home_item_hide))
        menu.menu.add(0, MENU_INFO, 2, getString(R.string.home_item_app_info))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_DOCK -> if (inDock) removeFromDock(app) else addToDock(app)
                MENU_HIDE -> hideApp(app)
                MENU_INFO -> openAppInfo(app)
            }
            true
        }
        menu.show()
    }

    private fun showDockMenu(anchor: View, app: AppInfo) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_DOCK, 0, getString(R.string.home_item_dock_remove))
        menu.setOnMenuItemClickListener {
            removeFromDock(app)
            true
        }
        menu.show()
    }

    private fun addToDock(app: AppInfo) {
        val dock = Prefs.getDock(this)
        if (dock.size >= Prefs.DOCK_MAX) {
            Toast.makeText(this, getString(R.string.home_dock_full, Prefs.DOCK_MAX), Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.setDock(this, dock + app.key)
        applyLists()
    }

    private fun removeFromDock(app: AppInfo) {
        Prefs.setDock(this, Prefs.getDock(this).filter { it != app.key })
        applyLists()
    }

    private fun hideApp(app: AppInfo) {
        Prefs.setHidden(this, Prefs.getHidden(this) + app.key)
        applyLists()
    }

    private fun openAppInfo(app: AppInfo) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // 開けない場合は無視
        }
    }

    private fun showSettingsMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        val edit = menu.menu.addSubMenu(0, MENU_GROUP_EDIT, 0, getString(R.string.home_menu_group_edit))
        edit.add(0, MENU_REORDER, 0, getString(R.string.home_menu_reorder))
        edit.add(0, MENU_RESTORE, 1, getString(R.string.home_menu_restore_hidden))
        edit.add(0, MENU_ICON_SIZE, 2, getString(R.string.home_menu_icon_size))
        edit.add(0, MENU_COLUMNS, 3, getString(R.string.home_menu_columns))
        edit.add(0, MENU_RESET, 4, getString(R.string.home_menu_reset_order))
        val other = menu.menu.addSubMenu(0, MENU_GROUP_OTHER, 1, getString(R.string.home_menu_group_other))
        other.add(0, MENU_WIDGET_ADD, 0, getString(R.string.home_menu_widget_add))
        other.add(0, MENU_WIDGET_TOGGLE, 1, getString(
            if (Prefs.getWidgetPanelVisible(this)) R.string.home_menu_widget_hide
            else R.string.home_menu_widget_show))
        other.add(0, MENU_SET_BG, 2, getString(R.string.home_menu_set_background))
        other.add(0, MENU_CLEAR_BG, 3, getString(R.string.home_menu_clear_background))
        other.add(0, MENU_NATIVE_HOME, 4, getString(R.string.home_menu_native_home))
        other.add(0, MENU_CHANGE_HOME, 5, getString(R.string.home_menu_change_home))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_REORDER -> setReorderMode(true)
                MENU_RESTORE -> showVisibleAppsDialog()
                MENU_NATIVE_HOME -> openNativeHome()
                MENU_CHANGE_HOME -> openHomeSettings()
                MENU_COLUMNS -> showColumnsDialog()
                MENU_ICON_SIZE -> showIconSizeDialog()
                MENU_WIDGET_ADD -> {
                    // 空でも追加操作の間は領域を一時表示する（Prefs は書き換えず、結果が返ったら再判定）
                    widgetScroll.visibility = View.VISIBLE
                    widgetPanel.addWidget()
                }
                MENU_WIDGET_TOGGLE -> {
                    Prefs.setWidgetPanelVisible(this, !Prefs.getWidgetPanelVisible(this))
                    applyWidgetVisibility()
                }
                MENU_SET_BG -> {
                    if (importingBackground) busyToast() else pickBackground()
                }
                MENU_CLEAR_BG -> if (importingBackground) busyToast() else {
                    deleteBackgroundFiles()
                    Prefs.setBackground(this, null, false)
                    Prefs.setBackgroundFailCount(this, 0)
                    applyBackground()
                }
                MENU_RESET -> {
                    Prefs.setOrder(this, emptyList())
                    applyLists()
                }
            }
            true
        }
        menu.show()
    }

    private fun busyToast() {
        Toast.makeText(this, R.string.home_background_importing_busy, Toast.LENGTH_SHORT).show()
    }

    private fun pickBackground() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
        }
        try {
            startActivityForResult(intent, REQUEST_BACKGROUND)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.home_picker_not_found, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.home_background_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (widgetPanel.handleActivityResult(requestCode, resultCode, data)) {
            applyWidgetVisibility()
            return
        }
        if (requestCode != REQUEST_BACKGROUND || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        importBackground(uri)
    }

    /**
     * 選んだファイルを filesDir/background_<時刻>.<拡張子> へコピーして、以後はそのコピーだけを参照する。
     * 動画ならポスター画像も作る。コピーはIOスレッドで行い、失敗しても既存の設定は変えない。
     */
    private fun importBackground(uri: Uri) {
        if (importingBackground) {
            Toast.makeText(this, R.string.home_background_importing_busy, Toast.LENGTH_SHORT).show()
            return
        }
        importingBackground = true
        // 読み込み中の旧背景の結果を無効にする（世代を進める）
        bgGeneration++
        Toast.makeText(this, R.string.home_background_importing, Toast.LENGTH_SHORT).show()
        val app = applicationContext
        val mime = contentResolver.getType(uri)
        val dir = filesDir
        val dm = resources.displayMetrics
        Thread {
            var ok = false
            var isVideo = false
            var highRes = false
            var dest: File? = null
            var poster: File? = null
            val tmp = File(dir, BG_TMP_NAME)
            try {
                val name = try {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                } catch (e: Exception) {
                    null
                }
                val ext0 = (name ?: uri.lastPathSegment ?: "").substringAfterLast('.', "").lowercase()
                isVideo = when {
                    mime?.startsWith("video/") == true -> true
                    mime?.startsWith("image/") == true -> false
                    else -> ext0 in VIDEO_EXTS
                }
                val ext = if (ext0.isNotEmpty() && ext0.length <= 5 && ext0.all { it.isLetterOrDigit() }) ext0
                else if (isVideo) "mp4" else "jpg"
                val input = contentResolver.openInputStream(uri) ?: throw IOException("open failed")
                input.use { i -> FileOutputStream(tmp).use { o -> i.copyTo(o) } }
                // 新本体は旧本体と別名（タイムスタンプ付き）で保存し、旧ファイルを上書きしない
                val d = File(dir, "${BG_BASE_NAME}_${System.currentTimeMillis()}.$ext")
                if (!tmp.renameTo(d)) throw IOException("rename failed")
                dest = d
                if (isVideo) {
                    // ポスターも本体名から決まる別名で保存する（旧ポスターは触らない）
                    val pf = File(dir, d.name + BG_POSTER_SUFFIX)
                    poster = pf
                    highRes = makePoster(d, pf, maxOf(dm.widthPixels, dm.heightPixels))
                }
                // 順序：設定を新ファイルへ切り替え → 旧ファイル（旧ポスター含む）を削除
                Prefs.setBackground(app, "file://" + d.absolutePath, isVideo)
                Prefs.setBackgroundFailCount(app, 0)
                ok = true
                val keep = HashSet<String>()
                keep.add(d.name)
                poster?.let { keep.add(it.name) }
                deleteBackgroundFiles(dir, keep)
            } catch (e: Throwable) {
                // 切り替え後の旧ファイル削除で失敗しても、設定は新ファイルを指しているので成功扱い
            } finally {
                if (!ok) {
                    try {
                        tmp.delete()
                        // 切り替え前に失敗した場合だけ新ファイルを消す（設定が新ファイルを指していれば残す）
                        val cur = Prefs.getBackgroundUri(app)
                        val d = dest
                        if (d != null && cur != "file://" + d.absolutePath) {
                            d.delete()
                            poster?.delete()
                        }
                    } catch (e: Throwable) {
                        // 無視
                    }
                }
                val success = ok
                val hr = highRes
                handler.post {
                    importingBackground = false
                    if (success) {
                        Toast.makeText(app, R.string.home_background_set_done, Toast.LENGTH_SHORT).show()
                        if (hr) Toast.makeText(app, R.string.home_background_high_res, Toast.LENGTH_LONG).show()
                        if (!isDestroyed) applyBackground()
                    } else {
                        Toast.makeText(app, R.string.home_background_import_failed, Toast.LENGTH_SHORT).show()
                        // 失敗しても、中断していた既存背景の表示は戻す
                        if (!isDestroyed) applyBackground()
                    }
                }
            }
        }.start()
    }

    /** 動画の1フレームをポスターとして保存する。戻り値は「長辺が1920pxを超える動画か」 */
    private fun makePoster(video: File, poster: File, screenLong: Int): Boolean {
        var highRes = false
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(video.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            highRes = maxOf(w, h) > 1920
            val frame = r.getFrameAtTime(0)
            if (frame != null) {
                val limit = maxOf(screenLong, 1280)
                val longSide = maxOf(frame.width, frame.height)
                val out = if (longSide > limit) {
                    val s = limit.toFloat() / longSide
                    Bitmap.createScaledBitmap(
                        frame,
                        maxOf(1, (frame.width * s).toInt()),
                        maxOf(1, (frame.height * s).toInt()),
                        true
                    )
                } else frame
                FileOutputStream(poster).use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }
        } catch (e: Throwable) {
            poster.delete()
        } finally {
            try {
                r.release()
            } catch (e: Throwable) {
                // 無視
            }
        }
        return highRes
    }

    /** 背景関連のコピー（background*）を filesDir から削除する。keep に指定した名前は残す */
    private fun deleteBackgroundFiles(dir: File = filesDir, keep: Set<String> = emptySet()) {
        dir.listFiles()?.forEach {
            if (it.name.startsWith(BG_BASE_NAME) && it.name !in keep) it.delete()
        }
    }

    /** 保存済みの背景ファイル（file:// のコピー）。未設定や旧形式なら null */
    private fun backgroundFile(): File? {
        val u = Prefs.getBackgroundUri(this) ?: return null
        return if (u.startsWith("file://")) File(u.removePrefix("file://")) else null
    }

    private fun videoActive(): Boolean =
        Prefs.getBackgroundUri(this) != null && Prefs.getBackgroundIsVideo(this)

    /** 保存済みの背景設定を画面に反映する */
    private fun applyBackground() {
        bgGeneration++
        releasePlayer()
        // 旧画像は新しいビットマップのデコード成功後に差し替える（ここでは消さない）
        val uriStr = Prefs.getBackgroundUri(this)
        if (uriStr == null) {
            hideBackgroundViews()
            return
        }
        val file = backgroundFile()
        if (file == null) {
            // 旧形式（content://）の設定は使えないので解除する
            Prefs.setBackground(this, null, false)
            hideBackgroundViews()
            return
        }
        if (!file.exists()) {
            onBackgroundFailed()
            return
        }
        bgScrim.visibility = View.VISIBLE
        if (Prefs.getBackgroundIsVideo(this)) {
            bgVideo.visibility = View.VISIBLE
            val poster = File(file.parentFile, file.name + BG_POSTER_SUFFIX)
            if (poster.exists()) {
                // 新しいポスターのデコードが終わるまで旧画像を残す
                loadBackgroundImage(poster, true)
            } else {
                bgImage.visibility = View.GONE
                bgImage.setImageDrawable(null)
            }
            // 既に Surface があれば直接開始、無ければ onSurfaceTextureAvailable で開始
            if (started && bgVideo.isAvailable) bgVideo.surfaceTexture?.let { startVideo(it) }
        } else {
            // 旧動画の最終フレーム／旧画像は、新画像のデコード成功後に隠す
            loadBackgroundImage(file, false)
        }
    }

    private fun hideBackgroundViews() {
        bgVideo.visibility = View.GONE
        bgImage.visibility = View.GONE
        bgImage.setImageDrawable(null)
        bgScrim.visibility = View.GONE
    }

    /** 画像を縮小して読み込む（総画素数が画面の2倍以下になるまで縮小）。poster=true は動画のポスター */
    private fun loadBackgroundImage(file: File, poster: Boolean) {
        val gen = bgGeneration
        val dm = resources.displayMetrics
        val reqW = dm.widthPixels
        val reqH = dm.heightPixels
        val app = applicationContext
        // ポスターのデコードではフラグを立てない（画像用フラグのみ）
        val myLoad = loadSeq.incrementAndGet()
        // ポスター読み込みは旧画像読み込みを置き換えるので、旧スレッドが下ろさない分をここで下ろす
        Prefs.setBackgroundLoading(app, false, !poster)
        Thread {
            var bmp: Bitmap? = null
            try {
                bmp = decodeBitmap(file, reqW, reqH)
            } catch (e: Throwable) {
                bmp = null
            }
            // 自分が最新の読み込みのときだけフラグを下ろす
            if (!poster && myLoad == loadSeq.get()) Prefs.setBackgroundLoading(app, false, false)
            val result = bmp
            handler.post {
                if (isDestroyed || gen != bgGeneration || myLoad != loadSeq.get()) return@post
                if (result == null) {
                    if (!poster) onBackgroundFailed()
                } else {
                    bgImage.setImageBitmap(result)
                    // 動画が既に描画中ならポスターは隠したまま
                    if (!poster || !videoRendering) bgImage.visibility = View.VISIBLE
                    if (!poster) {
                        bgVideo.visibility = View.GONE
                        Prefs.setBackgroundFailCount(this, 0)
                    }
                }
            }
        }.start()
    }

    /** EXIF回転を反映しつつ、総画素数が画面画素数の2倍以下になるよう縮小して読み込む */
    private fun decodeBitmap(file: File, reqW: Int, reqH: Int): Bitmap? {
        val limit = 2L * reqW * reqH
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(file)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width.toLong()
                val h = info.size.height.toLong()
                var s = 1
                while ((w / s) * (h / s) > limit) s *= 2
                if (s > 1) decoder.setTargetSampleSize(s)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val path = file.absolutePath
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > limit) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        val orientation = try {
            ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(270f)
                m.postScale(-1f, 1f)
            }
        }
        if (m.isIdentity) return bmp
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    private fun startVideo(st: SurfaceTexture) {
        val file = backgroundFile() ?: return
        if (bgFailedGen == bgGeneration) return
        releasePlayer()
        videoW = 0
        videoH = 0
        videoRendering = false
        // プロセス死亡の検知に必要なので、ここだけ commit で確実に書く
        Prefs.setBackgroundLoading(this, true, true, sync = true)
        try {
            val surface = Surface(st)
            playerSurface = surface
            val mp = MediaPlayer()
            player = mp
            playerPrepared = false
            mp.setSurface(surface)
            mp.setDataSource(file.absolutePath)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, w, h ->
                videoW = w
                videoH = h
                updateVideoTransform()
            }
            mp.setOnPreparedListener {
                if (player !== mp) return@setOnPreparedListener
                playerPrepared = true
                updateVideoTransform()
                if (resumed) it.start()
            }
            mp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && player === mp) {
                    // 最初のフレームが描画されてからポスターを外して動画を見せる
                    videoRendering = true
                    bgImage.visibility = View.GONE
                    Prefs.setBackgroundLoading(this, true, false)
                    Prefs.setBackgroundFailCount(this, 0)
                }
                false
            }
            mp.setOnErrorListener { _, _, _ ->
                if (player === mp) onBackgroundFailed()
                true
            }
            mp.prepareAsync()
        } catch (e: Throwable) {
            onBackgroundFailed()
        }
    }

    /** 動画が画面を覆うように（centerCrop 相当）TextureView を拡大する */
    private fun updateVideoTransform() {
        val vw = bgVideo.width.toFloat()
        val vh = bgVideo.height.toFloat()
        if (videoW <= 0 || videoH <= 0 || vw <= 0f || vh <= 0f) return
        val fill = maxOf(vw / videoW, vh / videoH)
        val matrix = Matrix()
        matrix.setScale(fill * videoW / vw, fill * videoH / vh, vw / 2f, vh / 2f)
        bgVideo.setTransform(matrix)
    }

    private fun releasePlayer() {
        playerPrepared = false
        videoRendering = false
        player?.let {
            try {
                it.setOnErrorListener(null)
                it.setOnPreparedListener(null)
                it.setOnInfoListener(null)
                it.release()
            } catch (e: Exception) {
                // 無視
            }
        }
        val hadPlayer = player != null
        player = null
        playerSurface?.release()
        playerSurface = null
        if (hadPlayer) {
            Prefs.setBackgroundLoading(this, true, false)
            // 動画を止めたらポスターを再表示する
            if (videoActive() && bgImage.drawable != null) bgImage.visibility = View.VISIBLE
        }
    }

    /**
     * 背景の読み込みに失敗したとき。設定は消さず、この回は背景なしで表示する。
     * Toast は1回だけ。連続3回失敗したら背景を解除する。
     */
    private fun onBackgroundFailed() {
        // 背景の取り込み中は、旧背景の失敗で回数加算やToastをしない
        if (importingBackground) return
        if (bgFailedGen == bgGeneration) return
        bgFailedGen = bgGeneration
        releasePlayer()
        Prefs.setBackgroundLoading(this, false, false)
        Prefs.setBackgroundLoading(this, true, false)
        hideBackgroundViews()
        val n = Prefs.getBackgroundFailCount(this) + 1
        if (n >= MAX_BG_FAILS) {
            deleteBackgroundFiles()
            Prefs.setBackground(this, null, false)
            Prefs.setBackgroundFailCount(this, 0)
        } else {
            Prefs.setBackgroundFailCount(this, n)
        }
        Toast.makeText(this, R.string.home_background_open_failed, Toast.LENGTH_SHORT).show()
    }

    /** 全アプリをチェック一覧で出し、チェック＝表示・外す＝非表示として保存する */
    private fun showVisibleAppsDialog() {
        val apps = allApps
        val hidden = Prefs.getHidden(this)
        val checked = BooleanArray(apps.size) { apps[it].key !in hidden }
        AlertDialog.Builder(this)
            .setTitle(R.string.home_visible_title)
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.home_ok) { _, _ ->
                val allKeys = apps.map { it.key }.toSet()
                // 現在インストールされていないアプリの非表示設定はそのまま残す
                val newHidden = (hidden - allKeys) +
                    apps.filterIndexed { i, _ -> !checked[i] }.map { it.key }
                Prefs.setHidden(this, newHidden)
                applyLists()
            }
            .setNegativeButton(R.string.home_cancel, null)
            .show()
    }

    /** 純正ホーム（自パッケージ以外でHOMEを解決できる最初のActivity）を開く */
    private fun openNativeHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val target = packageManager.queryIntentActivities(homeIntent, 0)
            .firstOrNull { it.activityInfo.packageName != packageName }
        if (target == null) {
            Toast.makeText(this, R.string.home_native_home_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            component = ComponentName(target.activityInfo.packageName, target.activityInfo.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.home_native_home_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    /** デフォルトのホームアプリ設定を開く（失敗時は設定トップ） */
    private fun openHomeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: Exception) {
                // 開けない場合は無視
            }
        }
    }

    /** 設定が表示でも、ウィジェットが0個なら領域を出さない（グリッドを広く使う） */
    private fun applyWidgetVisibility() {
        val show = Prefs.getWidgetPanelVisible(this) && !widgetPanel.isEmpty
        widgetScroll.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** 自動ON時、グリッドの実幅とアイコンの大きさから列数を決める（最低3） */
    private fun updateColumns() {
        if (!Prefs.getAutoColumns(this)) return
        val usable = appGrid.width - appGrid.paddingLeft - appGrid.paddingRight
        if (usable <= 0) return
        val cellPx = (Prefs.getIconSizeDp(this) + CELL_MARGIN_DP) * resources.displayMetrics.density
        val n = (usable / cellPx).toInt().coerceAtLeast(MIN_AUTO_COLUMNS)
        if (gridLayout.spanCount != n) gridLayout.spanCount = n
    }

    private fun showIconSizeDialog() {
        val sizes = intArrayOf(48, 64, 80, 96)
        val labels = arrayOf(
            getString(R.string.home_icon_size_small),
            getString(R.string.home_icon_size_medium),
            getString(R.string.home_icon_size_large),
            getString(R.string.home_icon_size_xlarge)
        )
        val current = sizes.indexOf(Prefs.getIconSizeDp(this)).let { if (it < 0) 1 else it }
        AlertDialog.Builder(this)
            .setTitle(R.string.home_icon_size_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                Prefs.setIconSizeDp(this, sizes[which])
                gridAdapter.iconSizeDp = sizes[which]
                dockAdapter.iconSizeDp = sizes[which]
                updateColumns()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.home_cancel, null)
            .show()
    }

    private fun showColumnsDialog() {
        val options = (MIN_COLUMNS..MAX_COLUMNS).toList()
        val labels = (listOf(getString(R.string.home_columns_auto)) + options.map { it.toString() }).toTypedArray()
        val current = if (Prefs.getAutoColumns(this)) 0
        else (options.indexOf(gridLayout.spanCount) + 1).coerceAtLeast(1)
        AlertDialog.Builder(this)
            .setTitle(R.string.home_columns_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                if (which == 0) {
                    Prefs.setAutoColumns(this, true)
                    updateColumns()
                } else {
                    val n = options[which - 1]
                    Prefs.setAutoColumns(this, false)
                    Prefs.setColumns(this, n)
                    gridLayout.spanCount = n
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.home_cancel, null)
            .show()
    }

    private fun loadInstalledApps(): List<AppInfo> {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        return resolveInfos
            .map { info ->
                AppInfo(
                    label = info.loadLabel(pm).toString(),
                    packageName = info.activityInfo.packageName,
                    activityName = info.activityInfo.name,
                    icon = info.loadIcon(pm)
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun launchApp(app: AppInfo) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(app.packageName, app.activityName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // 起動できないアプリは無視する
        }
    }

    /**
     * オーバーレイ表示権限とユーザー補助サービスが有効かを確認する。
     * 片方でも未許可ならバナーを出し、両方許可済みならステータスバーのオーバーレイを起動する。
     */
    private fun checkPermissionsAndMaybeStartOverlay() {
        val hasOverlayPermission = Settings.canDrawOverlays(this)

        if (!hasOverlayPermission) {
            permissionBanner.visibility = View.VISIBLE
            permissionBanner.text = getString(R.string.grant_overlay_permission)
            permissionBanner.setOnClickListener {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            return
        }

        if (!isAccessibilityServiceEnabled()) {
            permissionBanner.visibility = View.VISIBLE
            permissionBanner.text = getString(R.string.grant_accessibility_permission)
            permissionBanner.setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            return
        }

        permissionBanner.visibility = View.GONE
        StatusBarService.start(this)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    companion object {
        private const val MIN_COLUMNS = 3
        private const val MAX_COLUMNS = 7
        private const val MIN_AUTO_COLUMNS = 3
        private const val DOCK_ICON_MAX_DP = 72
        private const val DOCK_ITEM_EXTRA_DP = 20
        private const val DEFAULT_AUTO_COLUMNS = 4
        private const val CELL_MARGIN_DP = 32
        private const val MENU_GROUP_EDIT = 20
        private const val MENU_GROUP_OTHER = 21
        private const val MENU_ICON_SIZE = 22
        private const val MENU_WIDGET_ADD = 23
        private const val MENU_WIDGET_TOGGLE = 24
        private const val MENU_DOCK = 1
        private const val MENU_HIDE = 2
        private const val MENU_INFO = 3
        private const val MENU_REORDER = 11
        private const val MENU_RESTORE = 12
        private const val MENU_COLUMNS = 13
        private const val MENU_RESET = 14
        private const val MENU_NATIVE_HOME = 15
        private const val MENU_CHANGE_HOME = 16
        private const val MENU_SET_BG = 17
        private const val MENU_CLEAR_BG = 18
        private const val REQUEST_BACKGROUND = 100
        private const val BG_BASE_NAME = "background"
        private const val BG_TMP_NAME = "background_tmp"
        private const val BG_POSTER_SUFFIX = "_poster.jpg"
        private const val MAX_BG_FAILS = 3
        private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "3gp", "mov")
    }
}
