package app.lernet.desktop.protection

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.lang.ref.Reference
import java.util.UUID

/** WinSDK fwpmtypes.h x64 layout. No DLL is loaded and no filter is added by constructing a plan. */
internal object WfpAbi {
    const val SESSION_SIZE = 72L
    const val SESSION_TRANSACTION_TIMEOUT_OFFSET = 36L
    const val TRANSACTION_WAIT_MILLIS = 2_000
    const val FILTER_SIZE = 200L
    const val CONDITION_SIZE = 40L
    const val PROVIDER_SIZE = 64L
    const val SUBLAYER_SIZE = 72L
    const val FILTER_PROVIDER_OFFSET = 40L
    const val FILTER_LAYER_OFFSET = 64L
    const val FILTER_SUBLAYER_OFFSET = 80L
    const val FILTER_WEIGHT_OFFSET = 96L
    const val FILTER_CONDITION_COUNT_OFFSET = 112L
    const val FILTER_CONDITIONS_OFFSET = 120L
    const val FILTER_ACTION_OFFSET = 128L
    const val FILTER_CONTEXT_OFFSET = 152L
    const val FILTER_ID_OFFSET = 176L

    /** Static management session: closing it must not delete the persistent base policy. */
    fun managementSession(): Memory = Memory(SESSION_SIZE).also {
        it.clear()
        it.setInt(SESSION_TRANSACTION_TIMEOUT_OFFSET, TRANSACTION_WAIT_MILLIS)
    }

    fun guid(value: String): Memory = Memory(16).also { writeGuid(it, 0, value) }

    fun writeGuid(memory: Pointer, offset: Long, value: String) {
        val id = UUID.fromString(value)
        memory.setInt(offset, (id.mostSignificantBits ushr 32).toInt())
        memory.setShort(offset + 4, (id.mostSignificantBits ushr 16).toShort())
        memory.setShort(offset + 6, id.mostSignificantBits.toShort())
        repeat(8) { memory.setByte(offset + 8 + it, (id.leastSignificantBits ushr ((7 - it) * 8)).toByte()) }
    }

    fun readGuid(memory: Pointer, offset: Long = 0): String {
        val high = ((memory.getInt(offset).toLong() and 0xffffffffL) shl 32) or
            ((memory.getShort(offset + 4).toLong() and 0xffff) shl 16) or
            (memory.getShort(offset + 6).toLong() and 0xffff)
        var low = 0L
        repeat(8) { low = (low shl 8) or (memory.getByte(offset + 8 + it).toLong() and 0xff) }
        return UUID(high, low).toString()
    }

    const val SESSION_FLAGS_OFFSET = 32L
    const val SESSION_FLAG_DYNAMIC = 0x1
    const val MATCH_EQUAL = 0
    const val MATCH_FLAGS_ALL_SET = 6
    const val MATCH_NOT_EQUAL = 10
    const val TYPE_UINT8 = 1
    const val TYPE_UINT16 = 2
    const val TYPE_UINT32 = 3
    const val TYPE_UINT64 = 4
    const val TYPE_BYTE_ARRAY16 = 11
    const val TYPE_V4_ADDR_MASK = 0x100
    const val TYPE_V6_ADDR_MASK = 0x101
    const val CONDITION_FLAGS = "632ce23b-5167-435c-86d7-e903684aa80c"
    const val CONDITION_REMOTE_ADDRESS = "b235ae9a-1d64-49b8-a44c-5ff3d9095045"
    const val CONDITION_REMOTE_PORT = "c35a604d-d22b-4e1a-91b4-68f674ee674b"
    const val CONDITION_LOCAL_PORT = "0c1ba1af-5765-453f-af22-a8f791ac775b"
    const val CONDITION_PROTOCOL = "3971ef2b-623e-4f9a-8cb1-6e79b806b9a7"
    const val CONDITION_NEXT_HOP_INTERFACE = "93ae8f5b-7f6f-4719-98c8-14e97429ef04"
    const val CONDITION_ARRIVAL_INTERFACE = "618a9b6d-386b-4136-ad6e-b51587cfb1cd"
    const val FLAG_IS_LOOPBACK = 0x1

