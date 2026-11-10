package com.shikaketegami.pra106launcher

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * オーバーレイの「戻る」ボタンから実際の戻る操作を行うためのユーザー補助サービス。
 * root権限なしで、どのアプリを表示中でも戻る操作をエミュレートできる。
 * ユーザーが「設定 > ユーザー補助」で手動でオンにする必要がある（Android標準の仕組み）。
 */
class BackButtonAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: BackButtonAccessibilityService? = null

        fun performBack() {
            instance?.performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 画面内容の監視は行わない（戻る操作の実行のみに使用）
    }

    override fun onInterrupt() {
        // 何もしない
    }
}
