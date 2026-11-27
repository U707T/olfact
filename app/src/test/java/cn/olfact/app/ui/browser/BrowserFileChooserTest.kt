package cn.olfact.app.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserFileChooserTest {
    @Test fun `second request cancels old callback and is rejected until old result completes`() {
        val gate = SingleFlightFileChooser<List<String>>()
        var oldResult: List<String>? = listOf("unset")
        var newResult: List<String>? = listOf("unset")

        assertTrue(gate.begin { oldResult = it })
        assertFalse(gate.begin { newResult = it })
        assertEquals(null, oldResult)
        assertEquals(null, newResult)
        assertTrue(gate.hasOutstandingLaunch)

        // A stale first-launch result is discarded because both page callbacks were completed null.
        gate.complete(listOf("content://provider/old"))
        assertEquals(null, oldResult)
        assertEquals(null, newResult)
        assertFalse(gate.hasOutstandingLaunch)

        assertTrue(gate.begin { newResult = it })
        gate.complete(listOf("content://provider/new"))
        assertEquals(listOf("content://provider/new"), newResult)
    }

    @Test fun `activity pause for system picker keeps callback alive until result`() {
        val gate = SingleFlightFileChooser<List<String>>()
        var result: List<String>? = null
        assertTrue(gate.begin { result = it })

        gate.onActivityPause()
        assertTrue(gate.hasOutstandingLaunch)
        gate.complete(listOf("content://provider/selected"))

        assertEquals(listOf("content://provider/selected"), result)
        assertFalse(gate.hasOutstandingLaunch)
    }

    @Test fun `view teardown cancels callback and late picker result is discarded`() {
        val gate = SingleFlightFileChooser<List<String>>()
        var result: List<String>? = listOf("unset")
        assertTrue(gate.begin { result = it })

        gate.cancelPending()
        gate.complete(listOf("content://provider/late"))
        assertEquals(null, result)
        assertFalse(gate.hasOutstandingLaunch)
    }

    @Test fun `chooser URI validation requires external content read grant`() {
        assertTrue(chooserUriAllowed("content", "documents.example", "cn.olfact.app.fileprovider", true))
        assertFalse(chooserUriAllowed("content", "cn.olfact.app.fileprovider", "cn.olfact.app.fileprovider", true))
        assertFalse(chooserUriAllowed("content", "cn.olfact.app.fileprovider", "CN.OLFACT.APP.FILEPROVIDER", true))
        assertFalse(chooserUriAllowed("file", "documents.example", "cn.olfact.app.fileprovider", true))
        assertFalse(chooserUriAllowed("content", "documents.example", "cn.olfact.app.fileprovider", false))
    }

    @Test fun `extension accept types normalize and unknown types fall back`() {
        assertEquals(listOf("application/pdf", "image/jpeg"), browserAcceptMimeTypes(listOf(".pdf,.jpg")))
        assertEquals(listOf("image/*", "text/plain"), browserAcceptMimeTypes(listOf("image/*", "text/plain")))
        assertEquals(listOf("*/*"), browserAcceptMimeTypes(listOf(".unknown")))
    }
}
