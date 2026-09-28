package app.lernet.engine

sealed class ConnectionCause {
    data object UserDisconnected : ConnectionCause()

    data object NoActiveProfile : ConnectionCause()

    data class InvalidRouteTree(val details: List<String>) : ConnectionCause()

    data class InvalidConfig(val details: List<String>) : ConnectionCause()

    data object EngineUnavailable : ConnectionCause()

    data object VpnPermissionDenied : ConnectionCause()

    data class EngineStartFailed(val detail: String) : ConnectionCause()

    data class TlsFailure(val detail: String) : ConnectionCause()

    data class HandshakeFailure(val detail: String) : ConnectionCause()

    data class ConnectionReset(val detail: String) : ConnectionCause()

    data class DialFailure(val detail: String) : ConnectionCause()

    data class DialTimeout(val timeoutSeconds: Int) : ConnectionCause()

    data class WatchdogTimeout(val silentForSeconds: Int) : ConnectionCause()

    data class TunnelHealthFailed(val detail: String) : ConnectionCause()

    data class DnsStalled(val silentForSeconds: Int) : ConnectionCause()

    data class DnsUnreachable(val silentForSeconds: Int) : ConnectionCause()

    data class ConnectTimeout(val timeoutSeconds: Int) : ConnectionCause()

    data class OutboundUnreachable(val detail: String) : ConnectionCause()

    data class ReconnectExhausted(val attempts: Int, val last: ConnectionCause) : ConnectionCause()

    data class FailoverExhausted(val groupName: String) : ConnectionCause()

    data object ServiceRevoked : ConnectionCause()

    fun titleRu(): String = CauseTitles.ru(this)

    fun technicalDetail(): String? = CauseDetails.ru(this)

    fun labelRu(): String {
        val detail = technicalDetail()
        return if (detail.isNullOrBlank()) titleRu() else "${titleRu()}: $detail"
    }

    fun isRetryable(): Boolean =
        when (this) {
            is TlsFailure,
            is HandshakeFailure,
            is ConnectionReset,
            is DialFailure,
            is DialTimeout,
            is WatchdogTimeout,
            is TunnelHealthFailed,
            is DnsStalled,
            is DnsUnreachable,
            is ConnectTimeout,
            is OutboundUnreachable,
            -> true
            is UserDisconnected,
            is NoActiveProfile,
            is InvalidRouteTree,
            is InvalidConfig,
            is EngineUnavailable,
            is VpnPermissionDenied,
            is EngineStartFailed,
            is ReconnectExhausted,
            is FailoverExhausted,
            is ServiceRevoked,
            -> false
        }
}

private object CauseTitles {
    fun ru(cause: ConnectionCause): String =
        sessionTitle(cause) ?: runtimeTitle(cause)

    private fun sessionTitle(cause: ConnectionCause): String? =
        when (cause) {
            ConnectionCause.UserDisconnected -> "Отключено пользователем"
            ConnectionCause.NoActiveProfile -> "Нет активного профиля"
            is ConnectionCause.InvalidRouteTree -> "Некорректное дерево маршрутов"
            is ConnectionCause.InvalidConfig -> "Некорректный конфиг"
            ConnectionCause.EngineUnavailable -> "Ядро не подключено"
            ConnectionCause.VpnPermissionDenied -> "Нет разрешения VPN"
            ConnectionCause.ServiceRevoked -> "Система отозвала VPN"
            else -> null
        }

    private fun runtimeTitle(cause: ConnectionCause): String =
        when (cause) {
            is ConnectionCause.EngineStartFailed -> "Ядро не запустилось"
            is ConnectionCause.TlsFailure -> "Сбой TLS"
            is ConnectionCause.HandshakeFailure -> "Сбой handshake"
            is ConnectionCause.ConnectionReset -> "Соединение сброшено"
            is ConnectionCause.DialFailure -> "Не удалось дозвониться"
            is ConnectionCause.DialTimeout -> "Таймаут дозвона"
            is ConnectionCause.WatchdogTimeout -> "Нет трафика"
            is ConnectionCause.TunnelHealthFailed -> "Туннель не отвечает"
            is ConnectionCause.DnsStalled -> "DNS не отвечает"
            is ConnectionCause.DnsUnreachable -> "DNS не отвечает"
            is ConnectionCause.ConnectTimeout -> "Таймаут подключения"
            is ConnectionCause.OutboundUnreachable -> "Узел недоступен"
            is ConnectionCause.ReconnectExhausted -> "Исчерпаны попытки"
            is ConnectionCause.FailoverExhausted -> "Группа без живых узлов"
            else -> error("unhandled cause title: $cause")
        }
}

private object CauseDetails {
    fun ru(cause: ConnectionCause): String? =
        if (silentSession(cause)) null else sessionDetail(cause) ?: runtimeDetail(cause)

    private fun silentSession(cause: ConnectionCause): Boolean =
        cause is ConnectionCause.UserDisconnected ||
            cause is ConnectionCause.NoActiveProfile ||
            cause is ConnectionCause.VpnPermissionDenied ||
            cause is ConnectionCause.ServiceRevoked

    private fun sessionDetail(cause: ConnectionCause): String? =
        when (cause) {
            ConnectionCause.EngineUnavailable -> "Ядро libbox не подключено — VPN не поднимался"
            is ConnectionCause.InvalidRouteTree -> cause.details.filter { it.isNotBlank() }.joinToString("\n").ifBlank { null }
            is ConnectionCause.InvalidConfig -> cause.details.filter { it.isNotBlank() }.joinToString("\n").ifBlank { null }
            else -> null
        }

    private fun runtimeDetail(cause: ConnectionCause): String? =
        when (cause) {
            is ConnectionCause.EngineStartFailed -> cause.detail.ifBlank { null }
            is ConnectionCause.TlsFailure -> cause.detail.ifBlank { null }
            is ConnectionCause.HandshakeFailure -> cause.detail.ifBlank { null }
            is ConnectionCause.ConnectionReset -> cause.detail.ifBlank { null }
            is ConnectionCause.DialFailure -> cause.detail.ifBlank { null }
            is ConnectionCause.DialTimeout -> "${cause.timeoutSeconds} с"
            is ConnectionCause.WatchdogTimeout ->
                "${cause.silentForSeconds} с без прироста байт и без dns query ok"
            is ConnectionCause.TunnelHealthFailed -> cause.detail.ifBlank { null }
            is ConnectionCause.DnsStalled ->
                "нет dns query ok ${cause.silentForSeconds} с — перезапуск ядра"
            is ConnectionCause.DnsUnreachable ->
                "нет dns query ok ${cause.silentForSeconds} с после перезапуска"
            is ConnectionCause.ConnectTimeout ->
                "Не вышли из Connecting за ${cause.timeoutSeconds} с"
            is ConnectionCause.OutboundUnreachable -> cause.detail.ifBlank { null }
            is ConnectionCause.ReconnectExhausted -> {
                val last = cause.last.labelRu()
                "${cause.attempts} попыток. Последняя ошибка: $last"
            }
            is ConnectionCause.FailoverExhausted -> "Группа «${cause.groupName}»"
            else -> error("unhandled cause detail: $cause")
        }
}
