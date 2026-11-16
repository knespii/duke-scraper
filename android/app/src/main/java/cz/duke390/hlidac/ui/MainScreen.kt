package cz.duke390.hlidac.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import cz.duke390.hlidac.scrape.Card
import cz.duke390.hlidac.scrape.MIN_YEAR
import cz.duke390.hlidac.scrape.Report
import cz.duke390.hlidac.scrape.SUSPICIOUS

private const val MOBILE_DE = "https://suchen.mobile.de/fahrzeuge/search.html?isSearchRequest=true&vc=Motorbike&s=Motorbike" +
    "&ms=14600%3B%3B%3B&fr=$MIN_YEAR%3A&ref=quickSearch&sb=p&od=up"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val report by vm.report.collectAsStateWithLifecycle()
    val refresh by vm.refresh.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val open: (String) -> Unit = { url -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("Duke 390 ", fontWeight = FontWeight.ExtraBold)
                            Text("$MIN_YEAR+", fontWeight = FontWeight.ExtraBold, color = KtmOrange)
                        }
                        val sub = when {
                            refresh.running -> refresh.progress ?: "Aktualizuji…"
                            report != null -> "${fmtWhen(report!!.updatedAt)} · ${report!!.total} inzerátů"
                            else -> "Zatím žádná data"
                        }
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    TextButton(onClick = { vm.setPrefs(prefs.copy(original = !prefs.original)) }) {
                        Text(if (prefs.original) "Originál" else "EN")
                    }
                    if (refresh.running) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.5.dp)
                    } else {
                        IconButton(onClick = vm::refreshNow) { Icon(Icons.Default.Refresh, "Aktualizovat") }
                    }
                },
            )
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = vm::refreshNow,
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            val r = report
            if (r == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (refresh.running) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text(refresh.progress ?: "Stahuji inzeráty…")
                            Text("První stažení trvá 1–2 minuty", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(refresh.error ?: "Zatím žádná data")
                            TextButton(onClick = vm::refreshNow) { Text("Stáhnout inzeráty") }
                        }
                    }
                }
            } else {
                ReportList(r, prefs, refresh, onPrefs = vm::setPrefs, open = open)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ReportList(r: Report, prefs: Prefs, refresh: RefreshState, onPrefs: (Prefs) -> Unit, open: (String) -> Unit) {
    val visible = r.sections.map { s -> s.copy(cards = s.cards.filter { (prefs.country == "all" || it.country == prefs.country) && (prefs.gen == 0 || it.generation == prefs.gen) }) }
    val tab = prefs.tab.coerceIn(0, visible.lastIndex)
    val sec = visible[tab]
    val private = sec.cards.filter { !it.dealer }
    val dealers = sec.cards.filter { it.dealer }
    var dealersOpen by rememberSaveable(tab) { mutableStateOf(prefs.showDealers) }
    val listState = rememberLazyListState()
    LaunchedEffect(tab, prefs.country, prefs.gen) { listState.scrollToItem(0) }

    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
        stickyHeader(key = "tabs") {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column {
                    if (refresh.running) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                    PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
                        visible.forEachIndexed { i, s ->
                            val n = s.cards.count { !it.dealer }
                            val d = s.cards.size - n
                            Tab(selected = i == tab, onClick = { onPrefs(prefs.copy(tab = i)) }, text = {
                                Text(s.title + "  $n" + if (d > 0) " +$d🏪" else "", maxLines = 1)
                            })
                        }
                    }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("all" to "Vše", "CZ" to "🇨🇿 ČR", "DE" to "🇩🇪 DE").forEach { (k, label) ->
                            FilterChip(selected = prefs.country == k, onClick = { onPrefs(prefs.copy(country = k)) }, label = { Text(label) })
                        }
                        listOf(0 to "Gen 2+3", 2 to "Gen 2 (≤2023)", 3 to "Gen 3 (2024+)").forEach { (g, label) ->
                            FilterChip(selected = prefs.gen == g, onClick = { onPrefs(prefs.copy(gen = g)) }, label = { Text(label) })
                        }
                        FilterChip(
                            selected = prefs.showDealers,
                            onClick = { onPrefs(prefs.copy(showDealers = !prefs.showDealers)); dealersOpen = !prefs.showDealers },
                            label = { Text("🏪 Prodejci") },
                        )
                    }
                }
            }
        }

        item(key = "info") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(sec.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (refresh.error != null && !refresh.running)
                    Text("Poslední aktualizace selhala: ${refresh.error}", style = MaterialTheme.typography.bodySmall, color = LocalBadges.current.warn.second)
                SourceRow(r)
            }
        }

        if (private.isEmpty()) item(key = "empty") {
            Text("Od soukromníků nic.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(private, key = { "p:" + it.key }) { c -> ListingCard(c, prefs.original, open) }

        if (dealers.isNotEmpty()) {
            item(key = "dealers-head") {
                Row(
                    Modifier.fillMaxWidth().clickable { dealersOpen = !dealersOpen }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🏪 Od prodejců / ze salonů (${dealers.size})", Modifier.weight(1f), fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(if (dealersOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                }
            }
            if (dealersOpen) items(dealers, key = { "d:" + it.key }) { c -> ListingCard(c, prefs.original, open) }
        }

        item(key = "footer") {
            Column(Modifier.padding(16.dp)) {
                TextButton(onClick = { open(MOBILE_DE) }) { Text("mobile.de – otevřít hledání ručně") }
                Text(
                    "Vzdálenost v Německu je odhad podle PSČ. Data PSČ © GeoNames (CC BY 4.0). 1 € = ${"%.2f".format(r.eurRate)} Kč",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceRow(r: Report) {
    val b = LocalBadges.current
    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        r.sources.forEach { s ->
            when {
                s.ok -> Badge("${s.name} ✓ ${s.found}", b.neutral)
                s.stale != null -> Badge("${s.name} ⚠ data z ${fmtWhen(s.stale)}", b.qs)
                else -> Badge("${s.name} ✗ chyba", b.warn)
            }
        }
    }
}

@Composable
private fun Badge(text: String, colors: Pair<Color, Color>) {
    Text(
        text, color = colors.second, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(colors.first).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListingCard(c: Card, original: Boolean, open: (String) -> Unit) {
    val b = LocalBadges.current
    val ctx = LocalContext.current
    val title = if (!original && !c.titleTr.isNullOrBlank()) c.titleTr else c.title
    val desc = if (!original && !c.descTr.isNullOrBlank()) c.descTr else c.desc
    ElevatedCard(
        onClick = { open(c.url) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (c.image.isNotEmpty()) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(c.image).crossfade(true).build(),
                contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val yr = c.year?.toString() ?: "rok ?"
                Text(
                    yr, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    color = if (c.year != null) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(7.dp))
                        .background(if (c.year != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                c.generation?.let { g ->
                    Spacer(Modifier.width(6.dp))
                    val col = if (g == 3) Color(0xFFFF6A00) else MaterialTheme.colorScheme.onSurfaceVariant
                    Text(
                        "Gen $g", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = col,
                        modifier = Modifier.border(1.5.dp, col.copy(alpha = 0.7f), RoundedCornerShape(7.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(fmtCzk(c.priceCzk), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                c.priceEur?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(fmtEur(it), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val flag = if (c.country == "DE") "🇩🇪" else "🇨🇿"
            val line = listOfNotNull(
                c.km?.let(::fmtKm),
                "$flag ${c.location}".trim(),
                c.hours?.let { "🚗 ~$it h" },
                c.source,
            ).joinToString(" · ")
            Text(line, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)

            FlowRow(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (c.isNew) Badge("NOVÉ", Color(0xFFFF6A00) to Color.White)
                if (c.deal && c.dealPct != null) Badge("−${(c.dealPct * 100).toInt()} % pod mediánem", b.deal)
                if ((c.dealPct ?: 0.0) > SUSPICIOUS) Badge("⚠ podezřele levné", b.warn)
                if (c.priceDrop > 0) Badge("↓ zlevněno o ${fmtCzk(c.priceDrop)}", b.deal)
                c.exhaust?.let { Badge("🔊 $it", b.exhaust) }
                if (c.qs) Badge("⚡ quickshifter", b.qs)
                if (c.my2526) Badge("MY 25/26", b.my)
                if (c.damaged) Badge("⚠ možná poškozená", b.warn)
                if (c.dealer) Badge("prodejce", b.neutral)
            }
            if (desc.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    desc.replace(Regex("\\s+"), " ").take(400), fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
