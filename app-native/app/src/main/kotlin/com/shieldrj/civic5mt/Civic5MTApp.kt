package com.shieldrj.civic5mt

import android.app.Application
import android.util.Log
import com.shieldrj.civic5mt.core.OilLifeEngine
import com.shieldrj.civic5mt.service.PrefsLifetimeStore
import com.shieldrj.civic5mt.service.PrefsOilProfileStore
import com.shieldrj.civic5mt.service.TelemetryState
import com.shieldrj.civic5mt.service.importRescuedRecordsOnce
import com.shieldrj.civic5mt.service.loadFuelBlend

/**
 * Runs before any Activity or Service, which is the only place the migration belongs.
 *
 * It used to sit in the service, and that was wrong in a way worth recording: the service
 * only starts when you connect to an adapter, so on a phone that had never managed a
 * connection the rescued lifetime record was sitting in res/raw un-imported, and the screen
 * had nothing to show. Storage-level work that must happen before anything reads storage
 * happens here, once per process.
 */
class Civic5MTApp : Application() {

    override fun onCreate() {
        super.onCreate()

        importRescuedRecordsOnce(this)?.let { Log.i(TAG, it) }

        // Publish what is on disk immediately. The lifetime figure and the oil profile are
        // persisted facts rather than live readings - they should be legible with the car
        // parked, the adapter unplugged and Bluetooth off.
        PrefsLifetimeStore(this).load()?.let { TelemetryState.setLifetime(it) }
        // Through the engine rather than straight off the file, so what the Oil screen
        // shows is the recomputed view of the stored measurements. Reading the file
        // directly published the derived figures exactly as they were last written, which
        // is how a corrected interval estimate stayed invisible behind the old one.
        if (PrefsOilProfileStore(this).load() != null) {
            TelemetryState.setOil(OilLifeEngine(PrefsOilProfileStore(this)).getProfile())
        }
        TelemetryState.setFuelBlend(loadFuelBlend(this))
    }

    private companion object {
        const val TAG = "Civic5MT"
    }
}
