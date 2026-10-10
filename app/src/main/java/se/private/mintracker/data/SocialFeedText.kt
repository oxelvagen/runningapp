package se.oxelv.mintracker.data

object SocialFeedText {
    fun description(event: SocialEvent): String = when (event.eventType) {
        "CHALLENGE_COMPLETED" -> "Klarade utmaningen ${event.challengeTitle ?: "Utmaning"}"
        "PERSONAL_RECORD" -> "Nytt personligt rekord: längsta löppasset"
        else -> {
            val activity = when (ActivityKind.fromStored(event.activityType.orEmpty())) {
                ActivityKind.RUN -> "Genomförde ett löppass"
                ActivityKind.WALK -> "Tog en promenad"
                ActivityKind.DOG_RUN -> "Sprang med hund"
                ActivityKind.DOG_WALK -> "Tog en hundpromenad"
                null -> "Genomförde ett pass"
            }
            activity + (event.dogName?.let { " · $it" } ?: "")
        }
    }
}
