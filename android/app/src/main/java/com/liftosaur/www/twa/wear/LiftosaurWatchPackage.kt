package com.liftosaur.www.twa.wear

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.liftosaur.www.twa.specs.NativeLiftosaurWatchSpec

class LiftosaurWatchPackage : BaseReactPackage() {

    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
        when (name) {
            NativeLiftosaurWatchSpec.NAME -> LiftosaurWatchModule(reactContext)
            else -> null
        }

    override fun getReactModuleInfoProvider(): ReactModuleInfoProvider =
        ReactModuleInfoProvider {
            mapOf(
                NativeLiftosaurWatchSpec.NAME to ReactModuleInfo(
                    NativeLiftosaurWatchSpec.NAME,
                    LiftosaurWatchModule::class.java.name,
                    false, false, false, true
                )
            )
        }
}
