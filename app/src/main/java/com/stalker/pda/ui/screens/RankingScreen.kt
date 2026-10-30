package com.stalker.pda.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stalker.pda.data.model.ContactType
import com.stalker.pda.data.model.StalkerRank
import com.stalker.pda.ui.components.EmptyHint
import com.stalker.pda.ui.components.Panel
import com.stalker.pda.ui.components.PanelHeader
import com.stalker.pda.ui.components.AvatarView
import com.stalker.pda.ui.theme.PdaColors
import com.stalker.pda.ui.theme.PdaType
import com.stalker.pda.viewmodel.PdaUiState
import com.stalker.pda.viewmodel.PdaViewModel

private data class RankRow(
    val id: String,
    val name: String,
    val xp: Int,
    val faction: String,
    val relation: String,
    val isSelf: Boolean,
    val avatarPath: String? = null
)

/** Рейтинг: board of elites (you + contacts), sorted by XP. */
@Composable
fun RankingScreen(state: PdaUiState, vm: PdaViewModel) {
    val p = state.userProfile
    val rows = (listOf(RankRow("self", p.callsign, p.totalXp, p.faction, "це ти", true, p.avatarPath)) +
        state.contacts.map {
            RankRow(
                it.id, it.callsign, it.xp, it.faction,
                if (it.type == ContactType.FRIEND) "друг" else "нейтрал", false, it.avatarPath
            )
        }).sortedByDescending { it.xp }

    val selected = rows.find { it.id == state.selectedRankId } ?: rows.find { it.isSelf }

    SplitPane(
        leftWeight = 0.5f,
        left = {
            Panel(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    PanelHeader("Краща двадцятка сталкерів")
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp)) {
                        Text("##", style = PdaType.Tiny, modifier = Modifier.width(24.dp))
                        Text("Ім'я", style = PdaType.Tiny, modifier = Modifier.weight(1f))
                        Text("Ранг", style = PdaType.Tiny)
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(rows, key = { _, r -> r.id }) { index, r ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (r.id == selected?.id) PdaColors.SelectBar else Color.Transparent)
                                    .clickable { vm.selectRank(r.id) }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                val c = if (r.isSelf) PdaColors.Red else PdaColors.TextGrey
                                Text("${index + 1}.", style = PdaType.Small.copy(color = c), modifier = Modifier.width(24.dp))
                                Text(
                                    r.name,
                                    style = PdaType.Body.copy(color = if (r.isSelf) PdaColors.Amber else PdaColors.TextWhite),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1
                                )
                                Text("${r.xp}", style = PdaType.Small.copy(color = PdaColors.TextWhite))
                            }
                        }
                    }
                }
            }
        },
        right = {
            Panel(Modifier.fillMaxSize()) {
                if (selected == null) {
                    EmptyHint("Оберіть сталкера")
                } else {
                    Column(Modifier.padding(8.dp)) {
                        Row {
                            AvatarView(selected.id, selected.avatarPath, Modifier.size(width = 70.dp, height = 56.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(selected.name, style = PdaType.Title.copy(color = PdaColors.Amber))
                                Field("ранг", StalkerRank.fromXp(selected.xp).label)
                                Field("група", selected.faction)
                                Field("ставлення", selected.relation)
                                Field("досвід", "${selected.xp} XP")
                            }
                        }
                        if (selected.isSelf) {
                            Spacer(Modifier.height(10.dp))
                            Text("Формула XP", style = PdaType.Small.copy(color = PdaColors.Green))
                            Text(
                                "XP = завдання×100 + км×10 + зустрічі×50\n" +
                                    "   = ${p.completedTasks}×100 + ${"%.2f".format(p.kmWalked)}×10 + ${p.uniqueEncounters}×50\n" +
                                    "   = ${p.totalXp}",
                                style = PdaType.Terminal.copy(color = PdaColors.TextGrey)
                            )
                            val next = StalkerRank.entries.firstOrNull { it.minXp > p.totalXp }
                            if (next != null) {
                                Text(
                                    "До рангу «${next.label}»: ще ${next.minXp - p.totalXp} XP",
                                    style = PdaType.Tiny.copy(color = PdaColors.Amber)
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = PdaType.Tiny.copy(color = PdaColors.TextDim))
        Text(value, style = PdaType.Small.copy(color = PdaColors.TextWhite))
    }
}
