package cn.olfact.download

enum class DownloadStatus(val wireName: String) {
    QUEUED("queued"),
    RUNNING("running"),
    PAUSED("paused"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELED("canceled");

    val isTerminal: Boolean get() = this == COMPLETED || this == CANCELED
    val isActive: Boolean get() = this == QUEUED || this == RUNNING || this == PAUSED

    companion object {
        fun fromWire(name: String?): DownloadStatus =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) } ?: QUEUED
    }
}
