package com.shieldrj.civic5mt

import android.app.Application
import android.util.Log
import com.shieldrj.civic5mt.service.PrefsLifetimeStore
import com.shieldrj.civic5mt.service.PrefsOilProfileStore
import com.shieldrj.civic5mt.service.TelemetryState
import com.shieldrj.civic5mt.service.importRescuedRecordsOnce

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
        PrefsOilProfileStore(this).load()?.let { TelemetryState.setOil(it) }
    }

    private companion object {
        const val TAG = "Civic5MT"
    }
}
