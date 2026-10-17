package app.lernet.engine.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FailoverReportTest {
    @Test
    fun repeatedPassUpdatesRowsInsteadOfGrowingTheList() {
        val first = FailoverReport.merge(null, "Европа", listOf(FailoverAttempt("A", "нет ответа", 1), FailoverAttempt("B", "нет ответа", 2)), "1 мин")
        val second = FailoverReport.merge(first, "Европа", listOf(FailoverAttempt("A", "TLS: время истекло", 5)), "2 мин")
        assertThat(second.attempts.map { it.name }).containsExactly("B", "A").inOrder()
        assertThat(second.attempts.last().reason).isEqualTo("TLS: время истекло")
        assertThat(second.nextRetry).isEqualTo("2 мин")
    }

    @Test
    fun anotherFolderStartsAFreshReport() {
        val first = FailoverReport.merge(null, "Европа", listOf(FailoverAttempt("A", "нет ответа", 1)), null)
        val other = FailoverReport.merge(first, "Работа", listOf(FailoverAttempt("C", "нет ответа", 2)), null)
        assertThat(other.attempts.map { it.name }).containsExactly("C")
    }
}
