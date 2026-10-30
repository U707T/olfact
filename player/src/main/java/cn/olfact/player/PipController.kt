package cn.olfact.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import android.view.View

/**
 * PiP 封装：
 * - API 31+ 用 `setAutoEnterEnabled`，用户按 Home 直接进（无需 onUserLeaveHint）；
 * - API 26~30 退化为手动 `enterPictureInPictureMode(params)`；
 * - 低于 26 / 设备不支持时全部 no-op，调用方不需要判断版本。
 */
class PipController(private val activity: Activity) {

    @Volatile
    private var lastParams: PictureInPictureParams? = null

    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    val isInPip: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity.isInPictureInPictureMode

    fun updateParams(
        aspect: Pair<Int, Int>?,
        sourceRectHint: Rect? = null,
        autoEnter: Boolean = false,
        actions: List<RemoteAction> = emptyList(),
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val builder = PictureInPictureParams.Builder()
        aspect?.let { (w, h) -> runCatching { builder.setAspectRatio(Rational(w, h)) } }
        sourceRectHint?.let(builder::setSourceRectHint)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
            if (actions.isNotEmpty()) builder.setActions(actions)
        }
        val params = builder.build()
        lastParams = params
        runCatching { activity.setPictureInPictureParams(params) }
    }

    /** 从某个 View 计算 source rect（带一点内缩，动画更自然）。 */
    fun rectOf(view: View): Rect? {
        if (!view.isAttachedToWindow) return null
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val rect = Rect(
            location[0], location[1],
            location[0] + view.width, location[1] + view.height,
        )
        return if (rect.isEmpty) null else rect
    }

    fun enter(): Boolean {
        if (!isSupported || isInPip) return false
        val params = lastParams ?: PictureInPictureParams.Builder().build()
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.enterPictureInPictureMode(params)
            } else {
                false
            }
        }.getOrDefault(false)
    }

    fun setAutoEnter(enabled: Boolean, aspect: Pair<Int, Int>? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            updateParams(aspect, autoEnter = enabled)
        }
    }
}
