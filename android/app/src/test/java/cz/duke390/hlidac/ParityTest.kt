package cz.duke390.hlidac

import cz.duke390.hlidac.scrape.SOURCES
import cz.duke390.hlidac.scrape.TextRules
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * Ruční ověření (ne součást běžného buildu):
 *  - PARITY_FILE=… : stejné výsledky rozpoznávání jako Python verze (vzorky exportuje duke_scraper.py)
 *  - LIVE=1        : všechny bazary jdou stáhnout
 */
class ParityTest {
    @Test
    fun textRulesMatchPython() {
        val path = System.getenv("PARITY_FILE")
        assumeTrue(path != null)
        val rows = Json.parseToJsonElement(File(path!!).readText()).jsonArray
        val diffs = mutableListOf<String>()
        for (r in rows) {
            val o = r.jsonObject
            val title = o.s("title")!!; val desc = o.s("desc") ?: ""
            val full = "$title\n$desc"
            fun chk(what: String, py: Any?, kt: Any?) { if (py != kt) diffs += "$what: py=$py kt=$kt | ${title.take(50)}" }
            chk("isDuke", o.b("isDuke"), TextRules.isDuke390(title))
            chk("year", o.i("year"), TextRules.parseYear(title, desc))
            chk("strong", o.i("strong"), TextRules.strongYear(title, desc))
            chk("km", o.i("km"), TextRules.parseKm(full))
            chk("exhaust", o.s("exhaust"), TextRules.detectExhaust(full))
            chk("qs", o.b("qs"), TextRules.detectQs(full))
            chk("dealer", o.b("dealer"), TextRules.looksLikeDealer(full))
        }
        diffs.take(40).forEach(::println)
        println("Vzorků: ${rows.size}, rozdílů: ${diffs.size}")
        assertEquals(0, diffs.size)
    }

    @Test
    fun allSourcesDownload() = runBlocking {
        assumeTrue(System.getenv("LIVE") == "1")
        val res = SOURCES.map { s -> async { s.name to runCatching { s.list() } } }.awaitAll()
        res.forEach { (n, r) -> println("$n: " + r.fold({ "${it.size} inzerátů, např. ${it.firstOrNull()?.let { x -> "${x.title} | ${x.price} ${x.currency} | ${x.year} | ${x.location} | img=${x.image.isNotEmpty()}" }}" }, { "CHYBA $it" })) }
        val first = SOURCES.first { it.name == "Kleinanzeigen" }.let { s -> s.detail(res.first { it.first == s.name }.second.getOrThrow().first()) }
        println("Kleinanzeigen detail: dealer=${first.dealer} year=${first.year} km=${first.km} desc=${first.desc?.take(80)}")
        assertEquals(0, res.count { it.second.isFailure })
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
}
