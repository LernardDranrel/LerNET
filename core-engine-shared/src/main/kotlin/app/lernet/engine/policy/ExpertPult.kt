package app.lernet.engine.policy

/**
 * One source of truth for the Expert pult on both platforms: the lever label, the headline, the
 * uptime counter and the schema line all come from the same [PultStatus], so the screen can never
 * say «ВКЛ · 14 дн» next to «туннель выключен» or «Проверяем связь».
 */
enum class PultStatus {
    OFF, STARTING, CHECKING, WORKING, APPLYING,
    /** The tunnel stays up but no exit works right now: traffic does not leak, the counter keeps going. */
    HOLDING,
    PROBLEM, STOPPING,
}

enum class PultPhase { STOPPED, STARTING, RUNNING, APPLYING, STOPPING, FAILED }

object ExpertPult {
    fun status(phase: PultPhase, health: ExpertTunnelHealth): PultStatus = when (phase) {
        PultPhase.STOPPED -> PultStatus.OFF
        PultPhase.STARTING -> PultStatus.STARTING
        PultPhase.STOPPING -> PultStatus.STOPPING
        PultPhase.FAILED -> PultStatus.PROBLEM
        PultPhase.APPLYING -> PultStatus.APPLYING
        PultPhase.RUNNING -> when (health) {
            ExpertTunnelHealth.HEALTHY -> PultStatus.WORKING
            ExpertTunnelHealth.ERROR -> PultStatus.HOLDING
            ExpertTunnelHealth.PENDING, ExpertTunnelHealth.OFF -> PultStatus.CHECKING
        }
    }

    fun status(runtime: ExpertRuntimeState): PultStatus = status(
        when {
            runtime.applying && runtime.phase == ExpertSessionPhase.RUNNING -> PultPhase.APPLYING
            else -> when (runtime.phase) {
                ExpertSessionPhase.STOPPED -> PultPhase.STOPPED
                ExpertSessionPhase.STARTING -> PultPhase.STARTING
                ExpertSessionPhase.RUNNING -> PultPhase.RUNNING
                ExpertSessionPhase.STOPPING -> PultPhase.STOPPING
                ExpertSessionPhase.FAILED -> PultPhase.FAILED
            }
        },
        runtime.tunnelHealth,
    )

    /** Word on the lever plate. */
    fun lever(status: PultStatus): String = when (status) {
        PultStatus.OFF -> "ВЫКЛ"
        PultStatus.STARTING -> "ВКЛЮЧАЕМ"
        PultStatus.CHECKING -> "ПРОВЕРКА"
        PultStatus.WORKING -> "ВКЛ"
        PultStatus.APPLYING -> "МЕНЯЕМ"
        PultStatus.HOLDING -> "ДЕРЖИМ"
        PultStatus.PROBLEM -> "СБОЙ"
        PultStatus.STOPPING -> "ВЫКЛЮЧАЕМ"
    }

    /** Short headline under the lever, plain words, no service terms. */
    fun headline(status: PultStatus): String = when (status) {
        PultStatus.OFF -> "Выключено"
        PultStatus.STARTING -> "Включаем…"
        PultStatus.CHECKING -> "Включено · проверяем связь"
        PultStatus.WORKING -> "Работает"
        PultStatus.APPLYING -> "Меняем схему на ходу"
        PultStatus.HOLDING -> "Туннель держим · рабочего выхода сейчас нет"
        PultStatus.PROBLEM -> "Не запустилось"
        PultStatus.STOPPING -> "Выключаем…"
    }

    /** «Без перерыва» only when the tunnel is up and confirmed; otherwise the counter is hidden. */
    fun showsUptime(status: PultStatus): Boolean =
        status == PultStatus.WORKING || status == PultStatus.APPLYING || status == PultStatus.HOLDING

    fun running(status: PultStatus): Boolean =
        status == PultStatus.CHECKING || status == PultStatus.WORKING || status == PultStatus.APPLYING ||
            status == PultStatus.HOLDING

    /** The one schema line; never claims the tunnel is off while it runs. */
    fun schemaLine(status: PultStatus, unsavedDraft: Boolean, unapplied: Boolean, applied: Boolean): String = when {
        unsavedDraft -> "Есть несохранённые изменения"
        !running(status) -> "Схема сохранена · заработает, когда включишь"
        unapplied -> "Есть изменения · применятся после «Применить»"
        applied -> "Схема работает"
        else -> "Схема сохранена"
    }

    /**
     * The line under the pult. Null when it would only repeat the headline («Работает» + «Схема работает»),
     * so the pult shows one calm word instead of two.
     */
    fun schemaNote(status: PultStatus, unsavedDraft: Boolean, unapplied: Boolean, applied: Boolean): String? =
        schemaLine(status, unsavedDraft, unapplied, applied)
            .takeUnless { status == PultStatus.WORKING && !unsavedDraft && !unapplied && applied }

    /** Instead of «Обработчик не подтвердил безопасную смену схемы на работающем TUN». */
    const val RESTART_TO_APPLY = "На ходу эту схему не сменить. Выключи и включи — применится."
    const val APPLY_RUNNING = "Применить сейчас"
}
