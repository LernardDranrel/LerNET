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

    /** The passive detector saw a known filter pattern on ordinary traffic. */
    data class BlockingSuspected(val sign: app.lernet.engine.policy.BlockingSign) : ConnectionCause()

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
            is BlockingSuspected,
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
            ConnectionCause.UserDisconnected -> "Отключено"
            ConnectionCause.NoActiveProfile -> "Конфиг не выбран"
            is ConnectionCause.InvalidRouteTree -> "Ошибка в правилах"
            is ConnectionCause.InvalidConfig -> "Ошибка в конфиге"
            ConnectionCause.EngineUnavailable -> "Модуль VPN не загрузился"
            ConnectionCause.VpnPermissionDenied -> "Нет разрешения на VPN"
            ConnectionCause.ServiceRevoked -> "Система отозвала VPN"
            else -> null
        }

    private fun runtimeTitle(cause: ConnectionCause): String =
        when (cause) {
            is ConnectionCause.EngineStartFailed -> "VPN не запустился"
            is ConnectionCause.TlsFailure -> "Защищённое соединение не установилось"
            is ConnectionCause.HandshakeFailure -> "Сервер не принял подключение"
            is ConnectionCause.ConnectionReset -> "Соединение прервалось"
            is ConnectionCause.DialFailure -> "Сервер не принимает подключение"
            is ConnectionCause.DialTimeout -> "Сервер не ответил вовремя"
            is ConnectionCause.WatchdogTimeout -> "Данные не идут"
            is ConnectionCause.TunnelHealthFailed -> "Интернет через VPN не открывается"
            is ConnectionCause.BlockingSuspected -> "Похоже на блокировку"
            is ConnectionCause.DnsStalled -> "Адреса сайтов не находятся"
            is ConnectionCause.DnsUnreachable -> "Адреса сайтов не находятся"
            is ConnectionCause.ConnectTimeout -> "Подключение заняло слишком много времени"
            is ConnectionCause.OutboundUnreachable -> "Сервер недоступен"
            is ConnectionCause.ReconnectExhausted -> "Переподключиться не удалось"
            is ConnectionCause.FailoverExhausted -> "В папке нет рабочих конфигов"
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
            is ConnectionCause.BlockingSuspected -> app.lernet.engine.policy.BlockingDetector.message(cause.sign)
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