    /** Dynamic session: Windows removes everything it added when the handle closes or the GUI process dies. */
    fun dynamicSession(): Memory = managementSession().also { it.setInt(SESSION_FLAGS_OFFSET, SESSION_FLAG_DYNAMIC) }

    /**
     * FWPM_FILTER_CONDITION0 for every condition that needs no Windows call (all but Application).
     * Layout: GUID fieldKey @0, FWP_MATCH_TYPE @16, FWP_CONDITION_VALUE0 { type @24, union @32 }.
     */
    fun writeCondition(value: Pointer, condition: ProtectionCondition, memory: (Long) -> Memory) {
        fun key(guid: String, type: Int, match: Int = MATCH_EQUAL) {
            writeGuid(value, 0, guid)
            value.setInt(16, match)
            value.setInt(24, type)
        }
        when (condition) {
            is ProtectionCondition.Application -> error("Application id needs FwpmGetAppIdFromFileName0")
            is ProtectionCondition.Interface -> {
                key(if (condition.nextHop) CONDITION_NEXT_HOP_INTERFACE else CONDITION_ARRIVAL_INTERFACE, TYPE_UINT64)
                value.setPointer(32, memory(8).also { it.setLong(0, condition.luid) })
            }
            is ProtectionCondition.Protocol -> {
                key(CONDITION_PROTOCOL, TYPE_UINT8)
                value.setByte(32, condition.number.toByte())
            }
            is ProtectionCondition.Port -> {
                key(if (condition.local) CONDITION_LOCAL_PORT else CONDITION_REMOTE_PORT, TYPE_UINT16)
                value.setShort(32, condition.number.toShort())
            }
            is ProtectionCondition.RemotePortNot -> {
                key(CONDITION_REMOTE_PORT, TYPE_UINT16, MATCH_NOT_EQUAL)
                value.setShort(32, condition.number.toShort())
            }
            is ProtectionCondition.Address -> {
                if (condition.numeric == "::1") {
                    key(CONDITION_REMOTE_ADDRESS, TYPE_BYTE_ARRAY16)
                    value.setPointer(32, memory(16).also { it.setByte(15, 1) })
                } else {
                    key(CONDITION_REMOTE_ADDRESS, TYPE_UINT32)
                    value.setInt(32, ipv4(condition.numeric))
                }
            }
            is ProtectionCondition.Loopback -> {
                key(CONDITION_FLAGS, TYPE_UINT32, MATCH_FLAGS_ALL_SET)
                value.setInt(32, FLAG_IS_LOOPBACK)
            }
            is ProtectionCondition.RemoteSubnet -> {
                val bytes = numericAddress(condition.address)
                if (bytes.size == 4) {
                    require(condition.prefix in 0..32)
                    key(CONDITION_REMOTE_ADDRESS, TYPE_V4_ADDR_MASK)
                    val mask = if (condition.prefix == 0) 0 else (-1 shl (32 - condition.prefix))
                    value.setPointer(32, memory(8).also {
                        it.setInt(0, ipv4(condition.address) and mask)
                        it.setInt(4, mask)
                    })
                } else {
                    require(condition.prefix in 0..128)
                    key(CONDITION_REMOTE_ADDRESS, TYPE_V6_ADDR_MASK)
                    value.setPointer(32, memory(17).also {
                        it.write(0, bytes, 0, 16)
                        it.setByte(16, condition.prefix.toByte())
                    })
                }
            }
        }
    }

    /** Numeric literals only: never resolves a name. */
    fun numericAddress(value: String): ByteArray {
        require(value.isNotBlank() && value.all { it.isDigit() || it in "abcdefABCDEF.:" }) { "Нужен числовой адрес: $value" }
        return java.net.InetAddress.getByName(value).address
    }

