package cz.duke390.hlidac.scrape

import cz.duke390.hlidac.scrape.TextRules.num
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** Jeden bazar: stažení seznamu + detail inzerátu. */
interface Source {
    val name: String
    suspend fun list(): List<Listing>
    suspend fun detail(x: Listing): Detail
}

const val MIN_YEAR = 2023
private val json = Json { ignoreUnknownKeys = true }

// ---- drobné pomocníky pro JSON a HTML ---------------------------------------
private operator fun JsonElement?.get(k: String): JsonElement? = (this as? JsonObject)?.get(k)
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { this !is JsonNull }?.contentOrNull
private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull ?: str()?.toDoubleOrNull()?.toInt()
private fun JsonElement?.arr(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

/** Jako BeautifulSoup `stripped_strings`. */
private fun Element.strippedStrings(): List<String> {
    val out = mutableListOf<String>()
    fun walk(e: Element) {
        e.childNodes().forEach { n ->
            when (n) {
                is TextNode -> n.text().trim().takeIf { it.isNotEmpty() }?.let(out::add)
                is Element -> walk(n)
            }
        }
    }
    walk(this)
    return out
}

/** Text s řádky (jako get_text("\n", strip=True)). */
private fun Element.lines(): String = strippedStrings().joinToString("\n")

// =============================================================================
object Bazos : Source {
    override val name = "Bazoš"
    private const val ROOT = "https://motorky.bazos.cz/"

    override suspend fun list(): List<Listing> {
        val out = mutableListOf<Listing>()
        val seen = mutableSetOf<String>()
        var url = ROOT
        var params: Map<String, String>? = mapOf(
            "hledat" to "duke 390", "rubriky" to "motorky", "hlokalita" to "", "humkreis" to "25",
            "cenaod" to "50000", "cenado" to "", "Submit" to "Hledat",
        )
        for (page in 0 until 20) {
            val b = try {
                Http.doc(url, params)
            } catch (e: Exception) {
                if (page == 0) throw e else break   // další stránka nejde -> beru co mám
            }
            for (d in b.select("div.inzeraty")) {
                val a = d.selectFirst("h2.nadpis a") ?: continue
                val id = Regex("/inzerat/(\\d+)/").find(a.attr("href"))?.groupValues?.get(1) ?: continue
                if (!seen.add(id)) continue
                val lok = d.selectFirst(".inzeratylok")?.strippedStrings().orEmpty()
                out += Listing(
                    source = name, id = id, url = a.absUrl("href"), title = a.text().trim(),
                    price = num(d.selectFirst(".inzeratycena")?.text()),
                    location = lok.firstOrNull() ?: "",
                    image = d.selectFirst("img.obrazek")?.absUrl("src") ?: "",
                    desc = d.selectFirst(".popis")?.text() ?: "",
                )
            }
            val next = b.select(".strankovani a[href]").firstOrNull { "Další" in it.text() } ?: break
            url = next.absUrl("href"); params = null
        }
        return out
    }

    override suspend fun detail(x: Listing): Detail =
        Detail(desc = Http.doc(x.url).selectFirst(".popisdetail")?.lines() ?: x.desc)
}

// =============================================================================
object Sbazar : Source {
    override val name = "Sbazar"

    override suspend fun list(): List<Listing> {
        val out = mutableListOf<Listing>()
        for (phrase in listOf("duke 390", "390 duke")) {
            val r = json.parseToJsonElement(
                Http.text("https://www.sbazar.cz/api/v1/items/search", mapOf("phrase" to phrase, "limit" to "100"))
            )
            for (it in r["results"].arr()) {
                val id = it["id"].int()?.toString() ?: continue
                if (out.any { o -> o.id == id }) continue
                val img = it["images"].arr().firstOrNull()["url"].str() ?: ""
                val loc = it["locality"]
                val shop = it["user"]["user_service"]["shop_url"].str() ?: ""
                out += Listing(
                    source = name, id = id, url = "https://www.sbazar.cz/inzerat/${it["seo_name"].str()}",
                    title = it["name"].str() ?: "", price = it["price"].int(),
                    location = loc["municipality"].str()?.takeIf { s -> s.isNotEmpty() } ?: loc["district"].str() ?: "",
                    image = if (img.startsWith("//")) "https:$img?fl=exf|res,606,606,3|webp,75" else img,
                    dealer = (it["premise"] != null && it["premise"] !is JsonNull) || shop.startsWith("www."),
                )
            }
        }
        return out
    }

    override suspend fun detail(x: Listing): Detail {
        val r = json.parseToJsonElement(Http.text("https://www.sbazar.cz/api/v1/items/${x.id}"))
        return Detail(desc = r["result"]["description"].str() ?: "")
    }
}

// =============================================================================
object Motorkari : Source {
    override val name = "Motorkáři.cz"
    private const val URL = "https://www.motorkari.cz/motobazar/motorky/ktm/ktm-duke-390/"

    override suspend fun list(): List<Listing> {
        val b = Http.doc(URL)
        return b.select("div.main ul.list > li").mapNotNull { li ->
            val a = li.selectFirst("h3 a") ?: return@mapNotNull null
            val href = a.absUrl("href")
            val params = li.select("table tr").mapNotNull { tr ->
                val th = tr.selectFirst("th"); val td = tr.selectFirst("td")
                if (th != null && td != null) th.text().trim() to td.text().trim() else null
            }.toMap()
            val ps = li.select(".description p")
            val yr = Regex("Ročník:\\s*(\\d{4})").find(li.text())?.groupValues?.get(1)?.toInt()
            Listing(
                source = name, id = Regex("-(\\d+)\\.html").find(href)?.groupValues?.get(1) ?: href, url = href,
                title = a.text().trim(), price = num(params["Cena"]), year = yr, km = num(params["Najeto"]),
                location = (params["Kraj"] ?: "") + " kraj",
                image = li.selectFirst(".thumb img")?.absUrl("src") ?: "",
                desc = ps.getOrNull(1)?.text() ?: "",
                dealer = li.text().contains("-DPH"),
            )
        }
    }

    override suspend fun detail(x: Listing): Detail =
        Detail(desc = Http.doc(x.url).selectFirst(".box-highlighted .inner")?.lines() ?: x.desc)
}

// =============================================================================
object TipMoto : Source {
    override val name = "TipMoto"

    override suspend fun list(): List<Listing> {
        val out = mutableListOf<Listing>()
        for (od in 0 until 200 step 10) {
            val b = Http.doc("https://www.tipmoto.com/modely/ktm/6919-390-duke.html?od=$od")
            val rows = b.select("table.vypis tr").filter { it.selectFirst("td") != null }
            if (rows.isEmpty()) break
            for (tr in rows) {
                val tds = tr.select("td")
                val a = tr.selectFirst("a[href*=/motorky/ktm/]") ?: continue
                if (tds.size < 5) continue
                val href = a.absUrl("href")
                if (out.any { it.url == href }) continue
                out += Listing(
                    source = name, id = Regex("/(\\d+)-").find(href)?.groupValues?.get(1) ?: href, url = href,
                    title = tds[1].text().replace("Vložit do schránky", "").trim(),
                    price = num(tds[4].text().substringBefore("Kč")),
                    year = Regex("20\\d\\d").find(tds[2].text())?.value?.toInt(),
                    location = tds[3].text().trim(),
                    image = tr.selectFirst("img")?.absUrl("src") ?: "", dealer = true,
                )
            }
            if (rows.size < 10) break
        }
        return out
    }

    override suspend fun detail(x: Listing): Detail =
        Detail(desc = Http.doc(x.url).selectFirst("#detail-popis")?.lines() ?: "")
}

// =============================================================================
object Kleinanzeigen : Source {
    override val name = "Kleinanzeigen"

    override suspend fun list(): List<Listing> {
        val out = mutableListOf<Listing>()
        for (page in 1..10) {
            val p = if (page > 1) "seite:$page/" else ""
            val b = Http.doc("https://www.kleinanzeigen.de/s-motorraeder-roller/${p}duke-390/k0c305")
            val arts = b.select("article[data-adid]")
            if (arts.isEmpty()) break
            var new = 0
            for (a in arts) {
                val aid = a.attr("data-adid")
                if (out.any { it.id == aid }) continue
                new++
                val ld = a.selectFirst("script[type=application/ld+json]")?.data()
                    ?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
                a.select("script, svg").remove()
                val parts = a.strippedStrings()
                if ("Gesuch" in parts) continue
                val text = parts.joinToString(" | ")
                val plz = Regex("\\b(\\d{5})\\s+([^|]+)").find(text)
                val pm = Regex("([\\d.]+)\\s*€").find(text)
                val ez = Regex("EZ (\\d{2})/(\\d{4})").find(text)
                out += Listing(
                    source = name, id = aid, url = a.absUrl("data-href"),
                    title = ld["title"].str() ?: (a.selectFirst("h2") ?: a).text().trim(),
                    price = num(pm?.groupValues?.get(1)), currency = "EUR", country = "DE",
                    year = ez?.groupValues?.get(2)?.toInt(),
                    plz = plz?.groupValues?.get(1) ?: "", location = plz?.groupValues?.get(2)?.trim() ?: "",
                    image = (ld["contentUrl"].str() ?: "").replace("\$_59", "\$_2"),
                    desc = ld["description"].str() ?: "", dealer = "PRO" in parts,
                )
            }
            if (new == 0 || arts.size < 25) break
        }
        return out
    }

    override suspend fun detail(x: Listing): Detail {
        val b = Http.doc(x.url)
        val attrs = b.select(".addetailslist--detail").mapNotNull { li ->
            val v = li.selectFirst(".addetailslist--detail--value") ?: return@mapNotNull null
            li.text().replace(v.text(), "").trim() to v.text().trim()
        }.toMap()
        val page = b.text()
        return Detail(
            desc = b.selectFirst("#viewad-description-text")?.lines() ?: x.desc,
            dealer = when {
                "Gewerblicher Nutzer" in page -> true
                "Privater Nutzer" in page -> false
                else -> null
            },
            year = Regex("(20\\d\\d)").find(attrs["Erstzulassung"] ?: "")?.value?.toInt(),
            km = attrs["Kilometerstand"]?.let(::num),
        )
    }
}

// =============================================================================
object AutoScout : Source {
    override val name = "AutoScout24"

    private fun nextData(html: String): JsonElement? =
        Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data()?.let { json.parseToJsonElement(it) }

    override suspend fun list(): List<Listing> {
        val out = mutableListOf<Listing>()
        for (page in 1..5) {
            val pp = nextData(Http.text("https://www.autoscout24.de/lst-moto/ktm/390-duke?fregfrom=$MIN_YEAR&cy=D&page=$page"))
                ?.get("props")?.get("pageProps") ?: break
            for (l in pp["listings"].arr()) {
                val fr = l["tracking"]["firstRegistration"].str() ?: ""
                val v = l["vehicle"]; val loc = l["location"]
                out += Listing(
                    source = name, id = l["id"].str() ?: continue,
                    url = "https://www.autoscout24.de" + (l["url"].str() ?: ""),
                    title = listOfNotNull(v["make"].str(), v["model"].str(), v["modelVersionInput"].str()).joinToString(" ").trim(),
                    price = l["price"]["priceRaw"].int(), currency = "EUR", country = loc["countryCode"].str() ?: "DE",
                    year = Regex("(20\\d\\d)").find(fr)?.value?.toInt() ?: if (fr == "new") TextRules.thisYear else null,
                    isNewBike = v["offerType"].str() == "N",
                    km = num(v["mileageInKm"].str()), plz = loc["zip"].str() ?: "", location = loc["city"].str() ?: "",
                    image = l["images"].arr().firstOrNull().str() ?: "",
                    dealer = l["seller"]["type"].str() == "Dealer",
                )
            }
            if (page >= (pp["numberOfPages"].int() ?: 1)) break
        }
        return out
    }

    override suspend fun detail(x: Listing): Detail {
        val ld = nextData(Http.text(x.url))?.get("props")?.get("pageProps")?.get("listingDetails") ?: return Detail()
        val v = ld["vehicle"]
        val desc = Jsoup.parse(ld["description"].str() ?: "").body().lines()
        val extra = listOf("modelVersionInput", "variant", "subtitle").joinToString(" ") { v[it].str() ?: "" }
        return Detail(desc = "$extra\n$desc".trim())
    }
}

val SOURCES: List<Source> = listOf(Bazos, Sbazar, Motorkari, TipMoto, Kleinanzeigen, AutoScout)
