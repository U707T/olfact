package android.view
class MotionEvent(val actionMasked: Int, val x: Float = 0f, val y: Float = 0f) {
    companion object { const val ACTION_DOWN = 0 }
}

/** Minimal View token; custom-view session tests only need identity. */
open class View
