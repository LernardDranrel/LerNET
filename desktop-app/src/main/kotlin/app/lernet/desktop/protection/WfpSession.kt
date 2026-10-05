package app.lernet.desktop.protection

internal interface WfpSession : AutoCloseable {
    fun ensureOwnership()
    fun filters(): List<ProtectionFilter>
    fun add(filter: ProtectionFilter)
    fun delete(key: String)
    fun begin()
    fun commit()
    fun abort()

    fun transaction(block: () -> Unit) {
        begin()
        try {
            block()
            commit()
        } catch (failure: Throwable) {
            try {
                abort()
            } catch (abortFailure: Throwable) {
                failure.addSuppressed(abortFailure)
            }
            throw failure
        }
    }
}
