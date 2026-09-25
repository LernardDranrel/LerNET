package app.lernet.ui.routes

import app.lernet.routing.RuleConditions

/**
 * Dismiss-empty-sheet contract (phone freeze P0):
 *
 * 1. Swipe/back → [RouteEditorIntent.CloseEditor]: always clear [RouteEditorUiState.editingId]
 *    so ModalBottomSheet leaves composition (Hidden sheet + kept editingId ate all touches).
 * 2. Incomplete newly created draft → discard the node and reseed «Иначе».
 *    An existing rule stays in the editor tree when its last value is removed.
 * 3. Done → [RouteEditorIntent.DoneEditor]: gate errors stay in the sheet; editingId kept.
 * 4. Save → reopen the first incomplete rule's sheet; do not paint gate errors on the canvas.
 *
 * Repro: FAB → add rule → open sheet → dismiss without blocks → canvas must pan/tap/Save.
 */
object RuleSheetDismiss {
    fun shouldDiscardDraft(conditions: RuleConditions, isNewDraft: Boolean): Boolean =
        isNewDraft && RuleSheetGate.isIncomplete(conditions)
}
