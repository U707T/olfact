package cn.olfact.app.data

import cn.olfact.core.api.BrowserSurface

/** 当前正在显示的渲染器（AI 总结/翻译要从它身上取页面内容）。 */
object ActiveSurface {

    @Volatile
    var current: BrowserSurface? = null
        private set

    fun attach(surface: BrowserSurface) {
        current = surface
    }

    fun clear(surface: BrowserSurface) {
        if (current === surface) current = null
    }
}
