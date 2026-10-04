package ai.hermes.bots.ui.groups

internal data class GroupSendDraftState(
    val text: String,
    val pendingEventId: String? = null,
    val pendingText: String? = null,
)

data class GroupSendOutcome(
    val eventId: String,
    val text: String,
    val accepted: Boolean,
    val deliveryUnknown: Boolean = false,
)

internal fun groupSendEventId(draft: GroupSendDraftState, text: String, newEventId: String): String =
    if (draft.pendingText == text) draft.pendingEventId ?: newEventId else newEventId

internal fun resolveGroupSendDraft(draft: GroupSendDraftState, outcome: GroupSendOutcome): GroupSendDraftState {
    if (draft.pendingEventId != outcome.eventId) return draft
    if (!outcome.accepted) {
        return if (outcome.deliveryUnknown) draft else draft.copy(pendingEventId = null, pendingText = null)
    }
    return draft.copy(
        text = if (draft.text.trim() == outcome.text) "" else draft.text,
        pendingEventId = null,
        pendingText = null,
    )
}

internal fun unconfirmedGroupSend(
    draft: GroupSendDraftState,
    outcome: GroupSendOutcome?,
    busy: Boolean,
): GroupSendOutcome? {
    val eventId = draft.pendingEventId ?: return null
    val text = draft.pendingText ?: return null
    if (busy) return null
    val current = outcome?.takeIf { it.eventId == eventId }
    if (current != null && (current.accepted || !current.deliveryUnknown)) return null
    return current ?: GroupSendOutcome(eventId, text, accepted = false, deliveryUnknown = true)
}

internal fun canSubmitGroupDraft(text: String, unconfirmed: GroupSendOutcome?): Boolean =
    unconfirmed == null || text.trim() == unconfirmed.text
