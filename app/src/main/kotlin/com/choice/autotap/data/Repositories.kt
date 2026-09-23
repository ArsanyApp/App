package com.choice.autotap.data

import com.choice.autotap.model.Macro
import com.choice.autotap.model.MacroJson
import com.choice.autotap.model.MacroStep
import com.choice.autotap.player.RunLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MacroRepository(private val dao: MacroDao) {

    val macros: Flow<List<Macro>> = dao.observeAll().map { list -> list.mapNotNull(::toModel) }

    suspend fun get(id: Long): Macro? = dao.get(id)?.let(::toModel)

    /** Inserts when [Macro.id] is 0, otherwise updates. Returns the macro id. */
    suspend fun save(macro: Macro): Long {
        val now = System.currentTimeMillis()
        return if (macro.id == 0L) {
            val stored = macro.copy(createdAt = now, updatedAt = now)
            dao.insert(MacroEntity(name = stored.name, json = MacroJson.encodeMacro(stored), createdAt = now, updatedAt = now))
        } else {
            val createdAt = if (macro.createdAt == 0L) now else macro.createdAt
            val stored = macro.copy(createdAt = createdAt, updatedAt = now)
            dao.update(MacroEntity(macro.id, stored.name, MacroJson.encodeMacro(stored), createdAt, now))
            macro.id
        }
    }

    suspend fun create(name: String): Long = save(Macro(name = name))

    suspend fun updateSteps(id: Long, steps: List<MacroStep>) {
        val macro = get(id) ?: return
        save(macro.copy(steps = steps))
    }

    suspend fun duplicate(id: Long): Long? {
        val macro = get(id) ?: return null
        return save(macro.copy(id = 0, name = macro.name + " (copy)", steps = macro.steps.map { it.copy(id = MacroStep.newId()) }))
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun exportAll(): String = MacroJson.export(dao.getAll().mapNotNull(::toModel))

    suspend fun export(id: Long): String? = get(id)?.let { MacroJson.export(listOf(it)) }

    /** @return number of imported macros. */
    suspend fun import(json: String): Int {
        val macros = MacroJson.import(json)
        macros.forEach { save(it) }
        return macros.size
    }

    private fun toModel(e: MacroEntity): Macro? =
        runCatching { MacroJson.decodeMacro(e.json).copy(id = e.id, name = e.name, createdAt = e.createdAt, updatedAt = e.updatedAt) }
            .getOrNull()
}

class RunLogRepository(private val dao: RunLogDao) {

    val recent: Flow<List<RunLog>> = dao.observeRecent().map { list ->
        list.mapNotNull { runCatching { MacroJson.json.decodeFromString(RunLog.serializer(), it.json) }.getOrNull() }
    }

    suspend fun save(log: RunLog) {
        dao.insert(
            RunLogEntity(
                macroId = log.macroId,
                macroName = log.macroName,
                startedAt = log.startedAt,
                endedAt = log.endedAt,
                endReason = log.endReason.name,
                json = MacroJson.json.encodeToString(RunLog.serializer(), log),
            ),
        )
    }

    suspend fun clear() = dao.clear()
}

class SnippetRepository(private val dao: SnippetDao) {
    val snippets: Flow<List<SnippetEntity>> = dao.observeAll()

    suspend fun save(snippet: SnippetEntity) {
        if (snippet.id == 0L) dao.insert(snippet) else dao.update(snippet)
    }

    suspend fun delete(id: Long) = dao.delete(id)
}
