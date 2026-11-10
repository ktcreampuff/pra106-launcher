package com.shikaketegami.pra106launcher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * クイック設定（画面上から下ろすパネル）の「純正ホーム」タイル。
 * タップで PRA106 本来のホーム（TH2Launcher2）を開く。
 * タイルはパネルの編集画面から手動で追加する。
 */
class NativeHomeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = findNativeHomeIntent()
        if (intent == null) {
            Toast.makeText(this, R.string.home_native_home_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivityAndCollapse(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.home_native_home_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    /** 自分以外で HOME を受け付ける Activity（＝純正ホーム）を探す */
    private fun findNativeHomeIntent(): Intent? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = packageManager.queryIntentActivities(home, PackageManager.MATCH_ALL)
            .firstOrNull { it.activityInfo.packageName != packageName } ?: return null
        return Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
}