    /** IPv4 UINT32 and ports are host-order values, not byte-swapped network-order storage. */
    fun ipv4(value: String): Int {
        val parts = value.split('.').map { it.toInt() }
        require(parts.size == 4 && parts.all { it in 0..255 })
        return parts.fold(0) { address, part -> (address shl 8) or part }
    }
}

internal interface Fwpuclnt : StdCallLibrary {
    fun FwpmEngineOpen0(server: Pointer?, authn: Int, identity: Pointer?, session: Pointer?, handle: PointerByReference): Int
    fun FwpmEngineClose0(handle: Pointer): Int
    fun FwpmTransactionBegin0(handle: Pointer, flags: Int): Int
    fun FwpmTransactionCommit0(handle: Pointer): Int
    fun FwpmTransactionAbort0(handle: Pointer): Int
    fun FwpmProviderAdd0(handle: Pointer, provider: Pointer, security: Pointer?): Int
    fun FwpmProviderGetByKey0(handle: Pointer, key: Pointer, result: PointerByReference): Int
    fun FwpmSubLayerAdd0(handle: Pointer, sublayer: Pointer, security: Pointer?): Int
    fun FwpmSubLayerGetByKey0(handle: Pointer, key: Pointer, result: PointerByReference): Int
    fun FwpmFilterAdd0(handle: Pointer, filter: Pointer, security: Pointer?, id: LongByReference): Int
    fun FwpmFilterDeleteByKey0(handle: Pointer, key: Pointer): Int
    fun FwpmFilterCreateEnumHandle0(handle: Pointer, template: Pointer?, enumeration: PointerByReference): Int
    fun FwpmFilterEnum0(handle: Pointer, enumeration: Pointer, count: Int, entries: PointerByReference, returned: IntByReference): Int
    fun FwpmFilterDestroyEnumHandle0(handle: Pointer, enumeration: Pointer): Int
    fun FwpmGetAppIdFromFileName0(path: WString, result: PointerByReference): Int
    fun FwpmFreeMemory0(memory: PointerByReference)
}

/** Which provider/sublayer a session may read and write. */
internal data class WfpScope(val providerKey: String?, val sublayerKey: String) {
    companion object {
        val EXPERT = WfpScope(ProtectionPlan.PROVIDER_KEY, ProtectionPlan.SUBLAYER_KEY)
        val SIMPLE = WfpScope(null, SimpleGuardPlan.SUBLAYER_KEY)
    }
}

