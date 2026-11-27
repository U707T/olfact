package android.widget
class Toast {
    fun show() {}
    companion object { const val LENGTH_SHORT = 0; const val LENGTH_LONG = 1; fun makeText(context: android.content.Context, text: String, duration: Int) = Toast() }
}
