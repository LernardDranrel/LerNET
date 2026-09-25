package app.lernet.engine.nativebridge

/**
 * Gomobile converts Java `Exception` to a Go error. Unchecked `Error` /
 * `RuntimeException` crossing JNI can abort the runtime. Platform callbacks
 * must go through these helpers.
 */
object PlatformJni {
    fun <T> call(site: String, block: () -> T): T =
        try {
            block()
        } catch (error: Exception) {
            if (error.javaClass == Exception::class.java) {
                throw error
            }
            throw Exception("$site: ${error.message}", error)
        } catch (error: Throwable) {
            throw Exception("$site: ${error.javaClass.simpleName}: ${error.message}", error)
        }

    fun run(
        site: String,
        onError: (String, Throwable) -> Unit,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (error: Throwable) {
            onError(site, error)
        }
    }

    fun <T> orElse(
        fallback: T,
        onError: (Throwable) -> Unit,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (error: Throwable) {
            onError(error)
            fallback
        }
}
