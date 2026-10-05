package app.lernet.engine.expert

import app.lernet.engine.policy.ExitPhase
import app.lernet.engine.policy.PolicyControlCapabilities
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.PlatformInterface
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

data class ExpertNativeAck(val instanceId: String, val interfaceId: String, val revision: Long) {
    companion object {
        fun decode(raw: String): ExpertNativeAck {
            val body = ExpertNativeJson.objectValue(raw, 8_192, "Native Expert acknowledgement")
            fun identifier(key: String): String = ExpertNativeJson.string(body, key)
                ?.takeIf { it.length in 1..512 && it.matches(Regex("[A-Za-z0-9._:/-]+")) }
                ?: error("Native Expert acknowledgement has no valid $key")
            val revision = ExpertNativeJson.long(body, "revision")?.takeIf { it >= 0 }
                ?: error("Native Expert acknowledgement has no revision")
            return ExpertNativeAck(identifier("instance_id"), identifier("interface_id"), revision)
        }
    }
}

object ExpertNativeCapabilityProbe {
    fun decode(raw: String): PolicyControlCapabilities {
        val body = ExpertNativeJson.objectValue(raw, 8_192, "Native Expert capabilities")
        fun enabled(key: String): Boolean = ExpertNativeJson.boolean(body, key) == true
        val version = ExpertNativeJson.long(body, "protocol_version")
        if (version != 1L) return PolicyControlCapabilities.RESTART_ONLY
        return PolicyControlCapabilities(
            preservesTun = enabled("preserves_tun") && enabled("hot_policy_apply"),
            atomicRules = enabled("atomic_prepare_commit") && enabled("destination_redirect"),
            independentExits = enabled("independent_exit_lifecycle") && enabled("bounded_first_flow_wait"),
            nativeHealthRecovery = enabled("native_health_recovery"),
        )
    }
}

/** Native protocol readiness and HTTPS health are separate evidence. */
internal object ExpertNativeStatusEvidence {
    fun exitPhase(phase: String?, health: String?): ExitPhase =
        app.lernet.engine.policy.ExpertNativeStatusEvidence.exitPhase(phase, health)
}

/** Optional API is probed on the actual packaged AAR; an old AAR never claims hot-policy support. */
class ExpertNativeBridge {
    val capabilities: PolicyControlCapabilities
        get() = try {
            val method = Libbox::class.java.getMethod("expertCapabilities")
            ExpertNativeCapabilityProbe.decode(invoke(method, null) as String)
        } catch (_: ReflectiveOperationException) {
            PolicyControlCapabilities.RESTART_ONLY
        } catch (_: IllegalArgumentException) {
            PolicyControlCapabilities.RESTART_ONLY
        } catch (_: IllegalStateException) {
            PolicyControlCapabilities.RESTART_ONLY
        } catch (_: Exception) {
            PolicyControlCapabilities.RESTART_ONLY
        } catch (_: LinkageError) {
            PolicyControlCapabilities.RESTART_ONLY
        }

    fun create(ingressJson: String, platform: PlatformInterface): ExpertNativeSession {
        val factory = Libbox::class.java.getMethod("newExpertSession", String::class.java, PlatformInterface::class.java)
        val session = invoke(factory, null, ingressJson, platform) ?: error("Native Expert factory returned no session")
        return ExpertNativeSession(session)
    }

    companion object {
        internal fun invoke(method: Method, owner: Any?, vararg arguments: Any?): Any? = try {
            method.invoke(owner, *arguments)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }
}

class ExpertNativeSession(private val native: Any) {
    private val type = native.javaClass
    private var closeConfirmed = false
    private var closeFailure: Throwable? = null

    fun start(revision: Long, policyJson: String, exitManifestJson: String): ExpertNativeAck =
        ExpertNativeAck.decode(call("start", revision, policyJson, exitManifestJson) as String)

    fun apply(previous: ExpertNativeAck, revision: Long, policyJson: String, exitManifestJson: String): ExpertNativeAck =
        ExpertNativeAck.decode(
            call("apply", previous.instanceId, previous.interfaceId, previous.revision, revision, policyJson, exitManifestJson) as String,
        )

    @Synchronized
    fun close() {
        if (closeConfirmed) return
        closeFailure?.let { previous ->
            // An older native bridge can return nil after setting its closed flag even
            // though the first close failed. Only explicit resource-drain proof resolves
            // that uncertainty; a repeated nil must not release the Android ownership lease.
            val proven = runCatching {
                val body = ExpertNativeJson.objectValue(status(), 2_000_000, "Native Expert close status")
                ExpertNativeJson.boolean(body, "close_confirmed") == true
            }.getOrDefault(false)
            if (proven) {
                closeConfirmed = true
                closeFailure = null
                return
            }
            throw IllegalStateException("Native Expert resource closure is unconfirmed", previous)
        }
        try {
            call("close")
            closeConfirmed = true
        } catch (failure: Throwable) {
            closeFailure = failure
            throw failure
        }
    }

    fun status(): String = call("status") as String

    fun networkChanged() {
        call("networkChanged")
    }

    fun wakeExit(previous: ExpertNativeAck, tag: String) {
        call("wakeExitAt", previous.instanceId, previous.interfaceId, previous.revision, tag)
    }

    fun recoverExit(previous: ExpertNativeAck, tag: String) {
        call("recoverExitAt", previous.instanceId, previous.interfaceId, previous.revision, tag)
    }

    fun sleepExit(previous: ExpertNativeAck, tag: String) {
        call("sleepExitAt", previous.instanceId, previous.interfaceId, previous.revision, tag)
    }

    fun probeExit(previous: ExpertNativeAck, tag: String, url: String, timeoutMs: Long): String =
        call("probeExitAt", previous.instanceId, previous.interfaceId, previous.revision, tag, url, timeoutMs) as String

    private fun call(name: String, vararg arguments: Any?): Any? {
        val parameters = arguments.map { argument ->
            when (argument) {
                is Long -> Long::class.javaPrimitiveType!!
                is String -> String::class.java
                else -> error("Unsupported native Expert argument type")
            }
        }.toTypedArray()
        return ExpertNativeBridge.invoke(type.getMethod(name, *parameters), native, *arguments)
    }
}
