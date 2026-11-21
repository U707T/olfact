package cn.olfact.app.ui.ai

import kotlinx.coroutines.Job

/** Holds one UI-owned request and stops it when the result panel is cancelled or replaced. */
internal class AiRequestHandle {
    private var job: Job? = null
    var isActive: Boolean = true
        private set
    private var isCancelled = false

    fun attach(job: Job) {
        if (isCancelled) {
            job.cancel()
            return
        }
        if (job.isActive) this.job = job
    }

    fun cancel() {
        if (!isActive) return
        isActive = false
        isCancelled = true
        job?.cancel()
        job = null
    }

    fun complete() {
        isActive = false
        job = null
    }
}
