package app.lernet.ui.diag

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.lernet.R
import app.lernet.ui.icons.LerNetSymbols

@Composable
internal fun rememberAppFace(uid: Int?, pid: Long?, processApp: String?): AppFace {
    val context = LocalContext.current
    return remember(uid, pid, processApp) {
        val packages = context.packageManager
        val owned = packagesFor(packages, uid)
        AppIdentity.resolve(
            uid = uid,
            pid = pid,
            packagesForUid = owned,
            processPackages = listOfNotNull(processApp),
            hasLauncher = { pkg -> launcherVisible(packages, pkg) },
            labelForPackage = { pkg -> applicationLabelOrNull(packages, pkg) },
        )
    }
}

@Composable
internal fun appFaceTitle(face: AppFace): String = when (face) {
    is AppFace.Installed -> face.label
    AppFace.System -> stringResource(R.string.diag_system)
    is AppFace.Uid -> stringResource(R.string.diag_uid, face.uid)
    is AppFace.Pid -> stringResource(R.string.diag_pid, face.pid.toString())
}

@Composable
internal fun DiagAppIcon(face: AppFace) {
    FaceIcon(face)
}

@Composable
private fun FaceIcon(face: AppFace) {
    when (face) {
        is AppFace.Installed -> InstalledIcon(face.packageName)
        AppFace.System -> Icon(
            LerNetSymbols.settings(),
            contentDescription = stringResource(R.string.diag_system),
            modifier = Modifier.size(40.dp),
        )
        is AppFace.Uid, is AppFace.Pid -> MarkerIcon()
    }
}

@Composable
private fun InstalledIcon(packageName: String) {
    val context = LocalContext.current
    val bitmap = remember(packageName) { loadIcon(context.packageManager, packageName) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(40.dp),
        )
    } else {
        MarkerIcon()
    }
}

@Composable
private fun MarkerIcon() {
    Icon(
        LerNetSymbols.info(),
        contentDescription = null,
        modifier = Modifier.size(40.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun PidLine(pid: Long) {
    Text(
        stringResource(R.string.diag_pid, pid.toString()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun applicationLabelOrNull(packages: PackageManager, pkg: String): String? {
    if (!pkg.contains('.')) return null
    val info = runCatching { packages.getApplicationInfo(pkg, 0) }.getOrNull() ?: return null
    return packages.getApplicationLabel(info).toString().ifBlank { null }
}

private fun packagesFor(packages: PackageManager, uid: Int?): List<String> {
    if (uid == null) return emptyList()
    return runCatching { packages.getPackagesForUid(uid)?.toList().orEmpty() }.getOrDefault(emptyList())
}

private fun launcherVisible(packages: PackageManager, pkg: String): Boolean {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
    val found = runCatching { packages.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY) }
    return found.getOrDefault(emptyList()).isNotEmpty()
}

private fun loadIcon(packages: PackageManager, packageName: String): Bitmap? {
    if (!packageName.contains('.')) return null
    val icon: Drawable = runCatching { packages.getApplicationIcon(packageName) }.getOrNull() ?: return null
    return icon.toBitmap(width = 96, height = 96)
}
