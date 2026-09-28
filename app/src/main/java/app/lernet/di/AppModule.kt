package app.lernet.di

import android.content.Context
import app.lernet.config.db.LerNetDatabase
import app.lernet.config.net.OkHttpTextFetcher
import app.lernet.config.net.RemoteTextFetcher
import app.lernet.config.parse.ImportCoordinator
import app.lernet.config.repo.ConfigRepository
import app.lernet.engine.BoxEngine
import app.lernet.engine.ConnectionController
import app.lernet.engine.compile.GeoRuleSetStore
import app.lernet.engine.net.AsnCaches
import app.lernet.engine.net.AsnFact
import app.lernet.engine.net.LocalGeoIp
import app.lernet.engine.net.OutboundDialer
import app.lernet.engine.net.RipeStatHopDetails
import app.lernet.engine.net.ShellPingTracer
import app.lernet.settings.SettingsStore
import app.lernet.ui.home.AndroidTunnelProbe
import app.lernet.vpn.AndroidProtectedDialer
import app.lernet.vpn.VpnRuntime
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): LerNetDatabase = LerNetDatabase.create(context)

    @Provides
    @Singleton
    fun repository(db: LerNetDatabase): ConfigRepository =
        ConfigRepository(
            profileDao = db.profileDao(),
            outboundDao = db.outboundDao(),
            ruleNodeDao = db.ruleNodeDao(),
            groupDao = db.groupDao(),
            groupMemberDao = db.groupMemberDao(),
            database = db,
        )

    @Provides
    @Singleton
    fun fetcher(): RemoteTextFetcher = OkHttpTextFetcher()

    @Provides
    @Singleton
    fun importer(fetcher: RemoteTextFetcher): ImportCoordinator = ImportCoordinator(fetcher)

    @Provides
    @Singleton
    fun settings(@ApplicationContext context: Context): SettingsStore = SettingsStore(context)

    @Provides
    @Singleton
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Provides
    @Singleton
    fun outboundDialer(): OutboundDialer = AndroidProtectedDialer()

    @Provides
    @Singleton
    fun connectionController(
        engine: BoxEngine,
        scope: CoroutineScope,
        dialer: OutboundDialer,
        @ApplicationContext context: Context,
    ): ConnectionController = ConnectionController(
        engine,
        scope,
        outboundDialer = dialer,
        tunnelHealthProbe = AndroidTunnelProbe::measure,
        ruleSetDirectory = GeoRuleSetStore.install(context),
    ).also { controller ->
        AsnCaches.protect = VpnRuntime::protectDatagram
        val localGeoIp = LocalGeoIp { context.assets.open("hop-geoip.idx") }
        val hopDetails = RipeStatHopDetails()
        controller.pathTracer = ShellPingTracer(annotate = { ip ->
            localGeoIp.country(ip)?.let { AsnFact(it, null, null) } ?: AsnCaches.shared.lookup(ip)
        }, details = hopDetails::lookup)
    }
}
