package com.stalker.pda.ui.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.stalker.pda.R
import com.stalker.pda.data.model.PdaScreen
import com.stalker.pda.ui.components.CallOverlay
import com.stalker.pda.ui.components.IncomingQuestDialog
import com.stalker.pda.ui.components.NearbyContactAlert
import com.stalker.pda.ui.components.playSound
import com.stalker.pda.ui.screens.BootScreen
import com.stalker.pda.ui.screens.ContactsScreen
import com.stalker.pda.ui.screens.DataScreen
import com.stalker.pda.ui.screens.EncyclopediaScreen
import com.stalker.pda.ui.screens.JournalScreen
import com.stalker.pda.ui.screens.MapScreen
import com.stalker.pda.ui.screens.MessagesScreen
import com.stalker.pda.ui.screens.RankingScreen
import com.stalker.pda.ui.screens.TasksScreen
import com.stalker.pda.ui.theme.PdaColors
import com.stalker.pda.ui.theme.PdaScreenEffects
import com.stalker.pda.ui.theme.PdaType
import com.stalker.pda.viewmodel.PdaUiState
import com.stalker.pda.viewmodel.PdaViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full-screen PDA interface: tabs on top, the active page below. No device body. */
@Composable
fun PdaScaffold(state: PdaUiState, vm: PdaViewModel, modifier: Modifier = Modifier) {
    if (!state.bootComplete) {
        BootScreen()
        return
    }

    state.newNearbyContact?.let { NearbyContactAlert(contact = it, onDismiss = vm::acknowledgeNearbyAlert) }
    state.call?.let { c -> CallOverlay(c, state.contacts.find { it.id == c.peerId }?.avatarPath, vm) }
    state.incomingQuest?.let { IncomingQuestDialog(it, vm) }

    PdaScreenEffects(modifier = modifier.fillMaxSize().background(PdaColors.ScreenBg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            PdaTabBar(state = state, onNavigate = vm::navigateTo)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (state.activeScreen) {
                    PdaScreen.TASKS -> TasksScreen(state, vm)
                    PdaScreen.MAP -> MapScreen(state, vm)
                    PdaScreen.JOURNAL -> JournalScreen(state, vm)
                    PdaScreen.MESSAGES -> MessagesScreen(state, vm)
                    PdaScreen.CONTACTS -> ContactsScreen(state, vm)
                    PdaScreen.RANKING -> RankingScreen(state, vm)
                    PdaScreen.DATA -> DataScreen(state, vm)
                    PdaScreen.ENCYCLOPEDIA -> EncyclopediaScreen(state, vm)
                }
            }
        }
    }
}

@Composable
private fun PdaTabBar(state: PdaUiState, onNavigate: (PdaScreen) -> Unit) {
    val ctx = LocalContext.current
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val fmt = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault())
        while (true) {
            clock = fmt.format(Date())
            kotlinx.coroutines.delay(1000)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(PdaColors.PanelBg)
                .border(1.dp, PdaColors.Border)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PdaScreen.entries.forEach { screen ->
                val active = state.activeScreen == screen
                val flag = when (screen) {
                    PdaScreen.MESSAGES -> state.unreadMessages
                    PdaScreen.CONTACTS -> state.nearbyContactIds.isNotEmpty()
                    else -> false
                }
                Row(
                    modifier = Modifier
                        .clickable {
                            playSound(ctx, R.raw.pda_click)
                            onNavigate(screen)
                        }
                        .padding(horizontal = 2.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(5.dp).background(
                            when {
                                flag -> PdaColors.Green
                                active -> PdaColors.Amber
                                else -> PdaColors.TextDim
                            }
                        )
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = screen.label,
                        style = PdaType.Tab.copy(color = if (active) PdaColors.Amber else PdaColors.TextGrey),
                        maxLines = 1
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .width(112.dp)
                .fillMaxHeight()
                .background(PdaColors.PanelBg)
                .border(1.dp, PdaColors.Border),
            contentAlignment = Alignment.Center
        ) {
            Text(clock, style = PdaType.Small.copy(color = PdaColors.TextWhite), maxLines = 1)
        }
    }
}
