package ai.kilocode.client.ui

/**
 * Notification group ids. Must match the <notificationGroup id="..."/> registrations in
 * kilo.jetbrains.frontend.xml — a mismatch silently drops the group and its notifications.
 */
object CostrictBrand {
    const val NOTIFICATION_GROUP = "Costrict"
    const val CODE_REVIEW_NOTIFICATION_GROUP = "Costrict.CodeReview"
}
