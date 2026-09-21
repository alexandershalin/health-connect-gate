package com.bishop.healthconnectgate

/** Which synchronisation line the status screen shows. */
internal enum class SyncLine { IDLE, RUNNING, INTERRUPTED, FAILED }

/**
 * What the status screen shows, decided without any Android class so that it can be tested.
 * The last problem is shown only while the state says something went wrong: during a running synchronisation, or after a successful one,
 * an old "server cannot be reached" would be a stale message.
 */
internal data class StatusModel(
    val line: SyncLine,
    val hasProgress: Boolean,
    val errorText: String?,
    val errorAtMs: Long,
    val syncEnabled: Boolean
)

internal object StatusModelBuilder {
    fun build(status: SyncStatus): StatusModel {
        val line = when (status.state) {
            SyncState.RUNNING -> SyncLine.RUNNING
            SyncState.INTERRUPTED -> SyncLine.INTERRUPTED
            SyncState.FAILED -> SyncLine.FAILED
            SyncState.IDLE -> SyncLine.IDLE
        }
        val failed = status.state == SyncState.FAILED || status.state == SyncState.INTERRUPTED
        return StatusModel(
            line = line,
            hasProgress = status.monthsTotal > 0,
            errorText = status.lastError.takeIf { failed && it.isNotBlank() },
            errorAtMs = status.lastErrorMs,
            syncEnabled = status.state != SyncState.RUNNING
        )
    }
}
