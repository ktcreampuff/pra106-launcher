package com.shikaketegami.pra106launcher

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

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

        gridLayout = GridLayoutManager(this, Prefs.getColumns(this).coerceIn(MIN_COLUMNS, MAX_COLUMNS))
        appGrid.layoutManager = gridLayout
        gridAdapter = AppAdapter(R.layout.item_app, { launchApp(it) }, { v, app -> showAppMenu(v, app) })
        appGrid.adapter = gridAdapter
        touchHelper.attachToRecyclerView(appGrid)

        dockList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        dockAdapter = AppAdapter(R.layout.item_dock, { launchApp(it) }, { v, app -> showDockMenu(v, app) })
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
    }

    override fun onDestroy() {
        handler.removeCallbacks(reloadRunnable)
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

    override fun onResume() {
        super.onResume()
        checkPermissionsAndMaybeStartOverlay()
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
        menu.menu.add(0, MENU_REORDER, 0, getString(R.string.home_menu_reorder))
        menu.menu.add(0, MENU_RESTORE, 1, getString(R.string.home_menu_restore_hidden))
        menu.menu.add(0, MENU_COLUMNS, 2, getString(R.string.home_menu_columns))
        menu.menu.add(0, MENU_RESET, 3, getString(R.string.home_menu_reset_order))
        menu.menu.add(0, MENU_NATIVE_HOME, 4, getString(R.string.home_menu_native_home))
        menu.menu.add(0, MENU_CHANGE_HOME, 5, getString(R.string.home_menu_change_home))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_REORDER -> setReorderMode(true)
                MENU_RESTORE -> showVisibleAppsDialog()
                MENU_NATIVE_HOME -> openNativeHome()
                MENU_CHANGE_HOME -> openHomeSettings()
                MENU_COLUMNS -> showColumnsDialog()
                MENU_RESET -> {
                    Prefs.setOrder(this, emptyList())
                    applyLists()
                }
            }
            true
        }
        menu.show()
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

    private fun showColumnsDialog() {
        val options = (MIN_COLUMNS..MAX_COLUMNS).toList()
        val current = options.indexOf(gridLayout.spanCount).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.home_columns_title)
            .setSingleChoiceItems(options.map { it.toString() }.toTypedArray(), current) { dialog, which ->
                val n = options[which]
                Prefs.setColumns(this, n)
                gridLayout.spanCount = n
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
        private const val MIN_COLUMNS = 4
        private const val MAX_COLUMNS = 7
        private const val MENU_DOCK = 1
        private const val MENU_HIDE = 2
        private const val MENU_INFO = 3
        private const val MENU_REORDER = 11
        private const val MENU_RESTORE = 12
        private const val MENU_COLUMNS = 13
        private const val MENU_RESET = 14
        private const val MENU_NATIVE_HOME = 15
        private const val MENU_CHANGE_HOME = 16
    }
}
