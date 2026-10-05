package app.lernet.desktop.protection

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Only explicit enable/uninstall invokes mutation. Inspection is read-only. */
object WindowsProtectionService : ProtectionGuardian {
    private const val FILE_NAME = "lernet-protection-service.exe"
    private const val CORE_FILE_NAME = "lernet-core.exe"
    private val CORE_DEPENDENCIES = listOf("wintun.dll", "libcronet.dll")
    private const val SERVICE_ACCESS = 0xF01FF
    private val api: ServiceApi by lazy { Native.load("advapi32", ServiceApi::class.java) }
    private val kernel: ServiceKernel by lazy { Native.load("kernel32", ServiceKernel::class.java) }

    private fun installRoot(): Path = Path.of(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_ProgramFilesX64), "LerNETProtection")

    private data class GuardianStatus(val owner: ProtectionTunOwner?, val keys: Set<String>)

    private fun jsonString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun runningImage(pid: Int): String? {
        if (pid <= 0) return null
        val process = kernel.OpenProcess(0x1000, 0, pid) ?: return null
        try {
            val size = IntByReference(32768)
            val name = Memory(size.value * 2L)
            return if (kernel.QueryFullProcessImageNameW(process, 0, name, size) != 0) name.getWideString(0) else null
        } finally {
            kernel.CloseHandle(process)
        }
    }

    private fun bundledServiceDigest(): String = WindowsProtectionService::class.java.getResourceAsStream("/runtime/$FILE_NAME")
        ?.use { input -> sha256(input.readNBytes(20_000_001)).joinToString("") { "%02x".format(it) } }
        ?: error("В сборке отсутствует служба защиты Windows")

    private fun control(operation: String, fields: String = "", deadlineNanos: Long = protectionControlDeadline()): GuardianStatus {
        val bytes = WindowsProtectionService::class.java.getResourceAsStream("/runtime/$FILE_NAME")?.use { it.readNBytes(20_000_001) }
            ?: error("В сборке отсутствует служба защиты Windows")
        val hash = sha256(bytes).joinToString("") { "%02x".format(it) }
        val helper = installRoot().resolve(hash).resolve(FILE_NAME)
        check(hasProtectedArtifact(helper)) { "Копия службы защиты Windows не подтверждена" }
        val request = "{\"operation\":${jsonString(operation)}$fields}"
        val encoded = Base64.getEncoder().encodeToString(request.toByteArray(Charsets.UTF_8))
        check(deadlineNanos - System.nanoTime() > 1_000_000_000L) { "Истёк срок обращения к службе защиты; команда не запускалась" }
        val process = ProcessBuilder(helper.toString(), "--control", encoded).directory(helper.parent.toFile())
            .redirectErrorStream(true).start()
        val result = runProtectionControl(process, deadlineNanos)
        val response = result.text
        check(result.exitCode == 0 && Regex("\"ok\"\\s*:\\s*true").containsMatchIn(response)) {
            "Служба защиты отклонила команду: ${response.take(2048)}"
        }
        fun number(name: String): Long = Regex("\"$name\"\\s*:\\s*(\\d+)").find(response)?.groupValues?.get(1)?.toLong()
            ?: error("Служба не сообщила $name")
        fun text(name: String): String = Regex("\"$name\"\\s*:\\s*\"([^\"\\\\]+)\"").find(response)?.groupValues?.get(1)
            ?: error("Служба не сообщила $name")
        val owner = if (response.contains("\"owner\"")) {
            ProtectionTunOwner(
                number("pid"), number("started_at_ms"), text("tun_name"), number("luid"),
                number("if_index").toInt(), UUID.fromString(text("guid")).toString()
            )
        } else {
            null
        }
        val keyBlock = Regex("\"keys\"\\s*:\\s*\\[([^]]*)]").find(response)?.groupValues?.get(1).orEmpty()
        val keys = Regex("\"([a-fA-F0-9-]{36})\"").findAll(keyBlock).map { UUID.fromString(it.groupValues[1]).toString() }.toSet()
        return GuardianStatus(owner, keys)
    }

    override fun prepare(corePath: String, tunName: String, lease: ProtectionLease): ProtectionTunOwner {
        val deadline = protectionControlDeadline()
        require(Regex("LerNET-[A-Za-z0-9-]{1,100}").matches(tunName)) { "Неверное имя собственного адаптера" }
        val result = control(
            "prepare_owned_tun",
            ",\"pid\":${lease.pid},\"started_at_ms\":${lease.startedAtMillis}," +
                "\"core_path\":${jsonString(corePath)},\"tun_name\":${jsonString(tunName)}",
            deadline
        )
        return checkNotNull(result.owner).also {
            check(it.pid == lease.pid && it.startedAtMillis == lease.startedAtMillis && it.tunName == tunName && it.luid != 0L)
        }
    }

    override fun arm(filters: List<ProtectionFilter>, lease: ProtectionLease) {
        val deadline = protectionControlDeadline()
        val status = control("status", deadlineNanos = deadline)
        val owner = checkNotNull(status.owner) { "Служба не владеет текущим TUN" }
        check(owner.pid == lease.pid && owner.startedAtMillis == lease.startedAtMillis)
        check(
            filters.size == 4 &&
                filters.all { filter ->
                    filter.isTunAllowance && filter.conditions.single() == ProtectionCondition.Interface(owner.luid, filter.layer.outbound)
                }
        ) { "План разрешает другой адаптер" }
        val encoded = filters.joinToString(",") { "{\"key\":${jsonString(it.key)},\"layer\":${jsonString(it.layer.key)}}" }
        val armed = control(
            "arm_owned_tun",
            ",\"pid\":${lease.pid},\"started_at_ms\":${lease.startedAtMillis}," +
                "\"luid\":${owner.luid},\"guid\":${jsonString(owner.guid)},\"filters\":[$encoded]",
            deadline
        )
        check(armed.keys == filters.map { it.key }.toSet()) { "Служба не подтвердила разрешения текущего TUN" }
    }

    override fun revoke() {
        val deadline = protectionControlDeadline()
        val owner = control("status", deadlineNanos = deadline).owner ?: return
        control("revoke_owned_tun", ",\"pid\":${owner.pid},\"started_at_ms\":${owner.startedAtMillis}", deadline)
    }

    override fun releaseStopped() {
        val deadline = protectionControlDeadline()
        val owner = control("status", deadlineNanos = deadline).owner ?: return
        val released = control("release_owned_tun", ",\"pid\":${owner.pid},\"started_at_ms\":${owner.startedAtMillis}", deadline)
        check(released.owner == null) { "Служба не подтвердила освобождение остановленного адаптера" }
    }

    override fun activeKeys(): Set<String> = control("status").keys

    /** A WFP app-id trusts the path. The unrestricted core must not stay in a user-writable folder. */
    @Synchronized
    fun prepareProtectedCore(source: Path): Path {
        check(source.fileName.toString().equals(CORE_FILE_NAME, true) && Files.isRegularFile(source)) {
            "Не найден собственный lernet-core.exe"
        }
        val bytes = Files.newInputStream(source).use { it.readNBytes(128_000_001) }
        check(bytes.size in 1..128_000_000) { "Неверный размер ядра LerNET" }
        val sourceDigest = sha256(bytes).joinToString("") { "%02x".format(it) }
        check(sourceDigest == bundledCoreDigest()) { "Ядро LerNET отличается от комплектной сборки" }
        rejectReparseParents(source)
        // Native DLLs share the trusted core directory. A writable working directory or PATH
        // must never supply a dependency to an otherwise unrestricted WFP application.
        val dependencies = CORE_DEPENDENCIES.associateWith { name ->
            val supplied = source.parent.resolve(name)
            rejectReparseParents(supplied)
            check(Files.isRegularFile(supplied) && Files.size(supplied) in 1..64_000_000) {
                "Рядом с ядром отсутствует комплектная библиотека $name"
            }
            val expected = bundledDependency(name)
            val actual = Files.readAllBytes(supplied)
            check(MessageDigest.isEqual(sha256(actual), sha256(expected))) { "Библиотека $name отличается от комплектной сборки" }
            actual
        }
        val dependencyDigests = dependencies.mapValues { (_, value) -> sha256(value).joinToString("") { "%02x".format(it) } }
        val bundleDigest = ProtectedBundleIdentity.digest(sourceDigest, dependencyDigests)
        val executable = protectArtifact(bytes, CORE_FILE_NAME, bundleDigest)
        dependencies.forEach { (name, content) -> protectFile(executable.parent.resolve(name), content, allowUserExecute = false) }
        check(isProtectedCore(executable.toString())) { "Защищённая копия ядра или её библиотек не прошла проверку" }
        return executable
    }

    fun isProtectedCore(path: String): Boolean = runCatching {
        if (!ServiceBinaryPolicy.isOwned("\"$path\"", installRoot().toString(), CORE_FILE_NAME)) return@runCatching false
        val file = Path.of(path)
        rejectReparseParents(file)
        if (!Files.isRegularFile(file) || Files.size(file) !in 1..128_000_000) return@runCatching false
        if (!hasProtectedAcl(file, allowUserExecute = false) || !listOf(file.parent, installRoot()).all { hasProtectedAcl(it) }) {
            return@runCatching false
        }
        val digest = fileSha256(file).joinToString("") { "%02x".format(it) }
        digest == bundledCoreDigest() &&
            file.parent.fileName.toString() == ProtectedBundleIdentity.digest(
                digest, CORE_DEPENDENCIES.associateWith { sha256(bundledDependency(it)).joinToString("") { byte -> "%02x".format(byte) } }
            ) &&
            CORE_DEPENDENCIES.all { name ->
                val library = file.parent.resolve(name)
                rejectReparseParents(library)
                Files.isRegularFile(library) &&
                    Files.size(library) in 1..64_000_000 &&
                    hasProtectedAcl(library, allowUserExecute = false) &&
                    MessageDigest.isEqual(sha256(Files.readAllBytes(library)), sha256(bundledDependency(name)))
            }
    }.getOrDefault(false)

    fun isReady(): Boolean = runCatching {
        manager(1).use { manager ->
            val service = api.OpenServiceW(manager.value, WString(ProtectionPlan.SERVICE_NAME), 0x5) ?: return@use false
            ServiceHandle(service).use {
                val config = configuration(service)
                val status = Memory(36)
                check(api.QueryServiceStatusEx(service, 0, status, status.size().toInt(), IntByReference()) != 0)
                config.startType == 2 &&
                    config.serviceType == 0x10 &&
                    config.account.equals("LocalSystem", true) &&
                    ServiceBinaryPolicy.isOwned(config.binaryPath, installRoot().toString()) &&
                    hasProtectedArtifact(Path.of(config.binaryPath.drop(1).dropLast(1))) &&
                    status.getInt(4) == 4 &&
                    Path.of(config.binaryPath.drop(1).dropLast(1)).parent.fileName.toString() == bundledServiceDigest() &&
                    runningImage(status.getInt(28))?.equals(config.binaryPath.drop(1).dropLast(1), true) == true
            }
        }
    }.getOrDefault(false)

    @Synchronized
    fun ensureInstalled() {
        check(System.getProperty("os.name").startsWith("Windows") && Native.POINTER_SIZE == 8) { "Служба защиты требует Windows x64" }
        val bytes = WindowsProtectionService::class.java.getResourceAsStream("/runtime/$FILE_NAME")?.use { it.readNBytes(20_000_001) }
            ?: error("В сборке отсутствует служба защиты Windows")
        check(bytes.size in 1..20_000_000) { "Неверный размер службы защиты Windows" }
        val root = installRoot()
        val executable = protectArtifact(bytes, FILE_NAME)
        val binary = "\"$executable\""
        manager(3).use { manager ->
            var service = api.OpenServiceW(manager.value, WString(ProtectionPlan.SERVICE_NAME), SERVICE_ACCESS)
            if (service == null) {
                check(Native.getLastError() == 1060) { "Не удалось проверить службу защиты (${Native.getLastError()})" }
                val dependencies = Memory(10).also {
                    it.clear()
                    it.setWideString(0, "BFE\u0000")
                }
                service = api.CreateServiceW(
                    manager.value, WString(ProtectionPlan.SERVICE_NAME), WString("LerNET — защита сети"),
                    SERVICE_ACCESS, 0x10, 2, 1, WString(binary), null, null, dependencies, null, null
                )
                check(service != null) { "Не удалось установить службу LerNET (${Native.getLastError()})" }
            }
            ServiceHandle(checkNotNull(service)).use { owned ->
                val existing = configuration(owned.value)
                check(
                    ServiceBinaryPolicy.isOwned(existing.binaryPath, root.toString()) &&
                        existing.serviceType == 0x10 &&
                        existing.account.equals("LocalSystem", true)
                ) {
                    "Служба с этим именем имеет другой путь или владельца; она не изменена"
                }
                // A new content-addressed binary applies on the next service start. Updating never clears filters.
                check(api.ChangeServiceConfigW(owned.value, -1, 2, -1, WString(binary), null, null, null, null, null, null) != 0) {
                    "Не удалось включить автоматический запуск службы LerNET (${Native.getLastError()})"
                }
                val current = Memory(36)
                check(api.QueryServiceStatusEx(owned.value, 0, current, 36, IntByReference()) != 0)
                if (current.getInt(4) == 4 && runningImage(current.getInt(28))?.equals(executable.toString(), true) != true) {
                    check(api.ControlService(owned.value, 1, Memory(28)) != 0) { "Не удалось остановить старую службу защиты" }
                    val stopDeadline = System.nanoTime() + 30_000_000_000L
                    while (true) {
                        check(api.QueryServiceStatusEx(owned.value, 0, current, 36, IntByReference()) != 0)
                        if (current.getInt(4) == 1) break
                        check(System.nanoTime() < stopDeadline) { "Старая служба защиты не остановилась; запрет сохранён" }
                        Thread.sleep(100)
                    }
                }
                val started = api.StartServiceW(owned.value, 0, null)
                check(started != 0 || Native.getLastError() == 1056) { "Не удалось запустить службу защиты (${Native.getLastError()})" }
                val deadline = System.nanoTime() + 10_000_000_000L
                while (true) {
                    val status = Memory(36)
                    check(api.QueryServiceStatusEx(owned.value, 0, status, 36, IntByReference()) != 0)
                    if (status.getInt(4) == 4) break
                    check(status.getInt(4) != 1 && System.nanoTime() < deadline) { "Служба защиты LerNET не перешла в рабочее состояние" }
                    Thread.sleep(100)
                }
            }
        }
        check(isReady()) { "Автоматическая служба защиты Windows не подтверждена" }
    }

    private fun protectArtifact(bytes: ByteArray, fileName: String, directoryDigest: String? = null): Path {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        val digest = hash.joinToString("") { "%02x".format(it) }
        val root = installRoot()
        rejectReparseParents(root)
        Files.createDirectories(root)
        secure(root)
        val directory = root.resolve(directoryDigest ?: digest)
        rejectReparseParents(directory)
        Files.createDirectories(directory)
        secure(directory)
        val executable = directory.resolve(fileName)
        protectFile(executable, bytes, allowUserExecute = fileName != CORE_FILE_NAME)
        return executable
    }

    private fun bundledCoreDigest(): String {
        val digest = WindowsProtectionService::class.java.getResourceAsStream("/runtime/$CORE_FILE_NAME.sha256")
            ?.use { it.readNBytes(128).toString(Charsets.US_ASCII).trim() } ?: error("В сборке отсутствует контрольная сумма ядра LerNET")
        check(Regex("[a-f0-9]{64}").matches(digest)) { "Неверная контрольная сумма ядра LerNET" }
        return digest
    }

    private fun hasProtectedArtifact(path: Path): Boolean = runCatching {
        rejectReparseParents(path)
        Files.isRegularFile(path) &&
            Files.size(path) in 1..20_000_000 &&
            listOf(path, path.parent, installRoot()).all { hasProtectedAcl(it) } &&
            fileSha256(path).joinToString("") { "%02x".format(it) }.equals(path.parent.fileName.toString(), true)
    }.getOrDefault(false)

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun fileSha256(path: Path): ByteArray = Files.newInputStream(path).use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
        hash.digest()
    }

    private fun bundledDependency(name: String): ByteArray {
        val bytes = WindowsProtectionService::class.java.getResourceAsStream("/runtime/$name")?.use { it.readNBytes(64_000_001) }
            ?: error("В сборке отсутствует комплектная библиотека $name")
        check(bytes.size in 1..64_000_000) { "Неверный размер библиотеки $name" }
        return bytes
    }

    private fun protectFile(path: Path, bytes: ByteArray, allowUserExecute: Boolean = true) {
        rejectReparseParents(path)
        if (!Files.exists(path)) Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        secure(path, allowUserExecute)
        check(
            Files.isRegularFile(path) &&
                Files.size(path) == bytes.size.toLong() &&
                MessageDigest.isEqual(fileSha256(path), sha256(bytes))
        ) {
            "Файл ${path.fileName} отличается от комплектной сборки"
        }
    }

    /** Caller must successfully remove LerNET's filters first. Other services are never stopped. */
    @Synchronized
    fun uninstallOwned() {
        manager(1).use { manager ->
            val service = api.OpenServiceW(manager.value, WString(ProtectionPlan.SERVICE_NAME), SERVICE_ACCESS)
            if (service == null) {
                check(Native.getLastError() == 1060) { "Не удалось прочитать службу LerNET (${Native.getLastError()})" }
                return@use
            }
            ServiceHandle(service).use { owned ->
                check(ServiceBinaryPolicy.isOwned(configuration(owned.value).binaryPath, installRoot().toString())) {
                    "Служба с этим именем не принадлежит LerNET и не удалена"
                }
                val status = Memory(28)
                val stopped = api.ControlService(owned.value, 1, status)
                check(stopped != 0 || Native.getLastError() == 1062) { "Не удалось остановить службу LerNET (${Native.getLastError()})" }
                check(api.DeleteService(owned.value) != 0) { "Не удалось удалить службу LerNET (${Native.getLastError()})" }
            }
        }
    }

    private fun rejectReparseParents(path: Path) {
        var current: Path? = path
        while (current != null) {
            val attributes = kernel.GetFileAttributesW(WString(current.toString()))
            if (attributes != -1) check(attributes and 0x400 == 0) { "Папка службы содержит перенаправление Windows: $current" }
            current = current.parent
        }
    }

    private fun secure(path: Path, allowUserExecute: Boolean = true) {
        val descriptor = PointerByReference()
        check(
            api.ConvertStringSecurityDescriptorToSecurityDescriptorW(
                WString("O:BAG:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;${if (allowUserExecute) "FRFX" else "FR"};;;BU)"), 1, descriptor, null
            ) != 0
        ) { "Не удалось создать права доступа службы (${Native.getLastError()})" }
        try {
            check(api.SetFileSecurityW(WString(path.toString()), 0x80000001.toInt() or 4, checkNotNull(descriptor.value)) != 0) {
                "Не удалось защитить файл службы от изменения без администратора (${Native.getLastError()})"
            }
        } finally {
            kernel.LocalFree(checkNotNull(descriptor.value))
        }
    }

    private fun hasProtectedAcl(path: Path, allowUserExecute: Boolean = true): Boolean = runCatching {
        val needed = IntByReference()
        api.GetFileSecurityW(WString(path.toString()), 1 or 4, null, 0, needed)
        if (needed.value !in 20..65_536) return@runCatching false
        val descriptor = Memory(needed.value.toLong())
        check(api.GetFileSecurityW(WString(path.toString()), 1 or 4, descriptor, needed.value, needed) != 0)
        val text = PointerByReference()
        check(api.ConvertSecurityDescriptorToStringSecurityDescriptorW(descriptor, 1, 1 or 4, text, null) != 0)
        try {
            ServiceFilePolicy.isProtectedDescriptor(checkNotNull(text.value).getWideString(0), allowUserExecute)
        } finally {
            kernel.LocalFree(checkNotNull(text.value))
        }
    }.getOrDefault(false)

    private data class ServiceConfiguration(val serviceType: Int, val startType: Int, val binaryPath: String, val account: String)

    private fun configuration(service: Pointer): ServiceConfiguration {
        val needed = IntByReference()
        api.QueryServiceConfigW(service, null, 0, needed)
        check(needed.value in 64..65_536) { "Неверный ответ Windows о конфигурации службы" }
        val data = Memory(needed.value.toLong())
        check(api.QueryServiceConfigW(service, data, needed.value, needed) != 0) { "Не удалось прочитать конфигурацию службы" }
        return ServiceConfiguration(
            data.getInt(0), data.getInt(4), data.getPointer(16).getWideString(0), data.getPointer(48).getWideString(0)
        )
    }

    private fun manager(access: Int): ServiceHandle = ServiceHandle(
        checkNotNull(api.OpenSCManagerW(null, null, access)) {
            "Нет доступа к службам Windows (${Native.getLastError()}); запустите LerNET от администратора"
        }
    )

    private class ServiceHandle(val value: Pointer) : AutoCloseable {
        override fun close() {
            check(api.CloseServiceHandle(value) != 0) { "Не удалось закрыть дескриптор службы" }
        }
    }

    internal interface ServiceApi : StdCallLibrary {
        fun OpenSCManagerW(machine: Pointer?, database: Pointer?, access: Int): Pointer?
        fun OpenServiceW(manager: Pointer, name: WString, access: Int): Pointer?
        fun CloseServiceHandle(handle: Pointer): Int
        fun QueryServiceConfigW(service: Pointer, data: Pointer?, size: Int, needed: IntByReference): Int
        fun ChangeServiceConfigW(
            service: Pointer,
            type: Int,
            start: Int,
            error: Int,
            binary: WString?,
            group: Pointer?,
            tag: Pointer?,
            dependencies: Pointer?,
            account: WString?,
            password: WString?,
            display: WString?
        ): Int
        fun CreateServiceW(
            manager: Pointer,
            name: WString,
            display: WString,
            access: Int,
            type: Int,
            start: Int,
            error: Int,
            binary: WString,
            group: Pointer?,
            tag: Pointer?,
            dependencies: Pointer?,
            account: WString?,
            password: WString?
        ): Pointer?
        fun StartServiceW(service: Pointer, count: Int, arguments: Pointer?): Int
        fun QueryServiceStatusEx(service: Pointer, level: Int, status: Pointer, size: Int, needed: IntByReference): Int
        fun ControlService(service: Pointer, control: Int, status: Pointer): Int
        fun DeleteService(service: Pointer): Int
        fun ConvertStringSecurityDescriptorToSecurityDescriptorW(
            text: WString,
            revision: Int,
            descriptor: PointerByReference,
            size: Pointer?
        ): Int
        fun SetFileSecurityW(path: WString, information: Int, descriptor: Pointer): Int
        fun GetFileSecurityW(path: WString, information: Int, descriptor: Pointer?, size: Int, needed: IntByReference): Int
        fun ConvertSecurityDescriptorToStringSecurityDescriptorW(
            descriptor: Pointer,
            revision: Int,
            information: Int,
            text: PointerByReference,
            length: Pointer?
        ): Int
    }

    internal interface ServiceKernel : StdCallLibrary {
        fun GetFileAttributesW(path: WString): Int
        fun LocalFree(memory: Pointer): Pointer?
        fun OpenProcess(access: Int, inherit: Int, pid: Int): Pointer?
        fun QueryFullProcessImageNameW(process: Pointer, flags: Int, name: Pointer, size: IntByReference): Int
        fun CloseHandle(handle: Pointer): Int
    }
}

