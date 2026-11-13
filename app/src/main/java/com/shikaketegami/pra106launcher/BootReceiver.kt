package com.shikaketegami.pra106launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // LOCKED_BOOT_COMPLETED は BOOT_COMPLETED よりかなり早い段階（他の多くのアプリの
        // 初期化が終わる前）で届くため、できるだけこちらで自分をHOMEとして先に出す。
        // 端末やAndroidバージョンによっては届かない/早すぎて失敗することもあるため、
        // 保険として通常のBOOT_COMPLETEDでも同じ処理を行う（二重に届いても実害はない）。
        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_BOOT_COMPLETED) {
            DiagLog.append(context, "BOOT", "受信: ${intent.action}")
            if (intent.action == Intent.ACTION_BOOT_COMPLETED && Settings.canDrawOverlays(context)) {
                StatusBarService.start(context)
            }
            // 起動直後、本体のシステム側が純正ホームを先に表示してしまい、
            // 本アプリ（ホーム）に切り替わるまで一瞬純正ホームが見える現象への対策。
            // できるだけ早く自分をHOMEとして起動要求することで、切り替わりまでの表示時間を
            // 短くする（完全に消せるかは実機の起動順序次第。本体が起動シーケンスの中で
            // 純正ホームを直接指定して起動している場合は、アプリ側からは防ぎきれない）。
            try {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    setClass(context, MainActivity::class.java)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(homeIntent)
            } catch (e: Exception) {
                DiagLog.append(context, "BOOT", "起動要求に失敗: ${e.javaClass.simpleName}")
                // 端末によっては起動直後にActivity起動が許可されない場合があるため、
                // 失敗しても無視する（通常のHOME解決に任せる）
            }
        }
    }
}
