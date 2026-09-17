package com.shieldrj.civic5mt.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shieldrj.civic5mt.core.ConnectionStatus

/**
 * The four places this app goes.
 *
 * Four rather than the six screens there are, because a bar of six is a bar nobody aims at -
 * and because oil, clutch and fault codes answer one question between them. They live behind
 * [Tab.Health] as sections of a page rather than as three separate destinations.
 *
 * The order is the order they are wanted in: the gauge while moving, fuel at the pump, health
 * in the driveway, history afterwards.
 */
enum class Tab(val label: String, val icon: ImageVector) {
    Drive("Drive", Icons.Outlined.Speed),
    Fuel("Fuel", Icons.Outlined.LocalGasStation),
    Health("Health", Icons.Outlined.MonitorHeart),
    Trips("Trips", Icons.Outlined.History),
}

/**
 * How the connection reads, in the one place it is ever drawn.
 *
 * Six [ConnectionStatus] values collapse to four things a driver can act on: it is off, it is
 * working on it, it is live, or it went wrong. The state that used to need the most explaining
 * is [ConnectionStatus.SIMULATING] - the bench produced numbers that looked exactly like the
 * real ones, so it says so on the same line as everything else rather than in a caption
 * further down the screen.
 */
private data class ConnectionFace(
    val headline: String,
    val tone: Color,
    /** True while something is in flight, which is what the dot pulses to mean. */
    val working: Boolean,
    /** The one button that changes this state, or null when only waiting will. */
    val action: String?,
)

private fun faceOf(status: ConnectionStatus, canConnect: Boolean): ConnectionFace = when (status) {
    ConnectionStatus.CONNECTED ->
        ConnectionFace("Connected", CivicColors.Good, false, "Stop")

    ConnectionStatus.SIMULATING ->
        ConnectionFace("Simulated drive - not recorded", CivicColors.Warn, false, "Stop")

    ConnectionStatus.CONNECTING ->
        ConnectionFace("Connecting...", CivicColors.Warn, true, "Cancel")

    // Deliberately not "disconnected". The drive is still open and the figures on screen are
    // the last real ones; saying it lost the adapter says which half is missing.
    ConnectionStatus.RECONNECTING ->
        ConnectionFace("Adapter lost - reconnecting", CivicColors.Warn, true, "Stop")

    ConnectionStatus.ERROR ->
        ConnectionFace(
            headline = "Adapter error",
            tone = CivicColors.Accent,
            working = false,
            action = if (canConnect) "Try again" else null,
        )

    // No button at all until an adapter has answered once. "Connect" with nothing to
    // connect to is a button that can only disappoint; the Drive tab offers the paired
    // list instead.
    ConnectionStatus.DISCONNECTED ->
        ConnectionFace(
            headline = "Not connected",
            tone = CivicColors.Ink3,
            working = false,
            action = if (canConnect) "Connect" else null,
        )
}

/**
 * The state of the link, in the same place on every screen.
 *
 * This is the whole of the fix for "is it on or not". Before, the answer was the shape of the
 * screen: connected got a gauge, disconnected got an unrelated scrolling page, and neither
 * carried a label saying which it was. Reading the state meant recognising the layout. Now the
 * layout never changes and one line says it, with the button that changes it on the same row -
 * so "connect", "stop" and "why is nothing happening" are all answered in one place.
 */
