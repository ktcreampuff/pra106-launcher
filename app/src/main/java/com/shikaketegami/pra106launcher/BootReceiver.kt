package com.shikaketegami.pra106launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (Settings.canDrawOverlays(context)) {
                StatusBarService.start(context)
            }
            // 起動直後、本体のシステム側が純正ホームを先に表示してしまい、
            // 本アプリ（ホーム）に切り替わるまで一瞬純正ホームが見える現象への対策。
            // BOOT_COMPLETED を受け取った時点でできるだけ早く自分をHOMEとして起動要求することで、
            // 切り替わりまでの表示時間を短くする（完全に消せるかは実機の起動順序次第）。
            try {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    setClass(context, MainActivity::class.java)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(homeIntent)
            } catch (e: Exception) {
                // 端末によってはBOOT_COMPLETED直後にActivity起動が許可されない場合があるため、
                // 失敗しても無視する（通常のHOME解決に任せる）
            }
        }
    }
}
