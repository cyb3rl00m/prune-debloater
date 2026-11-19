package io.github.cyb3rl00m.prune.core.history

import android.content.Context
import io.github.cyb3rl00m.prune.core.plan.PlannedChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class HistoryEntry(
    val id: String,
    val change: PlannedChange,
    /** State before the change: "enabled" | "disabled" | "removed" | app-op mode | "granted" | "exempt" | setting value. */
    val before: String? = null,
    val after: String? = null,
    /** null = not verified yet (ADB script that has not been checked). */
    val success: Boolean? = null,
    val message: String = "",
    val commands: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val undone: Boolean = false,
    /** id of the entry this one reverts, if it is an undo. */
    val undoOf: String? = null,
)

@Serializable
data class Batch(
    val id: String,
    val title: String,
    val origin: String,
    val timestamp: Long,
    val access: String,
    val entries: List<HistoryEntry>,
    /** True for plans exported as an ADB script: results are checked when the user taps "Check results". */
    val script: Boolean = false,
)

/** State of a package the first time Prune changed it. Used by "Restore everything". */
@Serializable
data class OriginalState(val packageName: String, val label: String, val state: String, val recordedAt: Long)

@Serializable
data class HistoryFile(
    val version: Int = 1,
    val batches: List<Batch> = emptyList(),
    val originals: Map<String, OriginalState> = emptyMap(),
)

/** Modification history, kept as a JSON file in the app's private storage. */
class HistoryStore(context: Context) {

    private val file = File(context.filesDir, "history.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private val lock = Mutex()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<HistoryFile> = _state.asStateFlow()

    private fun load(): HistoryFile = runCatching {
        if (file.exists()) json.decodeFromString(HistoryFile.serializer(), file.readText()) else HistoryFile()
    }.getOrElse {
        // Keep a copy of an unreadable file instead of silently losing the history.
        runCatching { file.copyTo(File(file.parentFile, "history-unreadable-${System.currentTimeMillis()}.json")) }
        HistoryFile()
    }

    suspend fun update(transform: (HistoryFile) -> HistoryFile) = lock.withLock {
        val next = transform(_state.value)
        withContext(Dispatchers.IO) {
            val tmp = File(file.parentFile, "history.json.tmp")
            tmp.writeText(json.encodeToString(HistoryFile.serializer(), next))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
        _state.value = next
    }

    suspend fun addBatch(batch: Batch, originals: Map<String, OriginalState>) = update { h ->
        h.copy(
            batches = listOf(batch) + h.batches,
            originals = originals.filterKeys { it !in h.originals } + h.originals,
        )
    }

    suspend fun replaceBatch(batch: Batch) = update { h ->
        h.copy(batches = h.batches.map { if (it.id == batch.id) batch else it })
    }

    suspend fun markUndone(entryIds: Set<String>) = update { h ->
        h.copy(batches = h.batches.map { b ->
            b.copy(entries = b.entries.map { if (it.id in entryIds) it.copy(undone = true) else it })
        })
    }

    fun exportJson(): String = Json { prettyPrint = true; encodeDefaults = true }
        .encodeToString(HistoryFile.serializer(), _state.value)
}
