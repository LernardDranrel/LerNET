package app.lernet.ui.diag

import android.content.pm.PackageManager
import app.lernet.engine.live.LiveConn
import app.lernet.engine.live.LiveConnStatus
import app.lernet.engine.live.LiveVia

/** Debug screenshot rows. Shown only when the debug intent extra is set. */
internal object DiagSample {
    const val EXTRA = "lernet_diag_sample"

    private val known = listOf(
        "com.android.settings",
        "com.android.chrome",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
    )

    fun rows(packages: PackageManager, selfPackage: String): List<LiveConn> {
        val installed = installed(packages, selfPackage)
        return listOfNotNull(installed, system(), rawUid())
    }

    private fun installed(packages: PackageManager, selfPackage: String): LiveConn? {
        val pkg = pick(packages, listOf(selfPackage) + known) ?: return null
        val uid = runCatching { packages.getApplicationInfo(pkg, 0).uid }.getOrNull()
        return conn("sample-app", pkg, uid, 4100L, "example.com", "203.0.113.10")
    }

    private fun system(): LiveConn =
        conn("sample-system", "?", 1000, 1L, null, "8.8.8.8")

    private fun rawUid(): LiveConn =
        conn("sample-uid", "?", 10_245, 50L, null, "203.0.113.50")

    private fun pick(packages: PackageManager, names: List<String>): String? =
        names.firstOrNull { name ->
            runCatching { packages.getApplicationInfo(name, 0) }.isSuccess
        }

    private fun conn(
        id: String,
        app: String,
        uid: Int?,
        pid: Long?,
        domain: String?,
        host: String,
    ): LiveConn = LiveConn(
        id = id,
        app = app,
        uid = uid,
        destHost = host,
        destPort = 443,
        domain = domain,
        outbound = "proxy",
        via = LiveVia.PROXY,
        uplink = 64,
        downlink = 0,
        status = LiveConnStatus.UNFINISHED,
        pid = pid,
    )
}
