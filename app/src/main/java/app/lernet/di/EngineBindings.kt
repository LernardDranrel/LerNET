package app.lernet.di

import app.lernet.engine.BoxEngine
import app.lernet.engine.LibboxBoxEngine
import app.lernet.vpn.AndroidEngineProcessHost
import app.lernet.vpn.EngineProcessHost
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EngineBindings {
    @Binds
    @Singleton
    abstract fun boxEngine(impl: LibboxBoxEngine): BoxEngine

    @Binds
    @Singleton
    abstract fun processHost(impl: AndroidEngineProcessHost): EngineProcessHost
}
