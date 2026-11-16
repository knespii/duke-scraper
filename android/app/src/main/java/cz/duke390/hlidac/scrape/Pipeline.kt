package cz.duke390.hlidac.scrape

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.time.LocalDateTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------- nastavení (stejné jako v Python verzi)
const val MAX_DRIVE_HOURS_DE = 5.0
const val DEAL_THRESHOLD = 0.08
const val SUSPICIOUS = 0.33
private const val NEW_BADGE_HOURS = 48L
private const val DETAIL_CACHE_DAYS = 5L
private const val UNKNOWN_YEAR_MIN_CZK = 80_000
private const val DETAIL_VERSION = 2
private const val TRANSLATE_TO = "en"

class NoNetworkException : Exception("Není připojení k internetu")
class AllSourcesFailedException : Exception("Žádný bazar neodpověděl")

data class RunResult(val report: Report, val newCards: List<Card>, val firstRun: Boolean)

class Pipeline(private val ctx: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stateFile = File(ctx.filesDir, "state.json")
    private val reportFile = File(ctx.filesDir, "report.json")
    private val now = LocalDateTime.now()
    private val nowS = now.toString()

    fun loadReport(): Report? = runCatching { json.decodeFromString<Report>(reportFile.readText()) }.getOrNull()

    private fun loadState(): State = runCatching { json.decodeFromString<State>(stateFile.readText()) }.getOrElse { State() }

    private fun <T> writeAtomic(f: File, value: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(value); tmp.renameTo(f)
    }

    // ------------------------------------------------------------ vzdálenost
    private val plz: Map<String, Pair<Double, Double>> by lazy {
        ctx.assets.open("de_plz.tsv").bufferedReader().useLines { lines ->
            lines.mapNotNull { l ->
                val p = l.split('\t')
                if (p.size == 3) p[0] to (p[1].toDouble() to p[2].toDouble()) else null
            }.toMap()
        }
    }

    private fun driveHours(c: Pair<Double, Double>): Double {
        val r = Math.PI / 180
        val la1 = 50.0755 * r; val lo1 = 14.4378 * r
        val la2 = c.first * r; val lo2 = c.second * r
        val a = sin((la2 - la1) / 2).let { it * it } + cos(la1) * cos(la2) * sin((lo2 - lo1) / 2).let { it * it }
        val km = 6371 * 2 * asin(sqrt(a))
        return ((km * 1.27 / 100 + 0.25) * 10).roundToInt() / 10.0
    }

    // ------------------------------------------------------------ síť, kurz, překlad
    private suspend fun waitForNetwork(progress: suspend (String) -> Unit): Boolean {
        repeat(12) { i ->
            val ok = withContext(Dispatchers.IO) {
                runCatching { InetAddress.getByName("motorky.bazos.cz"); InetAddress.getByName("www.kleinanzeigen.de") }.isSuccess
            }
            if (ok) return true
            if (i == 0) progress("Čekám na internet…")
            delay(10_000)
        }
        return false
    }

    private suspend fun eurRate(state: State): Double {
        runCatching {
            val t = Http.text("https://www.cnb.cz/cs/financni-trhy/devizovy-trh/kurzy-devizoveho-trhu/kurzy-devizoveho-trhu/denni_kurz.txt", pause = false)
            Regex("\\|EUR\\|([\\d,]+)").find(t)?.groupValues?.get(1)?.replace(',', '.')?.toDouble()?.let { state.eur = it }
        }
        return state.eur ?: 24.5
    }

    private val trLock = Mutex()
    private suspend fun translate(text: String, state: State): String? {
        val t = text.trim()
        if (t.isEmpty()) return t
        val key = MessageDigest.getInstance("SHA-1").digest("$TRANSLATE_TO:$t".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        trLock.withLock { state.tr[key]?.let { it.at = nowS; return it.t } }
        return runCatching {
            val body = Http.text(
                "https://translate.googleapis.com/translate_a/single",
                mapOf("client" to "gtx", "sl" to "auto", "tl" to TRANSLATE_TO, "dt" to "t", "q" to t), pause = false,
            )
            val d = json.parseToJsonElement(body).jsonArray
            val out = (d[0] as? JsonArray)?.joinToString("") { seg -> ((seg as? JsonArray)?.getOrNull(0) as? JsonPrimitive)?.content ?: "" } ?: t
            val lang = (d.getOrNull(2) as? JsonPrimitive)?.content
            val res = if (lang == TRANSLATE_TO) t else out
            trLock.withLock { state.tr[key] = TrCache(res, nowS) }
            res
        }.getOrNull()
    }

    // ------------------------------------------------------------ hlavní běh
    suspend fun run(progress: suspend (String) -> Unit = {}): RunResult = coroutineScope {
        if (!waitForNetwork(progress)) throw NoNetworkException()
        val state = loadState()
        val rate = eurRate(state)

        // 1) všechny bazary paralelně (každý si drží vlastní pauzu mezi požadavky)
        progress("Stahuji bazary…")
        val statuses = mutableListOf<SourceStatus>()
        val all = mutableListOf<Listing>()
        val results = SOURCES.map { s -> async { s to runCatching { s.list() } } }.awaitAll()
        for ((s, r) in results) {
            r.onSuccess { items ->
                statuses += SourceStatus(s.name, ok = true, found = items.size)
                state.lastItems[s.name] = SourceSnapshot(nowS, items)
                all += items.map { it.copy() }
            }.onFailure { e ->
                val prev = state.lastItems[s.name]
                val err = "${e::class.simpleName}: ${e.message}".take(200)
                if (prev != null) {
                    statuses += SourceStatus(s.name, ok = false, found = prev.items.size, stale = prev.at, error = err)
                    all += prev.items.map { it.copy() }
                } else statuses += SourceStatus(s.name, ok = false, error = err)
            }
        }
        if (statuses.none { it.ok }) throw AllSourcesFailedException()

        // 2) hrubý filtr
        val hours = mutableMapOf<String, Double?>()
        val cand = all.filter { x ->
            if (!TextRules.isDuke390(x.title)) return@filter false
            val p = x.price ?: return@filter false
            if ((x.currency == "EUR" && p < 1500) || (x.currency == "CZK" && p < 40_000)) return@filter false
            if (x.year == null) x.year = TextRules.parseYear(x.title, x.desc)
            if ((x.year ?: 9999) < MIN_YEAR) return@filter false
            when (x.country) {
                "DE" -> {
                    val h = plz[x.plz.trim().padStart(5, '0')]?.let(::driveHours)
                    hours[x.key] = h
                    h == null || h <= MAX_DRIVE_HOURS_DE
                }
                "CZ" -> true
                else -> false
            }
        }

        // 3) detaily – paralelně po bazarech, s cache
        progress("Čtu detaily inzerátů (${cand.size})…")
        val bySource = SOURCES.associateBy { it.name }
        val cutoffDetail = now.minusDays(DETAIL_CACHE_DAYS).toString()
        val lock = Mutex()
        cand.groupBy { it.source }.map { (src, xs) ->
            async {
                for (x in xs) {
                    val cached = lock.withLock { state.details[x.key] }
                    val fresh = cached != null && cached.at > cutoffDetail && cached.price == x.price && cached.v == DETAIL_VERSION
                    val det = if (fresh) cached!!.data else runCatching { bySource.getValue(src).detail(x) }
                        .onSuccess { d -> lock.withLock { state.details[x.key] = DetailCache(nowS, x.price, DETAIL_VERSION, d) } }
                        .getOrElse { cached?.data ?: Detail() }
                    det.desc?.takeIf { it.isNotEmpty() }?.let { x.desc = it }
                    if (det.km != null && x.km == null) x.km = det.km
                    det.dealer?.let { x.dealer = it }
                    if (det.year != null && x.source == "Kleinanzeigen") x.year = det.year
                    if (x.year == null || x.source == "Bazoš" || x.source == "Sbazar")
                        x.year = TextRules.parseYear(x.title, x.desc) ?: x.year
                    TextRules.strongYear(x.title, x.desc)?.let { x.year = it }
                }
            }
        }.awaitAll()

        // 4) finální výběr a obohacení
        data class W(val x: Listing, val czk: Int, val full: String)
        var finals = cand.mapNotNull { x ->
            if ((x.year ?: 9999) < MIN_YEAR) return@mapNotNull null
            val czk = if (x.currency == "EUR") (x.price!! * rate).roundToInt() else x.price!!
            if (x.year == null && czk < UNKNOWN_YEAR_MIN_CZK) return@mapNotNull null
            val full = "${x.title}\n${x.desc}"
            if (x.km == null) x.km = TextRules.parseKm(full)
            if (x.source in setOf("Bazoš", "Sbazar", "Motorkáři.cz") && !x.dealer) x.dealer = TextRules.looksLikeDealer(full)
            W(x, czk, full)
        }.sortedBy { it.czk }

        // duplicity mezi bazary (stejná cena, rok, země, km)
        val dedup = mutableListOf<W>()
        for (w in finals) {
            val dup = dedup.any { o ->
                kotlin.math.abs(o.czk - w.czk) < 500 && (o.x.year == w.x.year || o.x.year == null || w.x.year == null) &&
                    o.x.country == w.x.country && (o.x.km == w.x.km || o.x.km == null || w.x.km == null) && o.x.source != w.x.source
            }
            if (!dup) dedup += w
        }
        finals = dedup

        // 5) mediány podle ročníku (fallback generace)
        fun gen(y: Int?) = when (y) { null -> null; 2023 -> "2023 (stará gen.)"; else -> "2024+ (nová gen.)" }
        val damaged = finals.associate { it.x.key to TextRules.looksDamaged(it.full) }
        val medians = mutableMapOf<String, Double>()
        fun median(v: List<Int>) = v.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0 }
        finals.mapNotNull { gen(it.x.year) }.toSet().forEach { g ->
            val p = finals.filter { gen(it.x.year) == g && damaged[it.x.key] != true }.map { it.czk }
            if (p.size >= 3) medians[g] = median(p)
        }
        finals.mapNotNull { it.x.year }.toSet().forEach { y ->
            val p = finals.filter { it.x.year == y && damaged[it.x.key] != true }.map { it.czk }
            if (p.size >= 3) medians[y.toString()] = median(p)
        }

        // 6) historie (nové, zlevnění)
        val firstRun = state.lastRun == null
        val newKeys = mutableSetOf<String>()
        val newCutoff = now.minusHours(NEW_BADGE_HOURS).toString()

        // 7) překlad (4 souběžně)
        progress("Překládám…")
        val tr = mutableMapOf<String, Pair<String?, String?>>()
        finals.chunked(4).forEach { chunk ->
            chunk.map { w ->
                async {
                    val t = translate(w.x.title, state)
                    val d = translate(w.x.desc.replace(Regex("[ \\t]+"), " ").take(1500), state)
                    w.x.key to (t to d)
                }
            }.awaitAll().forEach { tr[it.first] = it.second }
        }

        val cards = finals.map { w ->
            val x = w.x
            val h = state.listings.getOrPut(x.key) { newKeys += x.key; History(nowS, nowS) }
            h.lastSeen = nowS
            if (h.prices.lastOrNull() != w.czk) h.prices += w.czk
            val drop = h.prices.first() - w.czk
            val med = medians[x.year.toString()] ?: gen(x.year)?.let { medians[it] }
            val pct = med?.let { (it - w.czk) / it }
            val dmg = damaged[x.key] == true
            Card(
                key = x.key, source = x.source, url = x.url, title = x.title, titleTr = tr[x.key]?.first,
                desc = x.desc, descTr = tr[x.key]?.second, priceCzk = w.czk,
                priceEur = if (x.currency == "EUR") x.price else null, year = x.year, km = x.km,
                location = x.location, country = x.country, hours = hours[x.key], image = x.image, dealer = x.dealer,
                exhaust = TextRules.detectExhaust(w.full), qs = TextRules.detectQs(w.full),
                my2526 = (x.year ?: 0) >= 2025 || TextRules.mentionsMy2526(w.full) || x.isNewBike,
                generation = TextRules.detectGen(w.full, x.year),
                damaged = dmg, dealPct = pct, deal = pct != null && pct >= DEAL_THRESHOLD && !dmg,
                isNew = h.firstSeen > newCutoff, priceDrop = if (drop > 0 && drop < w.czk * 0.5) drop else 0,
            )
        }

        // úklid (30 dní neviděné)
        val cutoff = now.minusDays(30).toString()
        state.listings.entries.removeAll { it.value.lastSeen < cutoff }
        state.details.entries.removeAll { it.value.at < cutoff }
        state.tr.entries.removeAll { it.value.at < cutoff }
        state.lastRun = nowS
        writeAtomic<State>(stateFile, json.encodeToString(State.serializer(), state))

        // 8) sekce v pořadí: cena -> výfuk -> quickshifter -> 25/26 -> ostatní -> neznámý rok
        val deal = mutableListOf<Card>(); val exh = mutableListOf<Card>(); val my = mutableListOf<Card>()
        val rest = mutableListOf<Card>(); val unk = mutableListOf<Card>()
        for (c in cards) when {
            c.year == null -> unk += c
            c.deal -> deal += c
            c.exhaust != null -> exh += c
            c.my2526 -> my += c
            else -> rest += c
        }
        val qs = cards.filter { it.qs && it.year != null }
        val sections = listOf(
            Section("deal", "🔥 Zajímavá cena", "Aspoň o ${(DEAL_THRESHOLD * 100).toInt()} % levnější než medián stejného ročníku", deal.sortedByDescending { it.dealPct }),
            Section("exhaust", "🔊 Laděný výfuk", "Akrapovič, Arrow, SC-Project, Remus…", exh.sortedBy { it.priceCzk }),
            Section("qs", "⚡ Quickshifter", "Všechny s QS – můžou být i v jiné sekci", qs.sortedBy { it.priceCzk }),
            Section("my", "🆕 Model 25/26", "Novější ročníky, které nespadly výš", my.sortedBy { it.priceCzk }),
            Section("rest", "Ostatní 2023+", "Všechno ostatní od roku 2023", rest.sortedBy { it.priceCzk }),
            Section("unknown", "❓ Rok neznámý", "Inzerát rok neuvádí – zkontroluj ručně", unk.sortedBy { it.priceCzk }),
        )
        val report = Report(nowS, cards.size, rate, medians.filterKeys { it.all(Char::isDigit) }, statuses, sections)
        writeAtomic<Report>(reportFile, json.encodeToString(Report.serializer(), report))
        RunResult(report, cards.filter { it.key in newKeys }, firstRun)
    }
}