internal object ServiceFilePolicy {
    fun isProtectedDescriptor(sddl: String, allowUserExecute: Boolean = true): Boolean {
        val owner = Regex("O:(.*?)(?=[GDS]:|$)").find(sddl)?.groupValues?.get(1)
        if (owner !in setOf("BA", "SY", "S-1-5-32-544", "S-1-5-18")) return false
        val dacl = sddl.substringAfter("D:", missingDelimiterValue = "").substringBefore("S:")
        if (!Regex("P(?:AI|AR){0,2}").matches(dacl.substringBefore('('))) return false
        val entries = Regex("\\(([^()]*)\\)").findAll(dacl).map { it.groupValues[1].split(';') }.toList()
        if (entries.size != 3) return false
        val trustees = mutableSetOf<String>()
        return entries.all { ace ->
            if (ace.size != 6 || ace[0] != "A" || ace[1].isNotEmpty() || ace[3].isNotEmpty() || ace[4].isNotEmpty()) return@all false
            val trustee = when (ace[5]) {
                "SY", "S-1-5-18" -> "SY"
                "BA", "S-1-5-32-544" -> "BA"
                "BU", "S-1-5-32-545" -> "BU"
                else -> return@all false
            }
            if (!trustees.add(trustee)) return@all false
            if (trustee == "BU") {
                ace[2] in (if (allowUserExecute) setOf("FRFX", "FXFR", "0x1200a9") else setOf("FR", "0x120089"))
            } else {
                ace[2] in setOf("FA", "0x1f01ff")
            }
        } &&
            trustees == setOf("SY", "BA", "BU")
    }
}

