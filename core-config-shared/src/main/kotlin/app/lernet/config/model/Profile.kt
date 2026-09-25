package app.lernet.config.model

data class Profile(
    val id: String,
    val name: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val source: ProfileSource,
    val selectedOutboundId: String,
    val outbounds: List<NormalizedOutbound>,
    val subscriptionUrl: String?,
    val lastRefreshEpochMs: Long?,
    val dnsJson: String? = null,
    val dnsPolicy: DnsPolicy = DnsPolicy.UNDERLAY,
    val canvasLayout: String? = null,
    val modeOverride: String? = null,
) {
    fun selectedOutbound(): NormalizedOutbound? = outbounds.firstOrNull { it.id == selectedOutboundId }
}
