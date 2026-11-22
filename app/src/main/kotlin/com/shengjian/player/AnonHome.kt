package com.shengjian.player

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.function.IntConsumer

internal class AnonHome(private val context: Context) {
    private val labels = listOf("音乐", "漫画", "小说", "动画")
    private val icons = listOf("note", "panels", "book", "film")
    private val dark = PlayerViews.dark()
    private val background = Color(PlayerViews.BG)
    private val line = Color(PlayerViews.LINE)
    private val ink = Color(PlayerViews.FG)
    private val muted = Color(PlayerViews.MUTED)
    private val accent = Color(PlayerViews.textTone(PlayerViews.PINK))
    private val surface = Color(PlayerViews.SURFACE)
    private val colors = if (dark) darkColorScheme(primary = accent, onPrimary = background, surface = surface, onSurface = ink)
        else lightColorScheme(primary = accent, onPrimary = Color.White, surface = surface, onSurface = ink)
    private var section by mutableIntStateOf(0)
    private var filter by mutableIntStateOf(0)
    private var playlist by mutableStateOf("")
    private var searching by mutableStateOf(false)
    private var hasHistory by mutableStateOf(false)

    fun updateSection(index: Int) { section = index }

    fun updateLibrary(activeFilter: Int, playlistName: String, historyAvailable: Boolean) {
        filter = activeFilter
        playlist = playlistName
        hasHistory = historyAvailable
    }

    fun updateSearch(open: Boolean) { searching = open }

    fun header(settings: Runnable) = view {
        Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.anon_avatar), contentDescription = null,
                modifier = Modifier.size(27.dp).clip(CircleShape).background(surface))
            Spacer(Modifier.width(7.dp))
            Text(stringResource(R.string.app_name), Modifier.weight(1f), color = ink, fontSize = 15.sp,
                fontWeight = FontWeight.Bold)
            TextButton(onClick = settings::run, shape = RoundedCornerShape(8.dp)) {
                Glyph("settings", PlayerViews.MUTED, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("设置", color = muted, fontSize = 13.sp)
            }
        }
        HorizontalDivider(color = line)
    }

    fun navigation(select: IntConsumer) = view {
        Column {
            HorizontalDivider(color = line)
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                labels.forEachIndexed { index, label ->
                    val selected = index == section
                    val tint = if (selected) accent else muted
                    Column(
                        Modifier.weight(1f).heightIn(min = 51.dp).selectable(
                            selected = selected, onClick = { select.accept(index) }, role = Role.Tab
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Glyph(icons[index], if (selected) PlayerViews.PINK else PlayerViews.MUTED, Modifier.size(20.dp))
                        Spacer(Modifier.height(2.dp))
                        Text(label, color = tint, fontSize = 11.sp, lineHeight = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }

    fun library(showSearch: Runnable, selectFilter: IntConsumer, playlists: Runnable, more: Runnable,
                imports: Runnable, clearHistory: Runnable) = view {
        val viewingHistory = filter == 4 && playlist.isEmpty()
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            LibraryTab("曲库", filter == 0 && playlist.isEmpty(), Modifier.weight(1f)) { selectFilter.accept(0) }
            LibraryTab("收藏", filter == 3 && playlist.isEmpty(), Modifier.weight(1f)) { selectFilter.accept(3) }
            LibraryTab("歌单", playlist.isNotEmpty(), Modifier.weight(1f), action = playlists::run)
            LibraryTab("历史", viewingHistory, Modifier.weight(1f), accessibilityLabel = "音乐历史记录") {
                selectFilter.accept(4)
            }
            LibraryTab("导入", false, Modifier.weight(1f), action = imports::run)
        }
        HorizontalDivider(color = line)
        Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            val activeLabel = listOf("全部歌曲", "本地歌曲", "链接导入", "收藏", "历史记录").getOrElse(filter) { "全部歌曲" }
            Text(playlist.ifEmpty { activeLabel }, Modifier.weight(1f).semantics { heading() }, color = ink,
                fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = showSearch::run, shape = RoundedCornerShape(8.dp)) {
                Glyph("search", PlayerViews.MUTED, Modifier.size(18.dp))
                Spacer(Modifier.width(3.dp))
                Text(if (searching) "收起" else if (viewingHistory) "搜索历史" else "库内搜索",
                    color = muted, fontSize = 13.sp)
            }
            if (viewingHistory) {
                if (hasHistory) TextButton(onClick = clearHistory::run, shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.semantics { contentDescription = "清空音乐历史记录" }) {
                    Text("清空", color = muted, fontSize = 13.sp)
                }
            } else {
                TextButton(onClick = more::run, shape = RoundedCornerShape(8.dp)) {
                    Text("筛选", color = if (filter in listOf(1, 2) && playlist.isEmpty()) accent else muted,
                        fontSize = 13.sp)
                }
            }
        }
    }

    @Composable
    private fun LibraryTab(label: String, selected: Boolean, modifier: Modifier, accessibilityLabel: String? = null,
                           action: () -> Unit) {
        val semantics = if (accessibilityLabel == null) Modifier else Modifier.semantics {
            contentDescription = accessibilityLabel
        }
        Box(modifier.heightIn(min = 48.dp).selectable(selected = selected, onClick = action, role = Role.Tab)
            .then(semantics),
            contentAlignment = Alignment.Center) {
            Text(label, color = if (selected) accent else muted, fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (selected) Spacer(Modifier.align(Alignment.BottomCenter).width(24.dp).height(2.dp).background(accent))
        }
    }

    @Composable
    private fun Glyph(name: String, color: Int, modifier: Modifier) {
        AndroidView(factory = { PlayerViews.Icon(it, name, color, 20f) }, modifier = modifier,
            update = { it.show(name, color) })
    }

    private fun view(content: @Composable () -> Unit) = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MaterialTheme(colorScheme = colors) {
                Column(Modifier.fillMaxWidth()) { content() }
            }
        }
    }
}
