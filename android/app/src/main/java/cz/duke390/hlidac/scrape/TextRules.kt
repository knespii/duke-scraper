package cz.duke390.hlidac.scrape

import java.text.Normalizer
import java.time.LocalDate

/** Rozpoznávání z textu inzerátu – 1:1 podle duke_scraper.py. Pracuje s textem bez diakritiky, malými písmeny. */
object TextRules {
    val thisYear: Int = LocalDate.now().year

    private val MARKS = Regex("\\p{M}+")
    fun norm(t: String?): String =
        Normalizer.normalize(t ?: "", Normalizer.Form.NFKD).replace(MARKS, "").lowercase()

    fun num(text: String?): Int? = text?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }?.take(9)?.toIntOrNull()

    // ------------------------------------------------------------ rok výroby
    private const val YEAR_KW =
        "(?:rok(?:u)?(?: vyroby)?|r\\.? ?v\\.?|rocnik|vyroba|vyrobeno|model(?:ovy rok|ljahr|jahr)?|" +
            "\\bmy|\\bmj\\.?|\\bez|erstzulassung|zulassung|baujahr|\\bbj\\.?|registrac\\w*|1\\. ?reg\\w*|provoz\\w*)"
    private val YEAR_RE = Regex(YEAR_KW + "[\\s:.\\-]{0,6}(?:(?:0?[1-9]|1[0-2])[/.] ?)?(20[1-2]\\d)\\b")
    private val MY_SHORT = Regex("\\bmy ?'?(2[3-9])\\b")
    private val ANY_YEAR = Regex("\\b(20[1-2]\\d)\\b")
    private val BAD_CTX = Regex(
        "(stk|tuv|\\bhu\\b|garanc|gewahrleist|platn|servis|service|inspektion|reifen|pneu|bis|do)\\W{0,4}(?:\\d{1,2}[/.])?\\s*$"
    )
    private val STRONG_YEAR = Regex(
        "(?:modell?(?:ovy)? ?(?:rok|jahr)?|\\bmy|\\bmj\\.?|baujahr|\\bbj\\.?|rok vyroby|rocnik)\\W{0,4}(20[1-2]\\d)\\b"
    )

    private fun okYear(y: Int) = y in 2010..thisYear + 1

    fun parseYear(vararg texts: String?): Int? {
        for (raw in texts) {
            val t = norm(raw)
            YEAR_RE.findAll(t).forEach { m ->
                val y = m.groupValues[1].toInt()
                if (okYear(y)) return y
            }
            MY_SHORT.find(t)?.let { return 2000 + it.groupValues[1].toInt() }
        }
        val cands = mutableSetOf<Int>()
        for (raw in texts) {
            val t = norm(raw)
            ANY_YEAR.findAll(t).forEach { m ->
                val y = m.groupValues[1].toInt()
                val before = t.substring(maxOf(0, m.range.first - 25), m.range.first)
                if (okYear(y) && !BAD_CTX.containsMatchIn(before)) cands += y
            }
        }
        return cands.singleOrNull()
    }

    /** Modelový rok uvedený výslovně: rok v titulku nebo za slovem rok/Modelljahr/MY/Mj./Baujahr. */
    fun strongYear(title: String, desc: String): Int? {
        val t = norm(title)
        val ys = ANY_YEAR.findAll(t).map { it.groupValues[1].toInt() }.filter(::okYear).toSet()
        if (ys.size == 1) return ys.first()
        STRONG_YEAR.find(norm(title + "\n" + desc))?.let {
            val y = it.groupValues[1].toInt()
            if (okYear(y)) return y
        }
        return MY_SHORT.find(t)?.let { 2000 + it.groupValues[1].toInt() }
    }

    // ------------------------------------------------------------ km
    private val KM1 = Regex(
        "(?:najeto|najezd|nalet\\w*|kilometerstand|laufleistung|tachostand|km ?stand)\\D{0,15}([\\d .]{1,9})\\s*(tis|tkm|km)?"
    )
    private val KM2 = Regex("\\b(\\d[\\d .]{0,7})\\s*(km)\\b")

    fun parseKm(text: String): Int? {
        val t = norm(text)
        val m = KM1.find(t) ?: KM2.find(t) ?: return null
        var v = num(m.groupValues[1]) ?: return null
        if (m.groupValues[2] in setOf("tis", "tkm") && v < 1000) v *= 1000
        return v.takeIf { it < 200_000 }
    }

    // ------------------------------------------------------------ výfuk
    private val EXHAUST_BRANDS = linkedMapOf(
        "akrapovic" to "Akrapovič", "arrow" to "Arrow", "sc-project" to "SC-Project",
        "sc project" to "SC-Project", "scproject" to "SC-Project", "leovince" to "LeoVince",
        "leo vince" to "LeoVince", "remus" to "Remus", "yoshimura" to "Yoshimura",
        "termignoni" to "Termignoni", "mivv" to "MIVV", "gpr" to "GPR", "hp corse" to "HP Corse",
        "austin racing" to "Austin Racing", "ixil" to "IXIL", "bodis" to "Bodis", "zard" to "Zard",
        "spark exhaust" to "Spark", "two brothers" to "Two Brothers", "toce" to "Toce",
        "bos exhaust" to "BOS", "laser exhaust" to "Laser", "powerbox" to "Powerbox",
    ).map { (k, v) -> Regex("\\b" + Regex.escape(k) + "\\b") to v }

    private val EXHAUST_GENERIC = listOf(
        "laden\\w* vyfuk", "sportovni vyfuk", "vyfukov\\w* koncovk", "koncovk\\w* vyfuk",
        "aftermarket", "slip[- ]?on", "full ?system", "kompletni vyfuk", "sportauspuff",
        "auspuffanlage", "endschalldampfer", "\\besd\\b", "\\bexhaust\\b", "\\bdecat\\b",
        "vyfuk \\w+ s homologac", "underseat", "shark[- ]?(auspuff|exhaust|vyfuk)",
    ).map(::Regex)

    fun detectExhaust(text: String): String? {
        val t = norm(text)
        EXHAUST_BRANDS.firstOrNull { it.first.containsMatchIn(t) }?.let { return it.second }
        return if (EXHAUST_GENERIC.any { it.containsMatchIn(t) }) "laděný výfuk" else null
    }

    // ------------------------------------------------------------ quickshifter
    private val QS_RE = Regex(
        "q[u]?[i]?ck ?-?shift\\w*|schaltautomat|schaltassist\\w*|\\bq\\+|\\bqs\\+|blipper|" +
            "rychlo ?razen\\w*|rychlo ?radic\\w*|easy ?shift"
    )
    private val QS_NEG = Regex("ohne|nicht|kein|nachrust|optional|\\bbez\\b|chybi|neni|lze dokoupit|mozno dokoupit|not ")

    fun detectQs(text: String): Boolean {
        val t = norm(text)
        return QS_RE.findAll(t).any { m ->
            val ctx = t.substring(maxOf(0, m.range.first - 30), minOf(t.length, m.range.last + 1 + 25))
            !QS_NEG.containsMatchIn(ctx)
        }
    }

    // ------------------------------------------------------------ prodejce
    private val DEALER_CUES = Regex(
        "\\bs\\. ?r\\. ?o\\b|\\ba\\. ?s\\.(?!\\w)|\\bgmbh\\b|autorizovany (dealer|prodejce)|odpoc\\w* dph|cena (bez|vc\\.?|vcetne) dph|" +
            "\\bskladem\\b|nov[ea] moto\\b|predvadec\\w*|financovan\\w*|\\bsplat\\w*|\\bleasing\\w*|protiucet|vykup|" +
            "moznost (splatek|financovani|odpoctu)|novy motocykl|ihned k odberu|zaruka \\d+ (rok|let)|\\d ?(roky|let) zaruk|" +
            "mwst|finanzierung|inzahlungnahme|vorfuhr\\w*|tageszulassung|neufahrzeug"
    )

    fun looksLikeDealer(text: String) = DEALER_CUES.containsMatchIn(norm(text))

    // ------------------------------------------------------------ je to Duke 390?
    private val NOT_DUKE_390 = Regex(
        "super ?duke|duke ?(125|200|250|690|790|890|990|1290|1390)|adventure|\\bsmc\\b|enduro|" +
            "svartpilen|vitpilen|ankauf|\\bsuche\\b|kaufe|koupim|hledam|gesuch|" +
            "\\bdil[yu]\\b|nahradni|ersatzteil|\\bteile\\b|rozprod|ausschlacht|schlachtfest|" +
            "padaci|sturzbugel|kryt |plast|blinkr|zrcatk|polep|samolep"
    )

    fun isDuke390(title: String): Boolean {
        val t = norm(title)
        return "390" in t && "duke" in t && !NOT_DUKE_390.containsMatchIn(t)
    }

    private val MY2526 = Regex("(?:model\\w*(?: rok)?|\\bmy)\\W{0,3}(?:20)?(25|26|27)\\b")
    fun mentionsMy2526(text: String) = MY2526.containsMatchIn(norm(text))

    private val DAMAGED = Regex("bourac|bourana|po nehode|unfall(?!frei)|defekt|motorschaden|poskozen")
    fun looksDamaged(text: String) = DAMAGED.containsMatchIn(norm(text))
}
