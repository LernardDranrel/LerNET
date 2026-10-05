package app.lernet.engine.policy

import app.lernet.config.redact.SecretRedactor

/** Native errors are finite codes; provider text must not appear in status or user-facing logs. */
fun expertNativeFailureExplanation(code: String): String =
    knownExpertNativeFailureExplanation(code) ?: SecretRedactor.redact(code).take(160)

internal fun knownExpertNativeFailureExplanation(code: String): String? = when (code) {
    "tun_identity_unavailable" -> "Не удалось подтвердить собственный адаптер TUN. Перехват трафика остановлен."
    "tun_identity_changed" -> "Собственный адаптер TUN исчез или изменил идентичность. Перехват трафика остановлен."
    "expert_route_snapshot_failed" -> "Не удалось прочитать действующие маршруты Windows; безопасный захват не подтверждён."
    "expert_route_capture_collision" -> "Чужой маршрут имеет равный приоритет с TUN. Для этого адреса захват не гарантирован."
    "expert_route_capture_not_proven" -> "Маршруты Windows не подтверждают, что нужный трафик попадёт в собственный TUN."
    "expert_capture_route_update_failed" -> "После изменения сети не удалось восстановить маршруты перехвата TUN."
    "expert_underlay_binding_invalid" -> "Выбранный сетевой интерфейс изменился или отключён; прежняя привязка недействительна."
    "expert_underlay_must_be_physical" -> "Для подключения к серверу требуется доступный физический сетевой интерфейс."
    "expert_connected_network_snapshot_failed" -> "Не удалось прочитать подключённые сети; безопасный путь не подтверждён."
    "exit_stop_failed" -> "Закрытие старого выхода не подтверждено. Повторный запуск этого транспорта запрещён."
    "generation_stop_failed" -> "Закрытие ресурсов прежней версии схемы не подтверждено. Требуется остановка режима."
    "expert_cleanup_pending" -> "Прежний выход ещё освобождает ресурсы. Завершение очистки пока не подтверждено."
    "generation_cleanup_unconfirmed" -> "Прежняя версия не освободила ресурсы. Новая схема не применена; остановите режим."
    "exit_network_changed" -> "Сеть изменилась во время ожидания выхода. Запрос нужно повторить по новому пути."
    else -> null
}
