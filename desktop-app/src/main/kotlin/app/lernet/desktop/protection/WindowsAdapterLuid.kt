package app.lernet.desktop.protection

import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.ptr.LongByReference
import com.sun.jna.win32.StdCallLibrary

/** Reads the current LUID of a network adapter by its Windows alias. Read-only. */
internal object WindowsAdapterLuid {
    private interface IpHelper : StdCallLibrary {
        fun ConvertInterfaceAliasToLuid(alias: WString, luid: LongByReference): Int
    }

    private val api: IpHelper by lazy { Native.load("iphlpapi", IpHelper::class.java) }

    fun byAlias(alias: String): Long? {
        val luid = LongByReference()
        return if (api.ConvertInterfaceAliasToLuid(WString(alias), luid) == 0) luid.value.takeIf { it != 0L } else null
    }
}
