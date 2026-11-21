package cn.olfact.app.data

import cn.olfact.core.api.DohConfig
import cn.olfact.core.api.DohMode
import cn.olfact.web.doh.DohManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real adapter and manager, with background IO deliberately left unscheduled. */
class DohAdapterIntegrationTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val manager = DohManagerImpl(android.content.Context(), scope)
    private val settings = MemorySettings()
    private val adapter = DohAdapter(manager, settings)

    @After fun cleanup() { manager.stop(); scope.coroutineContext[Job]!!.cancel() }

    private fun runtimeConfig(): cn.olfact.web.doh.DohConfig {
        val field = DohManagerImpl::class.java.getDeclaredField("config")
        field.isAccessible = true
        return field.get(manager) as cn.olfact.web.doh.DohConfig
    }

    @Test fun `preset selection pins preset without forcing custom-only pool`() {
        settings.set(K.DOH_UPSTREAM, "https://doh.pub/dns-query")
        adapter.startFromSettings()
        val cfg = runtimeConfig()
        assertEquals("tencent", cfg.preferredUpstreamId)
        assertTrue(cfg.customUpstreams.isEmpty())
        assertFalse(cfg.customOnly)
    }

    @Test fun `local HTTP custom setting reaches manager as custom-only normalized endpoint`() {
        settings.set(K.DOH_UPSTREAM, "http://[::1]:8080")
        adapter.startFromSettings()
        val cfg = runtimeConfig()
        assertNull(cfg.preferredUpstreamId)
        assertTrue(cfg.customOnly)
        assertEquals(2, cfg.customUpstreams.size)
        assertTrue(cfg.customUpstreams.all { it.url == "http://[::1]:8080/dns-query" })
    }

    @Test fun `automatic setting keeps full built-in pool`() {
        settings.set(K.DOH_UPSTREAM, "auto")
        adapter.startFromSettings()
        assertFalse(runtimeConfig().customOnly)
        assertTrue(runtimeConfig().customUpstreams.isEmpty())
        assertNull(runtimeConfig().preferredUpstreamId)
    }

    @Test fun `public controller start follows same preset and custom routing decisions`() {
        adapter.start(DohConfig(true, DohMode.INCLUDE, listOf("https://dns.google/dns-query"), listOf("+.example.com")))
        assertEquals("google", runtimeConfig().preferredUpstreamId)
        assertFalse(runtimeConfig().customOnly)
        assertTrue(manager.rules().useDoh("example.com"))
        assertFalse(manager.rules().useDoh("other.example"))
        adapter.start(DohConfig(true, DohMode.SMART, listOf("https://dns.example.com"), emptyList()))
        assertTrue(runtimeConfig().customOnly)
        assertTrue(runtimeConfig().customUpstreams.all { it.url == "https://dns.example.com/dns-query" })
    }

    @Test fun `mode and rule changes immediately update manager and settings`() {
        adapter.applyRules("+.example.com", DohMode.INCLUDE)
        assertEquals("include", settings.get(K.DOH_MODE))
        assertEquals("+.example.com", settings.get(K.DOH_RULES))
        assertTrue(manager.rules().useDoh("cdn.example.com"))
        assertFalse(manager.rules().useDoh("unlisted.example"))
        adapter.applyMode(DohMode.EXCLUDE)
        assertFalse(manager.rules().useDoh("cdn.example.com"))
        assertTrue(manager.rules().useDoh("unlisted.example"))
        assertEquals("exclude", settings.get(K.DOH_MODE))
    }

    @Test fun `config enabled state tracks actual manager stop`() {
        adapter.start(DohConfig(true, DohMode.SMART, listOf("auto"), emptyList()))
        assertTrue(adapter.config().enabled)
        adapter.stop()
        assertFalse(adapter.config().enabled)
        assertNull(manager.appDns())
    }
}
