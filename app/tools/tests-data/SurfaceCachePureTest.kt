package cn.olfact.app.ui.browser

/** Runs the shared LRU contract against identity-only View tokens, not Robolectric. */
class SurfaceCachePureTest : SurfaceCacheContract() {
    override fun newView() = android.view.View(android.content.Context())
}