/** An unquoted SCM binary path, extra arguments, another folder or a junction must not be adopted. */
internal object ServiceBinaryPolicy {
    fun isOwned(binaryPath: String, installRoot: String, fileName: String = "lernet-protection-service.exe"): Boolean {
        if (!binaryPath.startsWith('"') || !binaryPath.endsWith('"')) return false
        val path = binaryPath.drop(1).dropLast(1)
        if ('"' in path || '/' in path || '\u0000' in path) return false
        val prefix = installRoot.trimEnd('\\') + "\\"
        if (!path.startsWith(prefix, true)) return false
        val relative = path.substring(prefix.length)
        return Regex("[a-f0-9]{64}\\\\${Regex.escape(fileName)}", RegexOption.IGNORE_CASE).matches(relative)
    }
}

/** A dependency-only update receives a new immutable directory even when core bytes stay identical. */
internal object ProtectedBundleIdentity {
    fun digest(coreDigest: String, dependencies: Map<String, String>): String {
        require(Regex("[a-f0-9]{64}").matches(coreDigest))
        require(dependencies.keys == setOf("wintun.dll", "libcronet.dll"))
        require(dependencies.values.all { Regex("[a-f0-9]{64}").matches(it) })
        val contents = "LerNET protected bundle v1\n$coreDigest\n" +
            dependencies.toSortedMap().entries.joinToString("\n") { (name, hash) -> "$name=$hash" }
        return MessageDigest.getInstance("SHA-256").digest(contents.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }
    }
}
