package se.oxelv.mintracker.data

enum class ActivityKind(val label: String) {
    RUN("Löpning"), WALK("Promenad"), DOG_RUN("Löpning med hund"), DOG_WALK("Promenad med hund");

    val requiresDog: Boolean get() = this == DOG_RUN || this == DOG_WALK

    companion object {
        fun fromStored(value: String): ActivityKind? = when (value) {
            "Löpning", "RUN" -> RUN
            "Promenad", "WALK" -> WALK
            "DOG_RUN" -> DOG_RUN
            "DOG_WALK" -> DOG_WALK
            else -> null
        }

        fun canonical(value: String): String = fromStored(value)?.name ?: value
        fun label(value: String): String = fromStored(value)?.label ?: value
    }
}
