package app.lernet.desktop

import app.lernet.desktop.protection.WindowsProtectionService
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** The elevated control token must also be unreadable to the same account's filtered, unelevated token. */
internal fun createControlSecret(protectedCore: Path, token: String): Path {
    check(System.getProperty("os.name").startsWith("Windows") && Native.POINTER_SIZE == 8) {
        "Защищённое управление ядром требует Windows x64"
    }
    require(token.length in 43..128 && token.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }) {
        "Некорректный секрет управления ядром"
    }
    check(WindowsElevation.isElevated && protectedCore.isAbsolute && WindowsProtectionService.isProtectedCore(protectedCore.toString())) {
        "Родительская папка секрета управления не подтверждена как защищённая"
    }
    val directory = protectedCore.parent.resolve("control-${UUID.randomUUID()}")
    val tokenFile = directory.resolve("control-token")
    var createdDirectory = false
    var createdFile = false
    try {
        withControlSecurity(directory = true) { attributes ->
            check(controlSecretKernel.CreateDirectoryW(WString(directory.toString()), attributes) != 0) {
                "Не удалось создать закрытую папку управления (${Native.getLastError()})"
            }
            createdDirectory = true
        }
        verifyControlSecurity(directory, directory = true)
        withControlSecurity(directory = false) { attributes ->
            val handle = controlSecretKernel.CreateFileW(WString(tokenFile.toString()), 0x40000000, 0, attributes, 1, 0x80, null)
            check(handle != null && Pointer.nativeValue(handle) != -1L) {
                "Не удалось создать закрытый секрет управления (${Native.getLastError()})"
            }
            createdFile = true
            try {
                val bytes = token.toByteArray(Charsets.US_ASCII)
                val written = IntByReference()
                check(controlSecretKernel.WriteFile(handle, bytes, bytes.size, written, null) != 0 && written.value == bytes.size) {
                    "Не удалось записать секрет управления (${Native.getLastError()})"
                }
            } finally {
                check(controlSecretKernel.CloseHandle(handle) != 0) { "Windows не подтвердила закрытие секрета управления" }
            }
        }
        verifyControlSecurity(tokenFile, directory = false)
        return tokenFile
    } catch (failure: Throwable) {
        if (createdFile) runCatching { Files.deleteIfExists(tokenFile) }.exceptionOrNull()?.let(failure::addSuppressed)
        if (createdDirectory) runCatching { Files.deleteIfExists(directory) }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
}

/** Atomic creation grants no individual user SID and changes ownership to Administrators. */
internal object ControlSecretPolicy {
    fun descriptor(directory: Boolean): String =
        "O:BAG:BAD:P(A;${if (directory) "OICI" else ""};FA;;;SY)(A;${if (directory) "OICI" else ""};FA;;;BA)"

    fun isProtectedDescriptor(value: String, directory: Boolean): Boolean {
        val descriptor = value.uppercase()
        val owner = Regex("^O:([^:()]+?)(?=[OGDS]:|$)").find(descriptor)?.groupValues?.get(1)
        if (owner !in setOf("BA", "S-1-5-32-544")) return false
        val dacl = Regex("D:([^()]*)((?:\\([^)]*\\))+)(?=S:|$)").find(descriptor) ?: return false
        if (dacl.groupValues[1] != "P") return false
        val entries = Regex("\\(([^()]*)\\)").findAll(dacl.groupValues[2]).map { it.groupValues[1].split(';') }.toList()
        if (entries.size != 2) return false
        val principals = mutableSetOf<String>()
        return entries.all { fields ->
            if (fields.size != 6 ||
                fields[0] != "A" ||
                fields[1] != (if (directory) "OICI" else "") ||
                fields[2] !in setOf("FA", "0X1F01FF") ||
                fields[3].isNotEmpty() ||
                fields[4].isNotEmpty()
            ) {
                return@all false
            }
            val principal = when (fields[5]) {
                "BA", "S-1-5-32-544" -> "BA"
                "SY", "S-1-5-18" -> "SY"
                else -> return@all false
            }
            principals.add(principal)
        } &&
            principals == setOf("BA", "SY")
    }
}

@Structure.FieldOrder("length", "descriptor", "inheritHandle")
internal class ControlSecurityAttributes : Structure() {
    @JvmField var length: Int = 0

    @JvmField var descriptor: Pointer? = null

    @JvmField var inheritHandle: Int = 0
    init {
        length = size()
    }
}

private inline fun <T> withControlSecurity(directory: Boolean, operation: (Pointer) -> T): T {
    val descriptor = PointerByReference()
    check(
        controlSecretAdvapi.ConvertStringSecurityDescriptorToSecurityDescriptorW(
            WString(ControlSecretPolicy.descriptor(directory)), 1, descriptor, null,
        ) != 0
    ) { "Не удалось подготовить закрытые права управления (${Native.getLastError()})" }
    val security = checkNotNull(descriptor.value)
    try {
        val attributes = ControlSecurityAttributes().also {
            it.descriptor = security
            it.write()
        }
        return operation(attributes.pointer)
    } finally {
        controlSecretKernel.LocalFree(security)
    }
}

private fun verifyControlSecurity(path: Path, directory: Boolean) {
    val needed = IntByReference()
    controlSecretAdvapi.GetFileSecurityW(WString(path.toString()), 5, null, 0, needed)
    check(needed.value in 20..65_536) { "Windows не сообщила права созданного секрета управления" }
    val security = Memory(needed.value.toLong())
    check(controlSecretAdvapi.GetFileSecurityW(WString(path.toString()), 5, security, needed.value, needed) != 0) {
        "Не удалось проверить права секрета управления (${Native.getLastError()})"
    }
    val text = PointerByReference()
    check(controlSecretAdvapi.ConvertSecurityDescriptorToStringSecurityDescriptorW(security, 1, 5, text, null) != 0) {
        "Windows не раскрыла права секрета управления"
    }
    try {
        check(ControlSecretPolicy.isProtectedDescriptor(checkNotNull(text.value).getWideString(0), directory)) {
            "Созданный секрет управления доступен вне повышенных прав; запуск отменён"
        }
    } finally {
        controlSecretKernel.LocalFree(checkNotNull(text.value))
    }
}

private val controlSecretAdvapi by lazy { Native.load("advapi32", ControlSecretAdvapi::class.java) }
private val controlSecretKernel by lazy { Native.load("kernel32", ControlSecretKernel::class.java) }

private interface ControlSecretAdvapi : StdCallLibrary {
    fun ConvertStringSecurityDescriptorToSecurityDescriptorW(
        text: WString,
        revision: Int,
        descriptor: PointerByReference,
        size: Pointer?,
    ): Int
    fun GetFileSecurityW(path: WString, information: Int, descriptor: Pointer?, size: Int, needed: IntByReference): Int
    fun ConvertSecurityDescriptorToStringSecurityDescriptorW(
        descriptor: Pointer,
        revision: Int,
        information: Int,
        text: PointerByReference,
        size: Pointer?,
    ): Int
}

private interface ControlSecretKernel : StdCallLibrary {
    fun CreateDirectoryW(path: WString, attributes: Pointer): Int
    fun CreateFileW(path: WString, access: Int, share: Int, attributes: Pointer, disposition: Int, flags: Int, template: Pointer?): Pointer?
    fun WriteFile(handle: Pointer, bytes: ByteArray, size: Int, written: IntByReference, overlapped: Pointer?): Int
    fun CloseHandle(handle: Pointer): Int
    fun LocalFree(value: Pointer): Pointer?
}
