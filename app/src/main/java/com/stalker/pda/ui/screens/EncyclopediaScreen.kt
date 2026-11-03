package com.stalker.pda.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stalker.pda.data.SampleData
import com.stalker.pda.ui.components.BreadcrumbBar
import com.stalker.pda.ui.components.EmptyHint
import com.stalker.pda.ui.components.Panel
import com.stalker.pda.ui.components.PanelHeader
import com.stalker.pda.ui.components.PdaButton
import com.stalker.pda.ui.components.PdaDialog
import com.stalker.pda.ui.components.PdaTextField
import com.stalker.pda.ui.components.TreeCategoryRow
import com.stalker.pda.ui.components.TreeLeafRow
import com.stalker.pda.ui.components.hasKey
import com.stalker.pda.ui.components.toggleKey
import com.stalker.pda.ui.theme.PdaColors
import com.stalker.pda.ui.theme.PdaType
import com.stalker.pda.viewmodel.PdaUiState
import com.stalker.pda.viewmodel.PdaViewModel

/** Довідка: category tree (left), formatted article (right), public/private flag, new article. */
@Composable
fun EncyclopediaScreen(state: PdaUiState, vm: PdaViewModel) {
    var expanded by rememberSaveable { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    val categories = SampleData.DEFAULT_CATEGORIES +
        state.articles.map { it.category }.distinct().filter { it !in SampleData.DEFAULT_CATEGORIES }
    val article = state.articles.find { it.id == state.selectedArticleId }

    SplitPane(
        leftWeight = 0.35f,
        left = {
            Panel(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    PanelHeader("Довідка") {
                        Text(
                            "[+]",
                            style = PdaType.Title.copy(color = PdaColors.Green),
                            modifier = Modifier.clickable { showNew = true }.padding(horizontal = 4.dp)
                        )
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        categories.forEach { cat ->
                            val arts = state.articles.filter { it.category == cat }
                            item(key = "cat_$cat") {
                                TreeCategoryRow(cat, hasKey(expanded, cat)) { expanded = toggleKey(expanded, cat) }
                            }
                            if (hasKey(expanded, cat)) {
                                items(arts.size, key = { "art_${arts[it].id}" }) { i ->
                                    val a = arts[i]
                                    TreeLeafRow(
                                        (if (a.isPublic) "◉ " else "") + a.title,
                                        a.id == state.selectedArticleId
                                    ) { vm.selectArticle(a.id) }
                                }
                            }
                        }
                    }
                }
            }
        },
        right = {
            Column(Modifier.fillMaxSize()) {
                BreadcrumbBar(if (article == null) "/ Довідка" else "/ ${article.category} / ${article.title}")
                Panel(Modifier.weight(1f).fillMaxWidth()) {
                    if (article == null) {
                        EmptyHint("Оберіть статтю")
                    } else {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                            Text(
                                renderMarkdown(article.bodyMarkdown),
                                style = PdaType.Body.copy(lineHeight = 15.sp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PdaButton(
                                    if (article.isPublic) "◉ ПУБЛІЧНА (скасувати)" else "○ ПРИВАТНА (поділитись)",
                                    PdaColors.Green,
                                    { vm.toggleArticlePublic(article.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    )

    if (showNew) {
        var title by remember { mutableStateOf("") }
        var body by remember { mutableStateOf("") }
        var catIndex by remember { mutableStateOf(0) }
        PdaDialog(title = "Нова стаття", onDismiss = { showNew = false }) {
            PdaTextField(title, { title = it }, "Заголовок", Modifier.fillMaxWidth(), singleLine = true)
            PdaButton(
                "Категорія: ${categories[catIndex % categories.size]}",
                PdaColors.Amber,
                { catIndex = (catIndex + 1) % categories.size }
            )
            PdaTextField(body, { body = it }, "Текст (## заголовок, **жирний**, - список)", Modifier.fillMaxWidth().height(90.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PdaButton("Створити", PdaColors.Green, {
                    vm.addArticle(title, categories[catIndex % categories.size], body)
                    showNew = false
                }, enabled = title.isNotBlank())
                PdaButton("Скасувати", PdaColors.TextGrey, { showNew = false })
            }
        }
    }
}

/** Tiny markdown subset: "## heading", "**bold**", "- bullet". */
private fun renderMarkdown(md: String): AnnotatedString = buildAnnotatedString {
    md.lines().forEachIndexed { i, raw ->
        if (i > 0) append("\n")
        var line = raw
        val header = line.startsWith("#")
        if (header) line = line.trimStart('#').trim()
        if (line.startsWith("- ")) line = "• " + line.removePrefix("- ")
        if (header) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = PdaColors.Green)) { append(line) }
        } else {
            line.split("**").forEachIndexed { idx, part ->
                if (idx % 2 == 1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = PdaColors.Amber)) { append(part) }
                } else {
                    append(part)
                }
            }
        }
    }
}
