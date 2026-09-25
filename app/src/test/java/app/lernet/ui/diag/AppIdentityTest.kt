package app.lernet.ui.diag

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppIdentityTest {
    @Test
    fun uidPackagesWinAndADomainIsNotAnInput() {
        val face = AppIdentity.resolve(
            uid = 10380,
            pid = 440,
            packagesForUid = listOf("ru.yandex.music"),
            hasLauncher = { true },
            labelForPackage = { pkg -> if (pkg == "ru.yandex.music") "Яндекс Музыка" else null },
        )
        assertThat(face).isEqualTo(AppFace.Installed("Яндекс Музыка", "ru.yandex.music"))
    }

    @Test
    fun sharedUidPrefersTheLauncherPackage() {
        val face = AppIdentity.resolve(
            uid = 10380,
            pid = 12,
            packagesForUid = listOf("com.google.android.gms", "ru.yandex.music"),
            hasLauncher = { it == "ru.yandex.music" },
            labelForPackage = { pkg ->
                when (pkg) {
                    "com.google.android.gms" -> "Google Play services"
                    "ru.yandex.music" -> "Яндекс Музыка"
                    else -> null
                }
            },
        )
        assertThat(face).isEqualTo(AppFace.Installed("Яндекс Музыка", "ru.yandex.music"))
    }

    @Test
    fun aSharedSystemUidStaysSystem() {
        val face = AppIdentity.resolve(
            uid = 1000,
            pid = 88,
            packagesForUid = listOf("android", "com.android.systemui"),
            hasLauncher = { false },
            labelForPackage = { it },
        )
        assertThat(face).isEqualTo(AppFace.System)
    }

    @Test
    fun oneSystemPackageKeepsItsLabel() {
        val face = AppIdentity.resolve(
            uid = 1000,
            pid = 4,
            packagesForUid = listOf("com.android.settings"),
            hasLauncher = { true },
            labelForPackage = { "Настройки" },
        )
        assertThat(face).isEqualTo(AppFace.Installed("Настройки", "com.android.settings"))
    }

    @Test
    fun processPackageFromLibboxLabelsWhenUidLookupIsEmpty() {
        val face = AppIdentity.resolve(
            uid = 10352,
            pid = 12,
            packagesForUid = emptyList(),
            processPackages = listOf("ru.yandex.music"),
            hasLauncher = { true },
            labelForPackage = { pkg -> if (pkg == "ru.yandex.music") "Яндекс Музыка" else null },
        )
        assertThat(face).isEqualTo(AppFace.Installed("Яндекс Музыка", "ru.yandex.music"))
    }

    @Test
    fun packageTokensTakePackagesFromProcessPathsNotHosts() {
        assertThat(AppIdentity.packageTokens("/data/app/~~x==/ru.yandex.music-AbCd/base.apk"))
            .contains("ru.yandex.music")
        assertThat(AppIdentity.packageTokens("ynison.music.yandex.net")).isEmpty()
        assertThat(AppIdentity.packageTokens("ru.yandex.music")).containsExactly("ru.yandex.music")
    }

    @Test
    fun aHiddenPackageStaysTheUid() {
        val face = AppIdentity.resolve(
            uid = 10352,
            pid = 12,
            packagesForUid = emptyList(),
            hasLauncher = { false },
            labelForPackage = { null },
        )
        assertThat(face).isEqualTo(AppFace.Uid(10352))
    }
}
