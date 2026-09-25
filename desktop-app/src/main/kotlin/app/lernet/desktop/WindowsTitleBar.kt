package app.lernet.desktop

import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.ptr.IntByReference
import java.awt.Window

/** Request a dark native frame on Windows 10/11; unsupported systems keep their normal frame. */
object WindowsTitleBar {
    fun dark(window: Window) {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        runCatching {
            val hwnd = Native.getComponentPointer(window)
            val api = NativeLibrary.getInstance("dwmapi").getFunction("DwmSetWindowAttribute")
            val enabled = IntByReference(1)
            val first = api.invokeInt(arrayOf(hwnd, 20, enabled, 4))
            if (first != 0) api.invokeInt(arrayOf(hwnd, 19, enabled, 4))
        }
    }
}
