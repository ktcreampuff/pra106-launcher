package com.shikaketegami.pra106launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent

/**
 * ランチャー唯一のユーザー補助サービス（ユーザーがオンにするのはこれ1つだけ）。
 * 役割：
 *  1. 戻る・最近のアプリ（GlobalAction）の実行
 *  2. 前面アプリの履歴追跡（「直前アプリ」ボタン用）
 *  3. AutoSkipEngine へのイベント受け渡し
 */
class LauncherAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile private var instance: LauncherAccessibilityService? = null

        val isRunning: Boolean get() = instance != null

        fun performBack(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
        fun openRecents(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false

        /** 直前に使っていたアプリへ切り替える。成功したら true */
        fun launchPreviousApp(): Boolean = instance?.switchToPrevious() ?: false

        /** 前面に出ている通常アプリのパッケージ名（ホーム・SystemUI・自分なら null） */
        fun currentAppPackage(): String? = instance?.currentApp()
    }

    private fun currentApp(): String? {
        val p = foregroundPkg ?: return null
        return if (p in ignoredPkgs || isSystemUiLike(p)) null else p
    }

    private var autoSkip: AutoSkipEngine? = null

    // 前面アプリ（ホーム等も含む）と、通常アプリだけの使用履歴（新しい順）
    private var foregroundPkg: String? = null
    private val history = ArrayDeque<String>()
    private var ignoredPkgs: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Prefs.load(this)
        autoSkip = AutoSkipEngine(this)
        ignoredPkgs = buildIgnoredPackages()
        DiagLog.append(this, "A11Y", "接続（ON）")
    }

    override fun onDestroy() {
        // OSがユーザー補助設定を自動でOFFに戻した場合も、ここが呼ばれて切断される
        DiagLog.append(this, "A11Y", "切断（OFFに戻った/サービス終了）")
        if (instance == this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        DiagLog.append(this, "A11Y", "onInterrupt（OSからの中断通知）")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        DiagLog.append(this, "A11Y", "onUnbind（バインド解除＝OFFにされた可能性）")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            trackForeground(event.packageName?.toString(), event.className?.toString())
        }
        if (Prefs.autoSkipEnabled) autoSkip?.onEvent(event)
    }

    private fun trackForeground(pkg: String?, cls: String?) {
        if (pkg.isNullOrEmpty()) return
        // 自作バー（オーバーレイ）やトーストなど、自分のActivity以外の窓は前面扱いしない
        if (pkg == packageName && cls?.endsWith("MainActivity") != true) return
        foregroundPkg = pkg
        if (pkg in ignoredPkgs || isSystemUiLike(pkg)) return
        history.remove(pkg)
        history.addFirst(pkg)
        while (history.size > 10) history.removeLast()
    }

    private fun switchToPrevious(): Boolean {
        // 前面が履歴の先頭なら2番目へ、ホーム等にいるなら先頭（最後に使ったアプリ）へ
        val target = if (foregroundPkg == history.firstOrNull()) history.getOrNull(1)
                     else history.firstOrNull()
        target ?: return false
        val intent = packageManager.getLaunchIntentForPackage(target) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try { startActivity(intent); true } catch (e: Exception) { false }
    }

    /** 履歴に入れないもの：自分、ホームアプリ、SystemUI、キーボード */
    private fun buildIgnoredPackages(): Set<String> {
        val set = mutableSetOf(packageName, "com.android.systemui", "android")
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(home, PackageManager.MATCH_ALL)
            .forEach { set += it.activityInfo.packageName }
        val ime = Intent("android.view.InputMethod")
        packageManager.queryIntentServices(ime, 0).forEach { set += it.serviceInfo.packageName }
        // PRA106のSystemUI（HCTSystemUIMsm13）はパッケージ名未確認のため、名前で判定して除外
        return set
    }

    private fun isSystemUiLike(pkg: String) = pkg.contains("systemui", ignoreCase = true)
}
