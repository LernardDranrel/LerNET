package app.lernet.engine.text

import org.junit.Assert.assertEquals
import org.junit.Test

class UserTextTest {
    @Test fun `raw kotlin and java messages never reach the screen`() {
        assertEquals("Не удалось проверить канал", UserText.of(IllegalStateException("Required value was null."), "Не удалось проверить канал"))
        assertEquals("Не удалось проверить канал", UserText.of(NullPointerException(), "Не удалось проверить канал"))
        assertEquals("Время ожидания истекло", UserText.of(java.net.SocketTimeoutException("connect timed out"), "x"))
        assertEquals("Адрес сервера не найден", UserText.of(RuntimeException("wrap", java.net.UnknownHostException("a.b")), "x"))
    }

    @Test fun `our own russian messages pass through`() {
        assertEquals("Дождись завершения переключения", UserText.of(IllegalStateException("Дождись завершения переключения"), "x"))
        assertEquals("Импорт не выполнен: файл не найден", UserText.withReason("Импорт не выполнен", java.io.FileNotFoundException("C:\\a.json")))
        assertEquals("Импорт не выполнен.", UserText.withReason("Импорт не выполнен", IllegalArgumentException("Unexpected JSON token at offset 3")))
        assertEquals("сервер не отвечает", UserText.clean("java.lang.IllegalStateException: boom", "сервер не отвечает"))
    }

    @Test fun `errors are logged`() {
        val seen = mutableListOf<String>()
        UserText.logger = { context, error -> seen += "$context|${error.message}" }
        try {
            UserText.of(IllegalStateException("Required value was null."), "Не удалось")
        } finally { UserText.logger = { _, _ -> } }
        assertEquals(listOf("Не удалось|Required value was null."), seen)
    }
}
