package cn.olfact.web.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class SiteConfigTest {
    @Test fun `site mobile override wins over global desktop mode`() {
        assertEquals(UaMode.MOBILE, SiteConfig("example.com", desktopMode = false)
            .mergeInto(BrowserConfig(uaMode = UaMode.DESKTOP)).uaMode)
    }

    @Test fun `site custom UA selects custom mode`() {
        val config = SiteConfig("example.com", customUa = "site-agent").mergeInto(BrowserConfig())
        assertEquals(UaMode.CUSTOM, config.uaMode)
        assertEquals("site-agent", config.customUa)
    }
}
