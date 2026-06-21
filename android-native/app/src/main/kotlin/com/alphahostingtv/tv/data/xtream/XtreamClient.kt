package com.alphahostingtv.tv.data.xtream

import com.alphahostingtv.tv.data.db.CategoryEntity
import com.alphahostingtv.tv.data.db.ChannelEntity
import com.alphahostingtv.tv.data.db.EpgEntity
import com.alphahostingtv.tv.data.db.EpisodeEntity
import com.alphahostingtv.tv.data.db.MovieEntity
import com.alphahostingtv.tv.data.db.ProviderEntity
import com.alphahostingtv.tv.data.db.SeriesEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Xtream-Codes player_api.php client. Endpoints used:
 *   action= get_live_categories | get_live_streams
 *           get_vod_categories  | get_vod_streams | get_vod_info
 *           get_series_categories | get_series   | get_series_info
 *           get_short_epg (channel-level EPG)
 *
 * Stream URLs:
 *   Live:    {base}/live/{user}/{pass}/{stream_id}.ts
 *   Movies:  {base}/movie/{user}/{pass}/{stream_id}.{container_extension}
 *   Episode: {base}/series/{user}/{pass}/{episode_id}.{container_extension}
 */
@Singleton
class XtreamClient @Inject constructor(private val ok: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    // ---- Live ----

    suspend fun fetchLiveCategories(p: ProviderEntity): List<CategoryEntity> = arrAt(p, "get_live_categories") { o ->
        val rid = o["category_id"]?.str() ?: return@arrAt null
        val name = o["category_name"]?.str() ?: return@arrAt null
        CategoryEntity(providerId = p.id, kind = "LIVE", remoteId = rid, name = name)
    }

    suspend fun fetchLiveStreams(p: ProviderEntity): List<ChannelEntity> = arrAt(p, "get_live_streams") { o ->
        val sid = o["stream_id"]?.str() ?: return@arrAt null
        val name = o["name"]?.str() ?: return@arrAt null
        val url = "${p.baseUrl}/live/${p.username.urlEnc()}/${p.password.urlEnc()}/$sid.ts"
        val tvArchive = o["tv_archive"]?.let { e -> e.str()?.toIntOrNull() ?: 0 } ?: 0
        val archiveDuration = o["tv_archive_duration"]?.let { e -> e.str()?.toIntOrNull() ?: 0 } ?: 0
        ChannelEntity(
            providerId = p.id,
            remoteId = sid,
            name = name,
            logo = o["stream_icon"]?.str(),
            categoryId = o["category_id"]?.str(),
            streamUrl = url,
            // Xtream exposes the xmltv ID either as epg_channel_id or, on some
            // panels, the same value embedded in tv_archive_duration JSON. We
            // take the canonical field and fall back to None.
            epgChannelId = o["epg_channel_id"]?.str()?.takeIf { it.isNotBlank() },
            // tv_archive == 1 means the provider keeps recordings; we synth
            // the timeshift URL from Catchup.synthesizeXtreamTimeshift().
            catchupSource = null,
            catchupDays = if (tvArchive >= 1) archiveDuration.coerceAtLeast(1) else 0,
        )
    }

    // ---- VOD (Movies) ----

    suspend fun fetchVodCategories(p: ProviderEntity): List<CategoryEntity> = arrAt(p, "get_vod_categories") { o ->
        val rid = o["category_id"]?.str() ?: return@arrAt null
        val name = o["category_name"]?.str() ?: return@arrAt null
        CategoryEntity(providerId = p.id, kind = "MOVIE", remoteId = rid, name = name)
    }

    suspend fun fetchVodStreams(p: ProviderEntity): List<MovieEntity> = arrAt(p, "get_vod_streams") { o ->
        val sid = o["stream_id"]?.str() ?: return@arrAt null
        val name = o["name"]?.str() ?: return@arrAt null
        val cont = o["container_extension"]?.str() ?: "mp4"
        val url = "${p.baseUrl}/movie/${p.username.urlEnc()}/${p.password.urlEnc()}/$sid.$cont"
        MovieEntity(
            providerId = p.id,
            remoteId = sid,
            name = name,
            poster = o["stream_icon"]?.str(),
            categoryId = o["category_id"]?.str(),
            streamUrl = url,
            container = cont,
            year = o["releaseDate"]?.str()?.take(4)?.toIntOrNull() ?: o["year"]?.str()?.toIntOrNull(),
            rating = o["rating"]?.str()?.toDoubleOrNull(),
            plot = null,
        )
    }

    // ---- Series ----

    suspend fun fetchSeriesCategories(p: ProviderEntity): List<CategoryEntity> = arrAt(p, "get_series_categories") { o ->
        val rid = o["category_id"]?.str() ?: return@arrAt null
        val name = o["category_name"]?.str() ?: return@arrAt null
        CategoryEntity(providerId = p.id, kind = "SERIES", remoteId = rid, name = name)
    }

    suspend fun fetchSeries(p: ProviderEntity): List<SeriesEntity> = arrAt(p, "get_series") { o ->
        val rid = o["series_id"]?.str() ?: return@arrAt null
        val name = o["name"]?.str() ?: return@arrAt null
        SeriesEntity(
            providerId = p.id,
            remoteId = rid,
            name = name,
            poster = o["cover"]?.str(),
            categoryId = o["category_id"]?.str(),
            year = o["releaseDate"]?.str()?.take(4)?.toIntOrNull(),
            rating = o["rating"]?.str()?.toDoubleOrNull(),
            plot = o["plot"]?.str(),
        )
    }

    /** Pull all episodes for one series. Returns pairs (season, episode_entity_without_id). */
    suspend fun fetchSeriesEpisodes(p: ProviderEntity, seriesRemoteId: String, seriesLocalId: Long): List<EpisodeEntity> {
        val body = get("${p.baseUrl}/player_api.php?username=${p.username.urlEnc()}&password=${p.password.urlEnc()}&action=get_series_info&series_id=$seriesRemoteId")
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val episodes = root["episodes"] as? JsonObject ?: return emptyList()
        val out = mutableListOf<EpisodeEntity>()
        episodes.forEach { (seasonKey, listEl) ->
            val seasonNo = seasonKey.toIntOrNull() ?: 0
            val list = listEl as? JsonArray ?: return@forEach
            list.forEach { ep ->
                val o = ep as? JsonObject ?: return@forEach
                val rid = o["id"]?.str() ?: return@forEach
                val episodeNo = o["episode_num"]?.str()?.toIntOrNull() ?: 0
                val title = o["title"]?.str() ?: "Episode $episodeNo"
                val cont = o["container_extension"]?.str() ?: "mkv"
                val url = "${p.baseUrl}/series/${p.username.urlEnc()}/${p.password.urlEnc()}/$rid.$cont"
                out += EpisodeEntity(
                    seriesId = seriesLocalId,
                    remoteId = rid,
                    season = seasonNo,
                    episode = episodeNo,
                    title = title,
                    streamUrl = url,
                    container = cont,
                    plot = (o["info"] as? JsonObject)?.get("plot")?.str(),
                )
            }
        }
        return out
    }

    // ---- EPG ----

    /** Short EPG (next ~5 programmes) for a single channel. */
    suspend fun fetchShortEpg(p: ProviderEntity, channelRemoteId: String, channelLocalId: Long): List<EpgEntity> {
        val body = get("${p.baseUrl}/player_api.php?username=${p.username.urlEnc()}&password=${p.password.urlEnc()}&action=get_short_epg&stream_id=$channelRemoteId")
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val listings = root["epg_listings"] as? JsonArray ?: return emptyList()
        return listings.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val title = decodeBase64(o["title"]?.str()) ?: return@mapNotNull null
            val desc = decodeBase64(o["description"]?.str())
            val start = o["start_timestamp"]?.str()?.toLongOrNull()?.times(1000)
                ?: o["start"]?.str()?.let { parseDate(it) } ?: return@mapNotNull null
            val end = o["stop_timestamp"]?.str()?.toLongOrNull()?.times(1000)
                ?: o["end"]?.str()?.let { parseDate(it) } ?: (start + 30 * 60_000)
            EpgEntity(channelId = channelLocalId, title = title, description = desc, startMs = start, endMs = end)
        }
    }

    // ---- Helpers ----

    suspend fun validateLogin(p: ProviderEntity): Unit {
        val body = get("${p.baseUrl}/player_api.php?username=${p.username.urlEnc()}&password=${p.password.urlEnc()}")
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return
        val status = root["status"]?.str()?.trim()?.lowercase()
        if (status == "error" || status == "failed" || status == "0") {
            throw IllegalStateException(root["message"]?.str() ?: "Server rejected credentials")
        }
        val userInfo = root["user_info"] as? JsonObject ?: return
        ensureNotRejected(userInfo)
    }

    private suspend inline fun <T : Any> arrAt(p: ProviderEntity, action: String, transform: (JsonObject) -> T?): List<T> {
        val body = get("${p.baseUrl}/player_api.php?username=${p.username.urlEnc()}&password=${p.password.urlEnc()}&action=$action")
        val parsed = json.parseToJsonElement(body)
        when (parsed) {
            is JsonObject -> {
                val userInfo = parsed["user_info"] as? JsonObject
                ensureNotRejected(userInfo)
                val status = parsed["status"]?.str()?.trim()?.lowercase()
                if (status == "error" || status == "failed" || status == "0") {
                    throw IllegalStateException(parsed["message"]?.str() ?: "Server error for $action")
                }
                val msg = parsed["message"]?.str()?.trim()
                val err = parsed["error"]?.str()?.trim()
                // Some Xtream panels return {} or {"user_info":{...}} for an empty
                // catalog (e.g. live-only package with no VOD). If there are no
                // error/message indicators, treat as an empty list rather than throwing.
                if (err.isNullOrBlank() && msg.isNullOrBlank()) {
                    return emptyList()
                }
                throw IllegalStateException(
                    when {
                        !err.isNullOrBlank() -> "Server error: $err"
                        !msg.isNullOrBlank() -> "Server response: $msg"
                        else -> "Server did not return a list for $action"
                    },
                )
            }
            is JsonArray -> return parsed.mapNotNull { (it as? JsonObject)?.let(transform) }
            else -> throw IllegalStateException("Invalid response from server for $action")
        }
    }

    private fun ensureNotRejected(userInfo: JsonObject?) {
        if (!isAuthFailure(userInfo)) return
        val message = userInfo
            ?.get("message")
            ?.str()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: userInfo
                ?.get("status")
                ?.str()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: "Invalid username or password."
        throw SecurityException("Login failed: $message")
    }

    private fun isAuthFailure(userInfo: JsonObject?): Boolean {
        val authValue = userInfo?.get("auth")
        if (authValue == null) return false
        val auth = authValue.str()?.trim()?.lowercase()
        return when (auth) {
            null -> false
            "1", "true", "active", "enabled", "ok" -> false
            "0", "false", "expired", "disabled", "banned", "inactive", "blocked" -> true
            else -> auth.toIntOrNull()?.let { it <= 0 } ?: false
        }
    }

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        ok.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            // Some Xtream panels (e.g. certain XUI builds) return 4xx for bad
            // credentials instead of 200 with auth:0. Read the body anyway so
            // ensureNotRejected() can surface a proper "Invalid password" message
            // rather than a raw "HTTP 404" error.
            if (!resp.isSuccessful && resp.code !in 400..499) {
                error("HTTP ${resp.code} $url")
            }
            resp.body?.string().orEmpty()
        }
    }

    private fun String.urlEnc(): String = java.net.URLEncoder.encode(this, "UTF-8")

    private fun decodeBase64(s: String?): String? = s?.let {
        runCatching { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
    }

    private fun parseDate(s: String): Long? = runCatching {
        // Xtream sends "yyyy-MM-dd HH:mm:ss" in UTC.
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        fmt.parse(s)?.time
    }.getOrNull()
}
