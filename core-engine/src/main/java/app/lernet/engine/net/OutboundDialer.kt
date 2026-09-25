package app.lernet.engine.net

fun interface OutboundDialer {
    suspend fun dial(target: OutboundEndpoint, timeoutMs: Int): Result<Unit>
}

object ImmediateSuccessDialer : OutboundDialer {
    override suspend fun dial(target: OutboundEndpoint, timeoutMs: Int): Result<Unit> =
        Result.success(Unit)
}
