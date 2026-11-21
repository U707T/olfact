package cn.olfact.app.ui.auditui

import cn.olfact.app.ui.ai.AiRequestHandle
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRequestHandleTest {

    @Test
    fun cancelStopsAnAttachedRequest() {
        val handle = AiRequestHandle()
        val job = Job()
        handle.attach(job)

        handle.cancel()

        assertTrue(job.isCancelled)
        assertFalse(handle.isActive)
    }

    @Test
    fun cancelBeforeRequestAttachmentStopsLateRequest() {
        val handle = AiRequestHandle()
        handle.cancel()
        val job = Job()

        handle.attach(job)

        assertTrue(job.isCancelled)
    }

    @Test
    fun completedRequestIsNotCancelledWhenAttachedLate() {
        val handle = AiRequestHandle()
        handle.complete()
        val job = Job()

        handle.attach(job)

        assertTrue(job.isActive)
        job.cancel()
    }
}
