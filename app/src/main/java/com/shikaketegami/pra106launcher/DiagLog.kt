package com.shikaketegami.pra106launcher

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * ADB/rootが使えない実機で「ユーザー補助がONにしても戻る」等の現象を調べるための、
 * アプリ内だけで完結する簡易ログ。ユーザー補助サービスの接続・切断・クラッシュを
 * タイムスタンプ付きで記録し、設定メニューの「動作ログ」から見られるようにする。
 *
 * - サービスが onDestroy() を伴ってOFFになった場合 → OSが明示的に無効化した
 *   （応答性チェック等で自動OFFにされた可能性が高い）
 * - onDestroy() のログが無いまま次回起動時にAPP_STARTが現れる場合 → アプリの
 *   プロセスごと強制終了された（電力管理によるプロセスキルの可能性が高い）
 * という見分けができるようにする狙い。
 */
object DiagLog {
    private const val FILE_NAME = "diag_log.txt"
    private const val MAX_CHARS = 20_000
    private val fmt = SimpleDateFormat("MM/dd HH:mm:ss.SSS", Locale.JAPAN)

    @Synchronized
    fun append(context: Context, tag: String, message: String) {
        try {
            val line = "${fmt.format(System.currentTimeMillis())} [$tag] $message\n"
            val file = File(context.filesDir, FILE_NAME)
            val existing = if (file.exists()) file.readText() else ""
            var combined = existing + line
            if (combined.length > MAX_CHARS) {
                combined = combined.substring(combined.length - MAX_CHARS)
            }
            file.writeText(combined)
        } catch (e: Throwable) {
            // ログ取り自体の失敗で本体機能に影響を出さない
        }
    }

    fun read(context: Context): String {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.readText() else "(まだ記録がありません)"
        } catch (e: Throwable) {
            "(ログの読み込みに失敗しました: ${e.javaClass.simpleName})"
        }
    }

    fun clear(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (e: Throwable) { }
    }

    /** アプリ全体の未捕捉例外を記録しつつ、元のハンドラにも渡す（クラッシュ動作自体は変えない） */
    fun installCrashLogger(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = throwable.stackTraceToString().take(4000)
                append(app, "CRASH", "thread=${thread.name}\n$trace")
            } catch (e: Throwable) { }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
