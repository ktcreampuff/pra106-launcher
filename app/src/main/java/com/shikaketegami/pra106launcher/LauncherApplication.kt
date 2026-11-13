package com.shikaketegami.pra106launcher

import android.app.Application

/** プロセス起動時にクラッシュロガーを仕込むためだけのApplication */
class LauncherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagLog.installCrashLogger(this)
        DiagLog.append(this, "APP", "プロセス起動")
    }
}
