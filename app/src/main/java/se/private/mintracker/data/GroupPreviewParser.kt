package se.oxelv.mintracker.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

object GroupPreviewParser {
    fun parse(json: String, normalizedCode: String): GroupPreview? {
        val raw = Json.parseToJsonElement(json)
        val row = when (raw) {
            is JsonObject -> raw
            is JsonArray -> raw.firstOrNull() as? JsonObject
            else -> null
        } ?: return null
        fun string(vararg keys: String): String? = keys.firstNotNullOfOrNull { row[it]?.jsonPrimitive?.contentOrNull }
        val name = string("group_name", "name") ?: error("Gruppens namn saknas i serverns svar.")
        val type = string("group_type") ?: error("Grupptyp saknas i serverns svar.")
        val memberCount = row["member_count"]?.jsonPrimitive?.intOrNull ?: error("Antal medlemmar saknas i serverns svar.")
        val alreadyMember = row["already_member"]?.jsonPrimitive?.booleanOrNull ?: error("Medlemsstatus saknas i serverns svar.")
        return GroupPreview(string("group_id", "id"), name, type, memberCount, alreadyMember, normalizedCode)
    }
}
