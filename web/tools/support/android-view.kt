package android.view
class MotionEvent(val actionMasked: Int, val x: Float = 0f, val y: Float = 0f) {
    companion object { const val ACTION_DOWN = 0 }
}
