package com.shieldrj.civic5mt

import android.app.Application
import android.util.Log
import com.shieldrj.civic5mt.service.PrefsClutchProfileStore
import com.shieldrj.civic5mt.service.PrefsFuelCalibrationStore
import com.shieldrj.civic5mt.service.PrefsLifetimeStore
import com.shieldrj.civic5mt.service.PrefsOilProfileStore
import com.shieldrj.civic5mt.service.importRescuedRecordsOnce
import com.shieldrj.civic5mt.service.loadFuelBlend
import com.shieldrj.civic5mt.service.publishPersistedRecords

/**
 * Runs before any Activity or Service, which is the only place the migration belongs.
 *
 * It used to sit in the service, and that was wrong in a way worth recording: the service
 * only starts when you connect to an adapter, so on a phone that had never managed a
 * connection the rescued lifetime record was sitting in res/raw un-imported, and the screen
 * had nothing to show. Storage-level work that must happen before anything reads storage
 * happens here, once per process.
 *
 * What to publish, and how each record has to be read, lives in [publishPersistedRecords]
 * rather than here - it takes stores rather than a Context so that a JVM test can hold it to
 * the rule this class exists to enforce. All that is left here is opening the files.
 */
class Civic5MTApp : Application() {

    override fun onCreate() {
        super.onCreate()

        importRescuedRecordsOnce(this)?.let { Log.i(TAG, it) }

        publishPersistedRecords(
            lifetime = PrefsLifetimeStore(this),
            oil = PrefsOilProfileStore(this),
            clutch = PrefsClutchProfileStore(this),
            calibration = PrefsFuelCalibrationStore(this),
            blend = loadFuelBlend(this),
        )
    }

    private companion object {
        const val TAG = "Civic5MT"
    }
}
