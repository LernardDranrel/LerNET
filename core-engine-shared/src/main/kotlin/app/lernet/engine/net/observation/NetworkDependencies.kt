package app.lernet.engine.net.observation

/** Passive evidence only. No DNS lookup, network request or attribution by driver name. */
object NetworkDependencies {
    fun localDnsFindings(snapshot: NetworkSnapshot): List<NetworkFinding> {
        val tcp = snapshot.sources.firstOrNull { it.id == "listeners" }
        val udp = snapshot.sources.firstOrNull { it.id == "udp-endpoints" }
        fun readable(source: ObservationSource?) = source != null && source.complete &&
            source.state in setOf(SourceState.AVAILABLE, SourceState.EMPTY)
        return snapshot.adapters.filter { it.up }.mapNotNull { adapter ->
            val local = adapter.dns.filter { it == "::1" || it == "127.0.0.1" }
            if (local.isEmpty()) return@mapNotNull null
            fun accepts(address: String, target: String): Boolean = if (target == "::1")
                address in setOf("::", "::1") else address in setOf("0.0.0.0", "127.0.0.1", "::ffff:127.0.0.1")
            val missing = local.filter { target ->
                snapshot.listeners.none { it.port == 53 && accepts(it.address, target) } &&
                    udp?.rows.orEmpty().none { it.fields["LocalPort"] == "53" && accepts(it.fields["LocalAddress"].orEmpty(), target) }
            }
            if (missing.isEmpty()) return@mapNotNull null
            val complete = readable(tcp) && readable(udp)
            NetworkFinding("local-dns-${adapter.id}",
                if (complete) "Локальный DNS без видимого обработчика: ${adapter.name}" else "Локальный DNS требует проверки: ${adapter.name}",
                "DNS этого адаптера направлен на само устройство. Для такого адреса обычно нужна работающая локальная программа.",
                if (complete) FindingKind.POTENTIAL_CONFLICT else FindingKind.INSUFFICIENT_DATA,
                listOf("dns", "adapters", "listeners", "udp-endpoints"), missing,
                reason = if (complete) "У включённого адаптера DNS ${missing.joinToString()}. В прочитанных TCP- и UDP-портах не найден подходящий обработчик на порту 53." else
                    "DNS ${missing.joinToString()} указывает на устройство, но данные TCP/UDP недоступны или неполны. Отсутствие обработчика подтвердить нельзя.",
                impact = "После остановки программы, обслуживающей этот DNS, имена сайтов могут перестать определяться. Фильтры Windows могут перенаправлять DNS без обычного слушающего порта; снимок не доказывает сбой и не устанавливает владельца настройки.",
                nextSteps = listOf("Сравните DNS и открытые порты до и после отключения VPN.", "Проверьте отдельно доступ по IP и разрешение имени. Если по IP работает, а по имени нет — изучите DNS.", "Не заменяйте DNS автоматически: локальный обработчик может быть частью корпоративной сети."),
                relatedItems = listOf(EvidenceRow(adapter.id, adapter.name, mapOf("DNS" to adapter.dns.joinToString(), "Описание" to adapter.description, "Интерфейс" to adapter.index.toString()))))
        }
    }

    /** A name correlation is a lead, never proof that a process created an interface. */
    fun relatedPrograms(snapshot: NetworkSnapshot, adapter: ObservedAdapter): List<EvidenceRow> {
        val token = adapter.name.lowercase().filter(Char::isLetterOrDigit)
        if (token.length < 5) return emptyList()
        return snapshot.sources.filter { it.id == "processes" || it.id == "services" }.flatMap { it.rows }
            .filter { row -> listOf("Name", "DisplayName", "ExecutablePath").any { key ->
                row.fields[key].orEmpty().lowercase().filter(Char::isLetterOrDigit).contains(token)
            } }.take(8).map { it.copy(fields = it.fields + ("Основание связи" to "Название совпадает с именем адаптера. Владение интерфейсом этим совпадением не доказано.")) }
    }
}
