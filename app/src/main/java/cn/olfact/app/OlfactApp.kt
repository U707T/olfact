package cn.olfact.app

import android.app.Application
import cn.olfact.core.Core

/**
 * 进程入口。只做初始化，不写业务；B5 可在此挂崩溃捕获 / 主题应用。
 */
class OlfactApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Core.init(this)
    }
}
