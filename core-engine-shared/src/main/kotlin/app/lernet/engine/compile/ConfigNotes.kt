package app.lernet.engine.compile

/**
 * Turns the assembler's technical notes into plain Russian lines for the screen.
 * Only notes where LerNET changed or dropped something the user's config asked for
 * are shown; LerNET's own additions (local/direct resolvers, crumbs) stay in the journal.
 */
object ConfigNotes {
    const val HEADER = "LerNET изменил эти настройки DNS из конфига:"

    private val finalRemapped = Regex("""^dns\.final remapped (.+)→(.+)$""")
    private val proxiedDropped = Regex("""^dns dropped proxied resolver tag=(\S+)""")
    private val resolverRemapped = Regex("""^dns remapped domain_resolver (\S+)→(\S+) tag=(\S+)""")
    private val resolverDropped = Regex("""^dns dropped domain_resolver (\S+) tag=(\S+)""")

    fun userVisible(notes: List<String>): List<String> = notes.mapNotNull(::translate).distinct()

    fun translate(note: String): String? {
        finalRemapped.find(note)?.let { m ->
            val (from, to) = m.destructured
            return "основной DNS-сервер «$from» в конфиге не найден — используется «$to»"
        }
        proxiedDropped.find(note)?.let { m ->
            val tags = m.groupValues[1].split(',').filter { it.isNotBlank() }.joinToString(", ")
            return "DNS-серверы $tags не используются: выбран DNS сети устройства. Чтобы их включить, выберите DNS профиля"
        }
        resolverRemapped.find(note)?.let { m ->
            val (from, to, tag) = m.destructured
            return "у DNS-сервера «$tag» резолвер «$from» не найден — используется «$to»"
        }
        resolverDropped.find(note)?.let { m ->
            val (from, tag) = m.destructured
            return "у DNS-сервера «$tag» резолвер «$from» не найден или указывает сам на себя — убран"
        }
        return null
    }
}
