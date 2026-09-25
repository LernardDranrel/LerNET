package app.lernet.engine.nativebridge

/**
 * Gomobile surface. Real `Libbox` stays in `app`.
 *
 * Live SFA `Application.kt`: `// Seq.setContext(this)`, first touch is
 * `Libbox.setLocale` (this loads `Seq` / `libbox.so` via clinit), then
 * `Libbox.setup` on IO. Do not call `Seq.setContext`. Do not call
 * `System.loadLibrary` separately — that is not SFA LoadJNI order.
 * `SetupOptions()` stays inside [setup].
 */
interface NativeBridge {
    fun setLocale()

    fun setup()

    fun version(): String
}

object NativeBootstrap {
    fun run(
        bridge: NativeBridge,
        onLocaleFailure: (Throwable) -> Unit,
    ): String = NativeBootstrapSession().await(bridge, onLocaleFailure)
}
