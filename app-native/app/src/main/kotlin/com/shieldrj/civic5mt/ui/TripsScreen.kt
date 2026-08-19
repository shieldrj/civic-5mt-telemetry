package com.shieldrj.civic5mt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shieldrj.civic5mt.data.TripDatabase
import com.shieldrj.civic5mt.data.TripEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Every drive, newest first.
 *
 * The figure a driver actually compares between drives is economy, so that is what each row
 * leads with. Distance and duration are context for it; the eco score is a summary of things
 * already shown and sits last.
 *
 * Simulated drives are kept and labelled rather than hidden. They are useful while developing
 * and must never be mistaken for real ones, and a row that says so is a better guarantee of
 * that than a filter someone can forget to apply.
 */
@Composable
fun TripsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dao = remember { TripDatabase.get(context).tripDao() }
    val trips by dao.observeRecentTrips().collectAsStateWithLifecycle(emptyList())

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(
            text = "TRIP HISTORY",
            color = CivicColors.Ink3,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(18.dp))

        if (trips.isEmpty()) {
            Text(
                text = "No drives recorded yet. A drive is saved from the moment you connect, " +
                    "so one that ends badly is still kept.",
                color = CivicColors.Ink3,
                fontSize = 14.sp,
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(trips, key = { it.id }) { trip ->
                    TripRow(trip)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(CivicColors.Hairline)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "Back",
            color = CivicColors.Accent,
            fontSize = 15.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onBack)
                .padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun TripRow(trip: TripEntity) {
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "%.1f".format(trip.avgMpg),
                    color = CivicColors.Ink,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.alignByBaseline(),
                )
                Spacer(Modifier.height(0.dp))
                Text(
                    text = " MPG",
                    color = CivicColors.Ink3,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.4.sp,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            Text(
                text = formatWhen(trip.startedAt),
                color = CivicColors.Ink3,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "%.1f mi · %s · %d eco".format(
                    trip.distanceMiles,
                    formatDuration(trip.durationSec),
                    trip.ecoScore,
                ),
                color = CivicColors.Ink2,
                fontSize = 13.sp,
            )
            if (trip.simulated) {
                Text(
                    text = "SIMULATED",
                    color = CivicColors.Warn,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.4.sp,
                )
            }
        }
    }
}

private fun formatWhen(millis: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(millis))

private fun formatDuration(seconds: Double): String {
    val total = seconds.roundToInt()
    val minutes = total / 60
    return if (minutes >= 60) {
        "%dh %02dm".format(minutes / 60, minutes % 60)
    } else {
        "%dm %02ds".format(minutes, total % 60)
    }
}
