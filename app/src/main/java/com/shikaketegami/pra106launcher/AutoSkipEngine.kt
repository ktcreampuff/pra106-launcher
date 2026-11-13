package com.shikaketegami.pra106launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * YouTube / Netflix / Prime Video のスキップ系ボタンを自動で押すエンジン。
 * LauncherAccessibilityService から全イベントが渡される（ON/OFF判定は呼び出し側で済み）。
 */
class AutoSkipEngine(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "AutoSkip"
        private const val NETFLIX = "com.netflix.mediaclient"
        private val TARGETS = setOf(
            "com.google.android.youtube",
            NETFLIX,
            "com.amazon.avod.thirdpartyclient"
        )
        private val KEYWORDS = listOf(
            "スキップ", "広告をスキップ", "イントロをスキップ", "オープニングをスキップ",
            "あらすじをスキップ", "skip", "skip ad", "skip ads", "skip intro", "skip recap"
        )
        private val EXCLUDE_WORDS = listOf(
            "次のエピソード", "next episode", "次へ", "設定", "settings", "autoskip", "pra106"
        )
        private const val MAX_NODES = 600
        private const val MAX_TEXT_LEN = 30
        private const val COOLDOWN_MS = 1500L
        private const val SAME_BUTTON_MS = 3000L
        private const val TAP_MS = 50L
        // typeWindowContentChangedは再生中だと1秒に何度も飛んでくるため、
        // クリック後のクールダウンとは別に、画面全体を走査する頻度そのものを間引く。
        // これがないと動画再生中ずっとノードツリーの全走査が走り続け、
        // Android 13以降のユーザー補助サービス応答性チェックに引っかかって
        // サービスが勝手にOFFに戻される（ONにしても戻る不具合の主因と思われる）。
        private const val SCAN_INTERVAL_MS = 300L
    }

    private var lastClickAt = 0L
    private var lastScanAt = 0L
    private val recentKeys = HashMap<String, Long>()

    fun onEvent(event: AccessibilityEvent) {
        try {
            val pkg = event.packageName?.toString() ?: return
            if (pkg !in TARGETS) return
            val now = SystemClock.elapsedRealtime()
            if (lastClickAt != 0L && now - lastClickAt < COOLDOWN_MS) return
            if (now - lastScanAt < SCAN_INTERVAL_MS) return
            lastScanAt = now

            val root = service.rootInActiveWindow ?: return
            if (root.packageName?.toString() !in TARGETS) return
            val rootPkg = root.packageName.toString()
            val isNetflix = rootPkg == NETFLIX

            val dm = service.resources.displayMetrics
            val halfW = dm.widthPixels / 2
            val halfH = dm.heightPixels / 2

            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var count = 0
            while (queue.isNotEmpty() && count < MAX_NODES) {
                val node = queue.poll() ?: break
                count++
                for (i in 0 until node.childCount) {
                    val c = node.getChild(i)
                    if (c != null) queue.add(c)
                }
                if (!node.isVisibleToUser) continue
                if (!isCandidate(node, isNetflix, halfW, halfH)) continue

                val rect = Rect()
                node.getBoundsInScreen(rect)
                val key = "${node.viewIdResourceName}|${node.text}|${rect.flattenToString()}"
                val last = recentKeys[key]
                if (last != null && now - last < SAME_BUTTON_MS) continue

                if (press(node, rect)) {
                    lastClickAt = now
                    recentKeys[key] = now
                    if (recentKeys.size > 50) {
                        recentKeys.entries.removeAll { now - it.value > SAME_BUTTON_MS }
                    }
                    Log.d(TAG, "押下: pkg=$rootPkg id=${node.viewIdResourceName} text=${node.text} desc=${node.contentDescription}")
                    return
                }
            }
        } catch (e: Throwable) {
            // 例外は握りつぶす
        }
    }

    private fun isCandidate(
        node: AccessibilityNodeInfo, isNetflix: Boolean, halfW: Int, halfH: Int
    ): Boolean {
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val id = node.viewIdResourceName?.lowercase() ?: ""

        // 除外（絶対押さない）
        if (id.contains("next") || id.contains("postplay")) return false
        for (s in listOf(text, desc)) {
            if (s == null) continue
            val l = s.lowercase()
            if (EXCLUDE_WORDS.any { l.contains(it) }) return false
        }

        var matched = false
        for (s in listOf(text, desc)) {
            if (s.isNullOrEmpty() || s.length > MAX_TEXT_LEN) continue
            val l = s.lowercase()
            if (KEYWORDS.any { l.contains(it) }) { matched = true; break }
        }
        if (!matched && isNetflix && id.contains("skip")) matched = true
        if (!matched) return false

        if (isNetflix) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.centerX() <= halfW || r.centerY() >= halfH) return false
        }
        return true
    }

    private fun press(node: AccessibilityNodeInfo, rect: Rect): Boolean {
        // ノード自身か最寄りのクリック可能祖先に ACTION_CLICK
        var target: AccessibilityNodeInfo? = node
        var depth = 0
        while (target != null && depth < 8) {
            if (target.isClickable) {
                if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                break
            }
            target = target.parent
            depth++
        }
        // 失敗時は中心を50msタップ
        val path = Path().apply {
            moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_MS))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }
}
