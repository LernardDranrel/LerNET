package app.lernet.vpn

import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.StringIterator

internal class StringArray(private val values: List<String>) : StringIterator {
    private var index = 0

    override fun len(): Int = values.size

    override fun hasNext(): Boolean = index < values.size

    override fun next(): String {
        val value = values[index]
        index += 1
        return value
    }
}

internal class InterfaceArray(
    private val values: List<NetworkInterface>,
) : NetworkInterfaceIterator {
    private var index = 0

    override fun hasNext(): Boolean = index < values.size

    override fun next(): NetworkInterface {
        val value = values[index]
        index += 1
        return value
    }
}
