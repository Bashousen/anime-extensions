package eu.kanade.tachiyomi.animeextension.pt.meusanimes.extractors

import android.util.Base64
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.unpacker.Unpacker
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class MeusAnimesExtractor(private val client: OkHttpClient, private val headers: Headers) {

    fun getVideosFromUrl(url: String, prefix: String): List<Video> {
        val playlistUtils by lazy { PlaylistUtils(client, headers) }
        val decodedUrl = decodeUrl(url)

        val base64String = client.newCall(GET(decodedUrl, headers)).execute().body.string()
            .substringAfter("atob(\"")
            .substringBefore("\"")

        val targetUrl = String(Base64.decode(base64String, Base64.DEFAULT), Charsets.UTF_8)

        val newClient = client.newBuilder().cookieJar(SessionJar()).build()

        val newHeaders = headers.newBuilder()
            .set("accept-language", "pt-BR,pt;q=0.8")
            .set("referer", "https://${decodedUrl.toHttpUrl().host}")
            .set("sec-fetch-dest", "document")
            .build()

        val redirectUrl = newClient.newCall(GET(targetUrl, newHeaders)).execute().body.string()
            .substringAfter("url=")
            .substringBefore("\"")

        val base64Bytes = newClient.newCall(GET(redirectUrl, headers)).execute().body.string()
            .substringAfter("h=[")
            .substringBefore("]")
            .replace("\",\"", "")
            .trim('"')
            .hexToByteArray()

        val finalUrl = String(Base64.decode(base64Bytes, Base64.DEFAULT), Charsets.UTF_8)

        val doc = newClient.newCall(GET(finalUrl, headers)).execute().asJsoup()
        val script = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")
            ?.data()
            ?.let { Unpacker.unpack(it) } ?: return emptyList()

        val playlistPath = script
            .substringAfter("\"videoUrl\":\"")
            .substringBefore("\"")
            .replace("\\", "")

        val playlistUrl = "https://${finalUrl.toHttpUrl().host}$playlistPath"

        return playlistUtils.extractFromHls(playlistUrl, videoNameGen = { quality -> "$prefix - $quality" })
    }

    private class SessionJar : CookieJar {
        private val cookies: MutableMap<String, List<Cookie>> = mutableMapOf()

        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies[url.host] ?: emptyList()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            this.cookies[url.host] = cookies
        }
    }

    private fun decodeUrl(url: String): String {
        val html = client.newCall(GET(url, headers)).execute().body.string()
        val base64 = html
            .substringAfter("data-redir=\"")
            .substringBefore("\"")
            .ifEmpty { throw Exception("data-redir não encontrado") }
        val jsArray = html
            .substringAfter("_kc = ")
            .substringBefore(";")

        val keyBytes = Json.decodeFromString<IntArray>(jsArray)
        val decodedBytes = Base64.decode(base64, Base64.DEFAULT)
        val result = ByteArray(decodedBytes.size)

        for (i in decodedBytes.indices) {
            result[i] = (decodedBytes[i].toInt() xor keyBytes[i % keyBytes.size]).toByte()
        }

        return String(result, Charsets.UTF_8)
    }
}
