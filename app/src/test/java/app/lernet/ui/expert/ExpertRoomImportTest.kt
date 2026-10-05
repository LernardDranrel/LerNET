package app.lernet.ui.expert

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.lernet.config.db.LerNetDatabase
import app.lernet.config.policy.ExternalExitKind
import app.lernet.config.policy.ExternalExitProfiles
import app.lernet.config.policy.ExternalExitRequest
import app.lernet.config.repo.ConfigRepository
import app.lernet.config.transfer.TransferBundle
import app.lernet.config.transfer.TransferCodec
import app.lernet.config.transfer.TransferGroup
import app.lernet.config.transfer.TransferOutbound
import app.lernet.config.transfer.TransferProfile
import app.lernet.config.transfer.TransferRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ExpertRoomImportTest {
    private fun bundle(id: String) = TransferBundle(
        scope = "all",
        profiles = listOf(
            TransferProfile(
                id, "Test profile", "JSON_PASTE",
                listOf(
                    TransferOutbound(
                        "out-$id", "proxy", "socks", "{\"type\":\"socks\",\"server\":\"example.invalid\",\"server_port\":1080}",
                    )
                ),
                "out-$id", dnsPolicy = "PROFILE", dnsJson = "{}", modeOverride = "FULL_VPN"
            )
        ),
        groups = listOf(TransferGroup("group-$id", "Test group", listOf(id), true)),
        rules = listOf(TransferRule("rule-$id", id, sortIndex = 0, action = "proxy", processes = listOf("test.exe"))),
    )

    @Test
    fun exactImportRetainsIdsPlatformFieldsAndAddsNoCatchAll() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LerNetDatabase::class.java).build()
        try {
            val repository = ConfigRepository(
                db.profileDao(), db.outboundDao(), db.ruleNodeDao(), db.groupDao(), db.groupMemberDao(), database = db,
            )
            val expected = bundle("shared-id")
            repository.restoreTransferExact(expected)
            val actual = TransferCodec.decode(repository.exportTransfer())
            assertThat(actual.profiles).isEqualTo(expected.profiles)
            assertThat(actual.groups).isEqualTo(expected.groups)
            assertThat(actual.rules).isEqualTo(expected.rules)
        } finally {
            db.close()
        }
    }

    @Test
    fun roomRollbackRetainsOldInventoryWhenInsertionFailsAfterDeletion() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LerNetDatabase::class.java).build()
        try {
            val repository = ConfigRepository(
                db.profileDao(), db.outboundDao(), db.ruleNodeDao(), db.groupDao(), db.groupMemberDao(), database = db,
            )
            val original = bundle("original")
            repository.restoreTransferExact(original)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_new_outbound BEFORE INSERT ON outbounds WHEN NEW.id = 'out-replacement' " +
                    "BEGIN SELECT RAISE(ABORT, 'fixture insertion failure'); END",
            )
            val result = runCatching { repository.restoreTransferExact(bundle("replacement")) }
            assertThat(result.isFailure).isTrue()
            assertThat(TransferCodec.decode(repository.exportTransfer())).isEqualTo(original)
        } finally {
            db.close()
        }
    }

    @Test
    fun externalEditRetainsReferencesOrderAndMetadataAndRejectsStaleEditor() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LerNetDatabase::class.java).build()
        try {
            val repository = ConfigRepository(
                db.profileDao(), db.outboundDao(), db.ruleNodeDao(), db.groupDao(),
                db.groupMemberDao(), nowMs = { 1000L }, database = db
            )
            val original = bundle("external")
            repository.restoreTransferExact(original)
            val expected = TransferCodec.decode(repository.exportTransfer()).profiles.single()
            val originalEntity = requireNotNull(db.profileDao().getProfile(expected.id))
            val updated = repository.saveExternalExit(
                ExternalExitRequest(ExternalExitKind.SOCKS5, "Changed name", "proxy.example.invalid", 2080), expected,
            )
            val after = TransferCodec.decode(repository.exportTransfer())
            assertThat(updated.id).isEqualTo(expected.id)
            assertThat(updated.selectedOutboundId).isEqualTo(expected.selectedOutboundId)
            assertThat(after.rules).isEqualTo(original.rules)
            assertThat(after.groups).isEqualTo(original.groups)
            assertThat(updated.dnsJson).isEqualTo(expected.dnsJson)
            assertThat(updated.modeOverride).isEqualTo(expected.modeOverride)
            val entity = requireNotNull(db.profileDao().getProfile(expected.id))
            assertThat(entity.createdAtEpochMs).isEqualTo(originalEntity.createdAtEpochMs)
            assertThat(entity.sortIndex).isEqualTo(originalEntity.sortIndex)
            assertThat(ExternalExitProfiles.describe(updated)?.port).isEqualTo(2080)
            val stale = runCatching {
                repository.saveExternalExit(
                    ExternalExitRequest(ExternalExitKind.HTTP, "Stale name", "proxy.example.invalid", 8080), expected,
                )
            }
            assertThat(stale.isFailure).isTrue()
            assertThat(TransferCodec.decode(repository.exportTransfer())).isEqualTo(after)
        } finally {
            db.close()
        }
    }

    @Test
    fun failedExternalEditRollsBackProfileAndSuccessfulCreateAddsNoImplicitRules() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LerNetDatabase::class.java).build()
        try {
            val repository = ConfigRepository(
                db.profileDao(), db.outboundDao(), db.ruleNodeDao(), db.groupDao(),
                db.groupMemberDao(), database = db
            )
            val original = bundle("external")
            repository.restoreTransferExact(original)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_external_outbound BEFORE INSERT ON outbounds WHEN NEW.id = 'out-external' " +
                    "BEGIN SELECT RAISE(ABORT, 'fixture insertion failure'); END",
            )
            val failed = runCatching {
                repository.saveExternalExit(
                    ExternalExitRequest(ExternalExitKind.SOCKS5, "Unsaved name", "proxy.example.invalid", 2080),
                    original.profiles.single()
                )
            }
            assertThat(failed.isFailure).isTrue()
            assertThat(TransferCodec.decode(repository.exportTransfer())).isEqualTo(original)
            val created = repository.saveExternalExit(ExternalExitRequest(ExternalExitKind.SOCKS5, "New external", "127.0.0.1", 1080))
            val after = TransferCodec.decode(repository.exportTransfer())
            assertThat(after.profiles.map { it.id }).containsExactly("external", created.id).inOrder()
            assertThat(after.rules).isEqualTo(original.rules)
            assertThat(after.groups).isEqualTo(original.groups)
        } finally {
            db.close()
        }
    }
}
