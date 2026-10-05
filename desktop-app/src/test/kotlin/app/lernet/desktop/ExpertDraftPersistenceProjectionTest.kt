package app.lernet.desktop

import app.lernet.desktop.expert.ExpertDraftResponse
import app.lernet.desktop.expert.ExpertDraftSubmission
import app.lernet.desktop.expert.ExpertUiState
import app.lernet.engine.policy.ExpertRuntimeState
import app.lernet.routing.policy.NetworkPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ExpertDraftPersistenceProjectionTest {
    @Test fun `window action projection cannot acknowledge matching draft with an unresolved write failure`() {
        val policy = NetworkPolicy()
        val before = ExpertUiState(policy)
        val submission = ExpertDraftSubmission.capture(policy, before)
        val runtime = ExpertRuntimeState(saved = policy, draftPersistenceError = "Не удалось записать черновик")
        val afterExport = before.copy(error = projectedRuntimeError(runtime))
        assertFalse(submission.response(afterExport) == ExpertDraftResponse.CONFIRMED)
        assertNull(projectedRuntimeError(runtime.copy(draftPersistenceError = null)))
    }
}