@Composable
private fun ConnectionBar(
    status: ConnectionStatus,
    detail: String?,
    canConnect: Boolean,
    compact: Boolean,
    onAction: () -> Unit,
    onSettings: () -> Unit,
) {
    val face = faceOf(status, canConnect)

    // A dot that only has a colour cannot separate "working on it" from "stuck". Amber holds
    // still for an error you must act on and breathes while something is actually in flight.
    val pulse by rememberInfiniteTransition(label = "link").animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "pulse",
    )

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (compact) 52.dp else 60.dp)
                .padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .alpha(if (face.working) pulse else 1f)
                    .clip(CircleShape)
                    .background(face.tone)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = face.headline,
                    color = face.tone,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                // The connection's running commentary, which is where the failures explain
                // themselves. It used to be drawn once, on the disconnected page only, so the
                // sentence naming the cause of a failure vanished the moment the app decided
                // it was connected enough to show a gauge.
                //
                // Dropped in a short window. That is the phone on the car mount in landscape,
                // where the height this line costs comes straight off the gauge, and where the
                // headline above it already says which of the six states this is.
                if (!compact) {
                    Text(
                        text = detail?.takeIf { it.isNotBlank() && it != face.headline }
                            ?: "2013 Civic LX - 1.8L 5-speed",
                        color = CivicColors.Ink3,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            face.action?.let { label ->
                Spacer(Modifier.width(12.dp))
                BarAction(label, face.tone, onAction)
            }

            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    tint = CivicColors.Ink3,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(CivicColors.Hairline))
    }
}

/**
 * The button that changes the link, sized to be hit in a moving car.
 *
 * 48dp tall because that is the floor for a touch target, and the thing it replaced was a
 * 15sp word with 4dp of padding sitting in a row of five other 15sp words - one of which
 * ended the drive.
 */
@Composable
private fun BarAction(label: String, tone: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(tone.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = tone, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * The shell: state on top, destinations down the side or along the bottom, one screen between.
 *
 * [NavigationSuiteScaffold] picks the bar for itself from the window size, which is why there
 * is no orientation branch here. Portrait gets a bottom bar; the car mount is landscape, where
 * the window is short and a bottom bar would take the height the gauge wants, so it gets a
 * rail on the side instead.
 *
 * The selected tab is drawn in ink rather than the accent colour. The accent means one thing
 * in this app - something needs attention - and spending it on "you are on the Fuel tab"
 * would leave a red mark permanently on screen, which is how a warning light stops working.
 */
@OptIn(ExperimentalMaterial3AdaptiveNavigationSuiteApi::class)
@Composable
fun CivicShell(
    tab: Tab,
    onTab: (Tab) -> Unit,
    status: ConnectionStatus,
    detail: String?,
    canConnect: Boolean,
    onConnectionAction: () -> Unit,
    onSettings: () -> Unit,
    content: @Composable () -> Unit,
) {
    val itemColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = CivicColors.Ink,
            selectedTextColor = CivicColors.Ink,
            indicatorColor = Color(0x1FFFFFFF),
            unselectedIconColor = CivicColors.Ink3,
            unselectedTextColor = CivicColors.Ink3,
        ),
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            selectedIconColor = CivicColors.Ink,
            selectedTextColor = CivicColors.Ink,
            indicatorColor = Color(0x1FFFFFFF),
            unselectedIconColor = CivicColors.Ink3,
            unselectedTextColor = CivicColors.Ink3,
        ),
    )

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Tab.entries.forEach { destination ->
                item(
                    selected = destination == tab,
                    onClick = { onTab(destination) },
                    icon = {
                        Icon(destination.icon, contentDescription = destination.label)
                    },
                    label = { Text(destination.label, fontSize = 11.sp) },
                    colors = itemColors,
                )
            }
        },
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationBarContainerColor = Color(0xFF15181C),
            navigationRailContainerColor = Color(0xFF15181C),
        ),
        containerColor = CivicColors.Ground,
        contentColor = CivicColors.Ink,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Short window means the car mount in landscape. Nothing here branches on
            // orientation - a tall phone held sideways is not what this is about, the space
            // left for the gauge is.
            val compact = maxHeight < 520.dp

            Column(
            modifier = Modifier
                .fillMaxSize()
                // The scaffold has already taken the inset its own bar sits in. Taking the
                // whole of safeDrawing here would pad the bottom a second time and leave a
                // band of ground above the bar.
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                    )
                )
        ) {
            ConnectionBar(
                status = status,
                detail = detail,
                canConnect = canConnect,
                compact = compact,
                onAction = onConnectionAction,
                onSettings = onSettings,
            )
            Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}
