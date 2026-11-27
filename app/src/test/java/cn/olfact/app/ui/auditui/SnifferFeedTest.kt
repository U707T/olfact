package cn.olfact.app.ui.auditui

import cn.olfact.app.ui.player.SnifferFeed
import cn.olfact.core.api.MediaItem
import org.junit.Assert.*
import org.junit.Test

class SnifferFeedTest {
    @Test fun sameMediaIsSeparatedByTabAndPage() {
        val media = "https://cdn.example/v.mp4"
        val page = "https://page.example/video"
        val other = "$page/2"
        fun item(pageUrl: String, token: String) = MediaItem(media, pageUrl = pageUrl, headers = mapOf("Authorization" to token))
        try {
            SnifferFeed.add(91, item(page, "one"))
            SnifferFeed.add(92, item(page, "two"))
            SnifferFeed.add(91, item(other, "three"))
            SnifferFeed.add(91, item(page, "updated"))
            assertEquals(listOf(item(page, "updated")), SnifferFeed.forPage(91, page))
            assertEquals(item(page, "two"), SnifferFeed.find(92, page, media))
            assertEquals(item(other, "three"), SnifferFeed.find(91, other, media))
            assertTrue(SnifferFeed.forPage(91, "").isEmpty())
            SnifferFeed.clearTab(91)
            assertNull(SnifferFeed.find(91, other, media))
            assertEquals(item(page, "two"), SnifferFeed.find(92, page, media))
        } finally {
            SnifferFeed.clearTab(91)
            SnifferFeed.clearTab(92)
        }
    }
}
