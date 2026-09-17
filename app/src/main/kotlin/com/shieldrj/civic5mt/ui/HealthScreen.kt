package com.shieldrj.civic5mt.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The three questions the Health tab answers.
 *
 * They were three separate destinations, which put six entries in a navigation bar built for
 * four or five. They are one destination now because they are one question asked three ways -
 * is anything wrong with this car - and because two of the three are read in a driveway, not
 * at speed, so paying one extra tap for them costs nothing.
 */
enum class HealthSection(val label: String) {
    Oil("Oil"),
    Clutch("Clutch"),
    Codes("Codes"),
}

/**
 * Oil life, clutch wear and fault codes, behind one switch.
 *
 * A segmented row rather than a scrolling stack of all three. Stacked, the clutch section
 * began some nine hundred pixels below the top of the page and the codes below that, which
 * made the tab that is supposed to answer "is the car alright" a thing you had to scroll to
 * read. Each section keeps its own body exactly as it was written - only the page header and
 * the back link are gone, because the shell now supplies both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthScreen(
    section: HealthSection,
    onSection: (HealthSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp)
                // The floor for a touch target. The words this replaced were 15sp text with
                // 4dp of padding, which is about half of it in each direction.
                .heightIn(min = 48.dp),
        ) {
            HealthSection.entries.forEachIndexed { index, entry ->
                val selected = entry == section
                SegmentedButton(
                    selected = selected,
                    onClick = { onSection(entry) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = HealthSection.entries.size,
                    ),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = Color(0x1FFFFFFF),
                        activeContentColor = CivicColors.Ink,
                        activeBorderColor = CivicColors.HairlineStrong,
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = CivicColors.Ink3,
                        inactiveBorderColor = CivicColors.HairlineStrong,
                    ),
                    // The default is a tick in the leading slot, which shunts the label
                    // sideways as you switch and makes the row look like a checklist.
                    icon = {},
                ) {
                    Text(entry.label, fontSize = 14.sp)
                }
            }
        }

        // weight rather than letting the section fill: each one scrolls internally, and a
        // child that fills the column's full height inside a column that already spent some
        // of it on the switch runs its last rows off the bottom of the window.
        val body = Modifier.weight(1f)
        when (section) {
            HealthSection.Oil -> OilSection(body)
            HealthSection.Clutch -> ClutchSection(body)
            HealthSection.Codes -> CodesSection(body)
        }
    }
}
