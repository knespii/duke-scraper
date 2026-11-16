package cz.duke390.hlidac.scrape

import kotlinx.serialization.Serializable

/** Inzerát tak, jak ho vrátí bazar (+ doplněné údaje z detailu). */
@Serializable
data class Listing(
    val source: String,
    val id: String,
    val url: String,
    val title: String,
    val price: Int? = null,
    val currency: String = "CZK",
    var year: Int? = null,
    var km: Int? = null,
    val location: String = "",
    val country: String = "CZ",
    val plz: String = "",
    val image: String = "",
    var desc: String = "",
    var dealer: Boolean = false,
    val isNewBike: Boolean = false,
) {
    val key get() = "$source:$id"
}

/** Inzerát připravený pro zobrazení. */
@Serializable
data class Card(
    val key: String,
    val source: String,
    val url: String,
    val title: String,
    val titleTr: String? = null,
    val desc: String,
    val descTr: String? = null,
    val priceCzk: Int,
    val priceEur: Int? = null,
    val year: Int? = null,
    val km: Int? = null,
    val location: String,
    val country: String,
    val hours: Double? = null,
    val image: String,
    val dealer: Boolean,
    val exhaust: String? = null,
    val qs: Boolean = false,
    val my2526: Boolean = false,
    val generation: Int? = null,
    val damaged: Boolean = false,
    val dealPct: Double? = null,
    val deal: Boolean = false,
    val isNew: Boolean = false,
    val priceDrop: Int = 0,
)

@Serializable
data class Section(val key: String, val title: String, val subtitle: String, val cards: List<Card>)

@Serializable
data class SourceStatus(val name: String, val ok: Boolean, val found: Int = 0, val stale: String? = null, val error: String? = null)

/** Výsledek posledního běhu – to, co ukazuje aplikace. */
@Serializable
data class Report(
    val updatedAt: String,
    val total: Int,
    val eurRate: Double,
    val medians: Map<String, Double>,
    val sources: List<SourceStatus>,
    val sections: List<Section>,
)

// ---------------------------------------------------------------- trvalý stav (cache, historie)
@Serializable
data class History(val firstSeen: String, var lastSeen: String, val prices: MutableList<Int> = mutableListOf())

@Serializable
data class DetailCache(val at: String, val price: Int?, val v: Int, val data: Detail)

@Serializable
data class Detail(val desc: String? = null, val year: Int? = null, val km: Int? = null, val dealer: Boolean? = null)

@Serializable
data class TrCache(val t: String, var at: String)

@Serializable
data class SourceSnapshot(val at: String, val items: List<Listing>)

@Serializable
data class State(
    val listings: MutableMap<String, History> = mutableMapOf(),
    val details: MutableMap<String, DetailCache> = mutableMapOf(),
    val tr: MutableMap<String, TrCache> = mutableMapOf(),
    val lastItems: MutableMap<String, SourceSnapshot> = mutableMapOf(),
    var eur: Double? = null,
    var lastRun: String? = null,
)
