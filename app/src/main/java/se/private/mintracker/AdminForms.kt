package se.oxelv.mintracker

import kotlinx.serialization.json.*

internal data class AdminField(val key: String, val label: String, val kind: String = "text", val options: List<String> = emptyList())
internal val npcPersonalities = listOf("CASUAL_RUNNER", "CONSISTENT_RUNNER", "WEEKEND_RUNNER", "MORNING_RUNNER", "EVENING_RUNNER", "COMPETITIVE_RUNNER", "BEGINNER_IMPROVER", "INCONSISTENT_RUNNER", "DOG_OWNER", "WALKER")
internal val npcTypes = listOf("RUN", "WALK", "DOG_RUN", "DOG_WALK")
internal val challengeTemplates = listOf("TOTAL_DISTANCE", "TOTAL_ACTIVITIES", "LONGEST_ACTIVITY", "FASTEST_5K", "WEEKEND_DISTANCE", "DOG_WALK_DISTANCE", "MONTHLY_DISTANCE")
internal val userFields = listOf(AdminField("display_name", "Visningsnamn"), AdminField("username", "Användarnamn"), AdminField("bio", "Om mig"), AdminField("is_public", "Offentlig profil", "boolean"))
internal val npcFields = userFields.filter { it.key in listOf("display_name", "username") } + listOf(
    AdminField("enabled", "Aktiverad", "boolean"), AdminField("personality", "Personlighet", "enum", npcPersonalities),
    AdminField("fitness_level", "Grundkondition (1–100)", "number"), AdminField("base_pace_sec_per_km", "Grundtempo, sek/km", "number"),
    AdminField("distance_min_km", "Minsta distans, km", "number"), AdminField("distance_max_km", "Största distans, km", "number"),
    AdminField("runs_per_week_min", "Minst pass/vecka", "number"), AdminField("runs_per_week_max", "Högst pass/vecka", "number"),
    AdminField("activity_multiplier", "Aktivitetsnivå (0–2)", "slider"), AdminField("motivation", "Motivation (0–1)", "number"),
    AdminField("competitiveness", "Tävlingslust (0–1)", "number"), AdminField("consistency", "Kontinuitet (0–1)", "number"),
    AdminField("fatigue_sensitivity", "Trötthetskänslighet (0–2)", "number"), AdminField("training_hour", "Träningstid, timme (0–23)", "number"),
    AdminField("preferred_days", "Veckodagar (1=mån, 7=sön)", "integers"), AdminField("time_zone", "Tidszon"),
    AdminField("activity_types", "Aktivitetstyper", "choices", npcTypes), AdminField("challenge_enabled", "Delta i utmaningar", "boolean"),
    AdminField("notifications_enabled", "Aktivitetsnotiser", "boolean")
)
internal val systemFields = listOf(
    AdminField("npc_system_enabled", "NPC-system", "boolean"), AdminField("npc_global_activity_multiplier", "Global aktivitet (0–2)", "slider"),
    AdminField("npc_generate_activities", "Skapa aktiviteter", "boolean"), AdminField("npc_challenges_enabled", "NPC deltar i utmaningar", "boolean"),
    AdminField("npc_notifications_enabled", "NPC-notiser", "boolean"), AdminField("npc_max_daily_activities", "Högst NPC-pass/dygn (UTC)", "number"),
    AdminField("npc_simulation_version", "Simuleringsversion", "number"), AdminField("system_challenges_enabled", "Systemutmaningar", "boolean"),
    AdminField("challenge_min_days", "Minst dagar mellan inbjudningar", "number"), AdminField("challenge_max_active", "Högst samtidiga systemutmaningar", "number"),
    AdminField("challenge_chance_per_week", "Chans per vecka (0–1)", "number"), AdminField("challenge_duration_days", "Standardperiod, dagar", "number"),
    AdminField("challenge_min_npcs", "Minst NPC-deltagare", "number"), AdminField("challenge_max_npcs", "Högst NPC-deltagare", "number"),
    AdminField("challenge_difficulty", "Svårighetsfaktor (0,5–1,5)", "number"), AdminField("matchmaking_range", "Matchningsintervall (0,1–2)", "number"),
    AdminField("allowed_templates", "Tillåtna utmaningar", "choices", challengeTemplates),
    AdminField("maintenance_mode", "Underhållsläge", "boolean"), AdminField("registration_enabled", "Registrering öppen", "boolean"),
    AdminField("challenges_enabled", "Utmaningar aktiva", "boolean"), AdminField("minimum_app_version", "Minsta appversion (versionCode)", "number"),
    AdminField("message_of_the_day", "Dagens meddelande")
)

/** Explicit field types prevent locale-dependent or accidental string-only updates. */
internal fun adminFormPayload(fields: List<AdminField>, values: Map<String, String>): JsonObject = buildJsonObject {
    fields.forEach { field ->
        val value = values[field.key].orEmpty()
        put(field.key, when (field.kind) {
            "number", "slider" -> JsonPrimitive(value.replace(',', '.').toBigDecimalOrNull()
                ?: throw IllegalArgumentException("${field.label}: ange ett tal."))
            "boolean" -> JsonPrimitive(value.toBooleanStrict())
            "integers" -> JsonArray(value.split(',').filter { it.isNotBlank() }.map {
                JsonPrimitive(it.trim().toIntOrNull() ?: throw IllegalArgumentException("${field.label}: ange heltal."))
            })
            "choices" -> JsonArray(value.split(',').filter { it.isNotBlank() }.map { JsonPrimitive(it.trim()) })
            else -> JsonPrimitive(value.trim())
        })
    }
}
