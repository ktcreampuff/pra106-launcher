package com.shikaketegami.pra106launcher

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * ホーム画面本体。インストール済みアプリをグリッド表示するだけのシンプルなランチャー。
 * ホームボタンを押した時の挙動・直前アプリへの復帰は、Androidの標準タスク管理に任せる
 * （HOMEカテゴリのActivityとして登録するだけで、通常のAndroidと同じ挙動になる）。
 */
class MainActivity : Activity() {

    private lateinit var appGrid: RecyclerView
    private lateinit var permissionBanner: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        appGrid = findViewById(R.id.appGrid)
        permissionBanner = findViewById(R.id.permissionBanner)

        val columns = 5
        appGrid.layoutManager = GridLayoutManager(this, columns)
        appGrid.adapter = AppAdapter(loadInstalledApps()) { app ->
            launchApp(app)
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndMaybeStartOverlay()
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
            component = android.content.ComponentName(app.packageName, app.activityName)
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
            permissionBanner.visibility = android.view.View.VISIBLE
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
            permissionBanner.visibility = android.view.View.VISIBLE
            permissionBanner.text = getString(R.string.grant_accessibility_permission)
            permissionBanner.setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            return
        }

        permissionBanner.visibility = android.view.View.GONE
        StatusBarService.start(this)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }
}
