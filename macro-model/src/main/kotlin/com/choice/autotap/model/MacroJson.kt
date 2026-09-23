package com.choice.autotap.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** File format for exported macros. */
@Serializable
data class MacroBundle(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val macros: List<Macro>,
) {
    companion object {
        const val FORMAT = "choice-auto-tap"
        const val VERSION = 1
    }
}

object MacroJson {

    val json: Json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encodeMacro(macro: Macro): String = json.encodeToString(Macro.serializer(), macro)

    fun decodeMacro(text: String): Macro = json.decodeFromString(Macro.serializer(), text)

    fun export(macros: List<Macro>): String = json.encodeToString(MacroBundle.serializer(), MacroBundle(macros = macros))

    /**
     * Parses an exported file. Accepts either a [MacroBundle] or a single bare [Macro].
     * Imported macros get id = 0 (so the database assigns new ids) and are sanitized.
     */
    fun import(text: String): List<Macro> {
        val element = json.parseToJsonElement(text)
        val macros = if (element is kotlinx.serialization.json.JsonObject && "macros" in element) {
            json.decodeFromJsonElement(MacroBundle.serializer(), element).macros
        } else {
            listOf(json.decodeFromJsonElement(Macro.serializer(), element))
        }
        return macros.map { MacroEditing.sanitize(it.copy(id = 0)) }
    }
}