internal class JnaWfpSession private constructor(
    private val api: Fwpuclnt,
    private val handle: Pointer,
    private val scope: WfpScope = WfpScope.EXPERT,
) : WfpSession {
    companion object {
        /**
         * Simple-mode guard session. Everything it adds is dynamic: closing the session, or the GUI
         * process ending for any reason, removes it, so a stuck block cannot outlive LerNET.
         */
        fun openDynamic(): JnaWfpSession {
            val (api, handle) = openEngine(WfpAbi.dynamicSession())
            return JnaWfpSession(api, handle, WfpScope.SIMPLE)
        }

        fun open(): JnaWfpSession {
            val (api, handle) = openEngine(WfpAbi.managementSession())
            return JnaWfpSession(api, handle)
        }

        private fun openEngine(session: Memory): Pair<Fwpuclnt, Pointer> {
            check(System.getProperty("os.name").startsWith("Windows") && Native.POINTER_SIZE == 8) {
                "Защита WFP доступна в 64-разрядной Windows"
            }
            val api = Native.load("fwpuclnt", Fwpuclnt::class.java)
            val handle = PointerByReference()
            try {
                checked(api.FwpmEngineOpen0(null, 10, null, session, handle), "FwpmEngineOpen0")
            } finally {
                Reference.reachabilityFence(session)
            }
            return api to checkNotNull(handle.value)
        }

        private fun checked(code: Int, operation: String) {
            check(code == 0) { "$operation: Windows WFP ${Integer.toUnsignedString(code, 16)}" }
        }
    }

    override fun ensureOwnership() {
        if (scope == WfpScope.SIMPLE) return ensureSimpleSublayer()
        val arena = Arena()
        try {
            val provider = arena.memory(WfpAbi.PROVIDER_SIZE)
            WfpAbi.writeGuid(provider, 0, ProtectionPlan.PROVIDER_KEY)
            provider.setPointer(16, arena.text("LerNET protection"))
            provider.setPointer(24, arena.text("Persistent network policy owned by LerNET"))
            provider.setInt(32, 1)
            provider.setPointer(56, arena.text(ProtectionPlan.SERVICE_NAME))
            val providerCode = api.FwpmProviderAdd0(handle, provider, null)
            if (providerCode == 0x80320009.toInt()) {
                val existing = PointerByReference()
                checked(api.FwpmProviderGetByKey0(handle, arena.guid(ProtectionPlan.PROVIDER_KEY), existing), "FwpmProviderGetByKey0")
                try {
                    val value = checkNotNull(existing.value)
                    check(value.getInt(32) and 1 != 0 && value.getPointer(56)?.getWideString(0) == ProtectionPlan.SERVICE_NAME) {
                        "Поставщик защиты LerNET имеет другое имя службы или срок жизни; восстановите его явно"
                    }
                } finally {
                    api.FwpmFreeMemory0(existing)
                }
            } else {
                checked(providerCode, "FwpmProviderAdd0")
            }

            val sublayer = arena.memory(WfpAbi.SUBLAYER_SIZE)
            WfpAbi.writeGuid(sublayer, 0, ProtectionPlan.SUBLAYER_KEY)
            sublayer.setPointer(16, arena.text("LerNET protection"))
            sublayer.setInt(32, 1)
            sublayer.setPointer(40, arena.guid(ProtectionPlan.PROVIDER_KEY))
            sublayer.setShort(64, 0xffff.toShort())
            val sublayerCode = api.FwpmSubLayerAdd0(handle, sublayer, null)
            if (sublayerCode == 0x80320009.toInt()) {
                val existing = PointerByReference()
                checked(api.FwpmSubLayerGetByKey0(handle, arena.guid(ProtectionPlan.SUBLAYER_KEY), existing), "FwpmSubLayerGetByKey0")
                try {
                    val value = checkNotNull(existing.value)
                    check(
                        value.getInt(32) and 1 != 0 &&
                            value.getPointer(40)?.let(WfpAbi::readGuid) == ProtectionPlan.PROVIDER_KEY &&
                            value.getShort(64).toInt() and 0xffff == 0xffff
                    ) { "Подслой LerNET принадлежит другому поставщику" }
                } finally {
                    api.FwpmFreeMemory0(existing)
                }
            } else {
                checked(sublayerCode, "FwpmSubLayerAdd0")
            }
        } finally {
            Reference.reachabilityFence(arena)
        }
    }

    /** A sublayer without provider, created inside the dynamic session and removed with it. */
    private fun ensureSimpleSublayer() {
        val arena = Arena()
        try {
            val sublayer = arena.memory(WfpAbi.SUBLAYER_SIZE)
            WfpAbi.writeGuid(sublayer, 0, SimpleGuardPlan.SUBLAYER_KEY)
            sublayer.setPointer(16, arena.text("LerNET simple guard"))
            sublayer.setShort(64, 0xffff.toShort())
            val code = api.FwpmSubLayerAdd0(handle, sublayer, null)
            // Already present in this session after a previous transaction: reuse it.
            if (code != 0x80320009.toInt()) checked(code, "FwpmSubLayerAdd0")
        } finally {
            Reference.reachabilityFence(arena)
        }
    }

    override fun filters(): List<ProtectionFilter> {
        val enumeration = PointerByReference()
        checked(api.FwpmFilterCreateEnumHandle0(handle, null, enumeration), "FwpmFilterCreateEnumHandle0")
        val result = mutableListOf<ProtectionFilter>()
        try {
            var seen = 0
            while (true) {
                val entries = PointerByReference()
                val count = IntByReference()
                checked(api.FwpmFilterEnum0(handle, checkNotNull(enumeration.value), 256, entries, count), "FwpmFilterEnum0")
                try {
                    seen += count.value
                    check(count.value in 0..256 && seen <= 100_000) { "Windows вернула слишком большой набор фильтров" }
                    repeat(count.value) { index ->
                        val value = checkNotNull(entries.value).getPointer(index * Native.POINTER_SIZE.toLong())
                        val provider = value.getPointer(WfpAbi.FILTER_PROVIDER_OFFSET)?.let(WfpAbi::readGuid)
                        val sublayer = WfpAbi.readGuid(value, WfpAbi.FILTER_SUBLAYER_OFFSET)
                        // Never trust a display name, file-local id list, or a provider key alone.
                        if (provider == scope.providerKey && sublayer == scope.sublayerKey) {
                            val layerKey = WfpAbi.readGuid(value, WfpAbi.FILTER_LAYER_OFFSET)
                            val layer = ProtectionLayer.entries.firstOrNull { it.key == layerKey }
                            val action = value.getInt(WfpAbi.FILTER_ACTION_OFFSET)
                            result += ProtectionFilter(
                                WfpAbi.readGuid(value), layer ?: ProtectionLayer.CONNECT_V4,
                                if (action == 0x1001) ProtectionAction.BLOCK else ProtectionAction.PERMIT,
                                0, readSingleInterfaceCondition(value), disabled = value.getInt(32) and 0x20 != 0,
                                providerKey = scope.providerKey ?: "",
                                sublayerKey = scope.sublayerKey,
                                persistent = value.getInt(32) and 1 != 0,
                                compatible = layer != null && action in setOf(0x1001, 0x1002),
                            )
                        }
                    }
                } finally {
                    if (entries.value != null) api.FwpmFreeMemory0(entries)
                }
                if (count.value < 256) break
            }
        } finally {
            checked(api.FwpmFilterDestroyEnumHandle0(handle, checkNotNull(enumeration.value)), "FwpmFilterDestroyEnumHandle0")
        }
        return result
    }

    private fun readSingleInterfaceCondition(filter: Pointer): List<ProtectionCondition> {
        if (filter.getInt(WfpAbi.FILTER_CONDITION_COUNT_OFFSET) != 1) return emptyList()
        val value = filter.getPointer(WfpAbi.FILTER_CONDITIONS_OFFSET) ?: return emptyList()
        if (value.getInt(16) != 0 || value.getInt(24) != 4) return emptyList() // FWP_MATCH_EQUAL / UINT64 pointer
        val nextHop = when (WfpAbi.readGuid(value)) {
            "93ae8f5b-7f6f-4719-98c8-14e97429ef04" -> true
            "618a9b6d-386b-4136-ad6e-b51587cfb1cd" -> false
            else -> return emptyList()
        }
        val luid = value.getPointer(32)?.getLong(0) ?: return emptyList()
        return listOf(ProtectionCondition.Interface(luid, nextHop))
    }

    override fun add(filter: ProtectionFilter) {
        require((filter.providerKey.ifEmpty { null }) == scope.providerKey && filter.sublayerKey == scope.sublayerKey)
        require(scope != WfpScope.SIMPLE || !filter.persistent) { "Простая защита не оставляет постоянных фильтров" }
        val arena = Arena()
        val appIds = mutableListOf<PointerByReference>()
        try {
            val memory = arena.memory(WfpAbi.FILTER_SIZE)
            WfpAbi.writeGuid(memory, 0, filter.key)
            memory.setPointer(16, arena.text("LerNET ${filter.action.name.lowercase()}"))
            memory.setInt(32, if (filter.persistent) 1 else 0) // Production TUN permits are added by the guardian's dynamic session.
            // Permits remain soft; blocks use the default hard action.
            memory.setPointer(WfpAbi.FILTER_PROVIDER_OFFSET, scope.providerKey?.let(arena::guid))
            WfpAbi.writeGuid(memory, WfpAbi.FILTER_LAYER_OFFSET, filter.layer.key)
            WfpAbi.writeGuid(memory, WfpAbi.FILTER_SUBLAYER_OFFSET, scope.sublayerKey)
            memory.setInt(WfpAbi.FILTER_WEIGHT_OFFSET, 4) // FWP_UINT64 contains a pointer, not an inline number.
            memory.setPointer(WfpAbi.FILTER_WEIGHT_OFFSET + 8, arena.long(filter.weight))
            memory.setInt(WfpAbi.FILTER_CONDITION_COUNT_OFFSET, filter.conditions.size)
            if (filter.conditions.isNotEmpty()) {
                val conditions = arena.memory(filter.conditions.size * WfpAbi.CONDITION_SIZE)
                filter.conditions.forEachIndexed { index, condition ->
                    writeCondition(conditions.share(index * WfpAbi.CONDITION_SIZE), condition, arena, appIds)
                }
                memory.setPointer(WfpAbi.FILTER_CONDITIONS_OFFSET, conditions)
            }
            memory.setInt(WfpAbi.FILTER_ACTION_OFFSET, if (filter.action == ProtectionAction.BLOCK) 0x1001 else 0x1002)
            checked(api.FwpmFilterAdd0(handle, memory, null, LongByReference()), "FwpmFilterAdd0")
        } finally {
            appIds.forEach(api::FwpmFreeMemory0)
            Reference.reachabilityFence(arena)
        }
    }

    private fun writeCondition(value: Pointer, condition: ProtectionCondition, arena: Arena, appIds: MutableList<PointerByReference>) {
        if (condition !is ProtectionCondition.Application) return WfpAbi.writeCondition(value, condition, arena::memory)
        val result = PointerByReference()
        checked(api.FwpmGetAppIdFromFileName0(WString(condition.path), result), "FwpmGetAppIdFromFileName0")
        appIds += result
        WfpAbi.writeGuid(value, 0, "d78e1e87-8644-4ea5-9437-d809ecefc971")
        value.setInt(16, WfpAbi.MATCH_EQUAL)
        value.setInt(24, 12)
        value.setPointer(32, checkNotNull(result.value))
    }

    override fun delete(key: String) {
        checked(api.FwpmFilterDeleteByKey0(handle, WfpAbi.guid(key)), "FwpmFilterDeleteByKey0")
    }
    override fun begin() {
        checked(api.FwpmTransactionBegin0(handle, 0), "FwpmTransactionBegin0")
    }
    override fun commit() {
        checked(api.FwpmTransactionCommit0(handle), "FwpmTransactionCommit0")
    }
    override fun abort() {
        checked(api.FwpmTransactionAbort0(handle), "FwpmTransactionAbort0")
    }
    override fun close() {
        checked(api.FwpmEngineClose0(handle), "FwpmEngineClose0")
    }

    private class Arena {
        private val references = mutableListOf<Memory>()
        fun memory(size: Long): Memory = Memory(size).also {
            it.clear()
            references += it
        }
        fun text(text: String): Memory = memory((text.length + 1) * 2L).also { it.setWideString(0, text) }
        fun guid(text: String): Memory = memory(16).also { WfpAbi.writeGuid(it, 0, text) }
        fun long(value: Long): Memory = memory(8).also { it.setLong(0, value) }
    }
}
