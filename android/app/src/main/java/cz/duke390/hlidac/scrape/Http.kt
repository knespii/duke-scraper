package cz.duke390.hlidac.scrape

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, url: String) : IOException("HTTP $code: $url")

object Http {
    private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    /** Pauza mezi požadavky na jeden bazar, ať ho nezatěžujeme. */
    var requestDelayMs = 1000L

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun url(base: String, params: Map<String, String>?): String {
        if (params.isNullOrEmpty()) return base
        val b = base.toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build().toString()
    }

    suspend fun text(url: String, params: Map<String, String>? = null, pause: Boolean = true): String {
        if (pause) delay(requestDelayMs)
        val full = url(url, params)
        return withContext(Dispatchers.IO) {
            val req = Request.Builder().url(full)
                .header("User-Agent", UA)
                .header("Accept-Language", "cs,de;q=0.8,en;q=0.6")
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw HttpException(r.code, full)
                r.body?.string() ?: ""
            }
        }
    }

    suspend fun doc(url: String, params: Map<String, String>? = null): Document {
        val html = text(url, params)
        return Jsoup.parse(html, url(url, params))
    }
}
