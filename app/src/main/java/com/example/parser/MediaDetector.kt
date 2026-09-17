package com.example.parser

import android.webkit.URLUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URI
import java.util.concurrent.TimeUnit

data class MediaItemCandidate(
    val title: String,
    val url: String,
    val format: String, // "MP3" or "MP4"
    val durationSeconds: Int? = null,
    val sizeBytes: Long? = null,
    var isSelected: Boolean = true
)

sealed class DetectResult {
    data class Single(val item: MediaItemCandidate) : DetectResult()
    data class Playlist(val title: String, val items: List<MediaItemCandidate>) : DetectResult()
    data class Restricted(val platform: String, val message: String) : DetectResult()
    data class Error(val message: String) : DetectResult()
}

object MediaDetector {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Domains that enforce DRM, access controls, or platform terms prohibiting third-party scraping/downloading
    private val RESTRICTED_DOMAINS = mapOf(
        "youtube.com" to "YouTube",
        "youtu.be" to "YouTube",
        "spotify.com" to "Spotify",
        "music.apple.com" to "Apple Music",
        "netflix.com" to "Netflix",
        "disneyplus.com" to "Disney+",
        "hulu.com" to "Hulu",
        "primevideo.com" to "Amazon Prime Video"
    )

    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp")

    suspend fun inspectUrl(rawUrl: String, preferredFormat: String): DetectResult = withContext(Dispatchers.IO) {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || !URLUtil.isValidUrl(trimmed)) {
            return@withContext DetectResult.Error("Please enter a valid HTTP or HTTPS URL.")
        }

        // Compliance check
        val host = runCatching { URI(trimmed).host?.lowercase() }.getOrNull() ?: ""
        for ((domain, platform) in RESTRICTED_DOMAINS) {
            if (host.contains(domain)) {
                return@withContext DetectResult.Restricted(
                    platform = platform,
                    message = "In strict compliance with $platform's Terms of Service, DRM, and copyright protection standards, downloading from $platform is not supported. Please provide direct authorized media URLs, M3U playlists, or open podcast feeds."
                )
            }
        }

        try {
            // First check URL path directly
            val path = runCatching { URI(trimmed).path?.lowercase() }.getOrNull() ?: ""
            val extension = path.substringAfterLast('.', "")

            if (extension in AUDIO_EXTENSIONS || extension in VIDEO_EXTENSIONS) {
                val detectedFormat = if (extension in AUDIO_EXTENSIONS) "MP3" else "MP4"
                val filename = path.substringAfterLast('/').substringBeforeLast('.')
                val title = if (filename.isNotBlank()) filename.replace(Regex("[-_+]"), " ").trim() else "Direct Media File"
                return@withContext DetectResult.Single(
                    MediaItemCandidate(
                        title = title.replaceFirstChar { it.uppercase() },
                        url = trimmed,
                        format = detectedFormat
                    )
                )
            }

            // Fetch headers / initial content to detect if it's M3U, RSS feed, JSON playlist or direct media stream
            val request = Request.Builder()
                .url(trimmed)
                .header("User-Agent", "Mozilla/5.0 (Android; MediaDownloader/1.0)")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext DetectResult.Error("Failed to reach server: HTTP ${response.code}")
            }

            val contentType = response.header("Content-Type", "")?.lowercase() ?: ""
            val contentDisposition = response.header("Content-Disposition", "") ?: ""

            // Check if response is direct media stream
            if (contentType.startsWith("audio/") || contentType.startsWith("video/")) {
                val detectedFormat = if (contentType.startsWith("audio/")) "MP3" else "MP4"
                var title = extractFilenameFromHeader(contentDisposition)
                if (title.isBlank()) {
                    title = path.substringAfterLast('/').substringBeforeLast('.')
                }
                if (title.isBlank()) title = "Media Stream (${detectedFormat})"
                return@withContext DetectResult.Single(
                    MediaItemCandidate(
                        title = title.replaceFirstChar { it.uppercase() },
                        url = trimmed,
                        format = detectedFormat,
                        sizeBytes = response.body?.contentLength()?.takeIf { it > 0 }
                    )
                )
            }

            val bodyString = response.body?.string() ?: ""
            if (bodyString.isBlank()) {
                return@withContext DetectResult.Error("The provided URL returned an empty response.")
            }

            // Check if it's M3U or M3U8 playlist
            if (bodyString.contains("#EXTM3U") || extension == "m3u" || extension == "m3u8" || contentType.contains("mpegurl")) {
                val items = parseM3uPlaylist(bodyString, trimmed, preferredFormat)
                if (items.isNotEmpty()) {
                    val playlistName = path.substringAfterLast('/').substringBeforeLast('.').ifBlank { "M3U Media Playlist" }
                    return@withContext DetectResult.Playlist(playlistName, items)
                }
            }

            // Check if it's RSS podcast / media feed
            if (bodyString.contains("<rss") || bodyString.contains("<feed") || contentType.contains("xml")) {
                val feedResult = parseXmlFeed(bodyString, preferredFormat)
                if (feedResult != null && feedResult.items.isNotEmpty()) {
                    return@withContext feedResult
                }
            }

            // Check if it's JSON playlist
            if (bodyString.trim().startsWith("[") || bodyString.trim().startsWith("{")) {
                val jsonResult = parseJsonPlaylist(bodyString, preferredFormat)
                if (jsonResult != null && jsonResult.items.isNotEmpty()) {
                    return@withContext jsonResult
                }
            }

            // Fallback: Check if webpage contains direct media links
            val extractedLinks = extractMediaLinksFromHtml(bodyString, trimmed, preferredFormat)
            if (extractedLinks.isNotEmpty()) {
                val title = extractHtmlTitle(bodyString) ?: "Extracted Media Batch"
                return@withContext DetectResult.Playlist(title, extractedLinks)
            }

            return@withContext DetectResult.Error(
                "Could not find supported media files or playlists at this URL. Please verify the URL points to a media file, M3U/M3U8 playlist, or open RSS podcast feed."
            )

        } catch (e: Exception) {
            return@withContext DetectResult.Error(
                e.localizedMessage ?: "Failed to connect to media host. Check your internet connection."
            )
        }
    }

    private fun extractFilenameFromHeader(header: String): String {
        val match = Regex("""filename\*?=['"]?(?:UTF-\d['"]*)?([^'";]+)['"]?""", RegexOption.IGNORE_CASE).find(header)
        return match?.groupValues?.get(1)?.substringBeforeLast('.') ?: ""
    }

    private fun parseM3uPlaylist(content: String, baseUrl: String, preferredFormat: String): List<MediaItemCandidate> {
        val items = mutableListOf<MediaItemCandidate>()
        val lines = content.lines()
        var currentTitle = ""
        var currentDuration: Int? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
                val info = trimmed.substring(8)
                val commaIndex = info.indexOf(',')
                if (commaIndex != -1) {
                    val durationStr = info.substring(0, commaIndex).trim()
                    currentDuration = durationStr.toIntOrNull()
                    currentTitle = info.substring(commaIndex + 1).trim()
                } else {
                    currentTitle = info.trim()
                }
            } else if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                val absoluteUrl = resolveAbsoluteUrl(baseUrl, trimmed)
                val itemTitle = if (currentTitle.isNotBlank()) currentTitle else {
                    trimmed.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Track ${items.size + 1}" }
                }
                val ext = absoluteUrl.substringAfterLast('.', "").lowercase()
                val format = if (ext in AUDIO_EXTENSIONS) "MP3" else if (ext in VIDEO_EXTENSIONS) "MP4" else preferredFormat

                items.add(
                    MediaItemCandidate(
                        title = itemTitle,
                        url = absoluteUrl,
                        format = format,
                        durationSeconds = currentDuration
                    )
                )
                currentTitle = ""
                currentDuration = null
            }
        }
        return items
    }

    private fun parseXmlFeed(xml: String, preferredFormat: String): DetectResult.Playlist? {
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var inItem = false
            var channelTitle = "Media Feed"
            var currentTitle = ""
            var currentUrl = ""
            var currentType = ""
            var currentLength: Long? = null
            val items = mutableListOf<MediaItemCandidate>()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name?.lowercase() ?: ""
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tagName == "item" || tagName == "entry") {
                            inItem = true
                            currentTitle = ""
                            currentUrl = ""
                            currentType = ""
                            currentLength = null
                        } else if (!inItem && tagName == "title") {
                            channelTitle = parser.nextText().trim().ifBlank { channelTitle }
                        } else if (inItem && tagName == "title") {
                            currentTitle = parser.nextText().trim()
                        } else if (inItem && (tagName == "enclosure" || tagName == "media:content")) {
                            currentUrl = parser.getAttributeValue(null, "url") ?: ""
                            currentType = parser.getAttributeValue(null, "type") ?: ""
                            currentLength = parser.getAttributeValue(null, "length")?.toLongOrNull()
                        } else if (inItem && tagName == "link") {
                            val href = parser.getAttributeValue(null, "href")
                            if (!href.isNullOrBlank() && (href.endsWith(".mp3") || href.endsWith(".mp4") || href.endsWith(".m4a"))) {
                                currentUrl = href
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tagName == "item" || tagName == "entry") {
                            if (currentUrl.isNotBlank()) {
                                val ext = currentUrl.substringAfterLast('.', "").lowercase()
                                val format = if (currentType.contains("audio") || ext in AUDIO_EXTENSIONS) {
                                    "MP3"
                                } else if (currentType.contains("video") || ext in VIDEO_EXTENSIONS) {
                                    "MP4"
                                } else {
                                    preferredFormat
                                }
                                val title = currentTitle.ifBlank { "Media Item ${items.size + 1}" }
                                items.add(
                                    MediaItemCandidate(
                                        title = title,
                                        url = currentUrl,
                                        format = format,
                                        sizeBytes = currentLength
                                    )
                                )
                            }
                            inItem = false
                        }
                    }
                }
                eventType = parser.next()
            }
            if (items.isNotEmpty()) {
                return DetectResult.Playlist(channelTitle, items)
            }
        } catch (e: Exception) {
            // Ignore XML parse errors
        }
        return null
    }

    private fun parseJsonPlaylist(jsonStr: String, preferredFormat: String): DetectResult.Playlist? {
        try {
            val items = mutableListOf<MediaItemCandidate>()
            var title = "JSON Playlist"

            val trimmed = jsonStr.trim()
            val array = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val obj = JSONObject(trimmed)
                title = obj.optString("title", obj.optString("name", "JSON Playlist"))
                obj.optJSONArray("items") ?: obj.optJSONArray("tracks") ?: obj.optJSONArray("media") ?: JSONArray()
            }

            for (i in 0 until array.length()) {
                val itemObj = array.optJSONObject(i) ?: continue
                val url = itemObj.optString("url", itemObj.optString("src", itemObj.optString("link", "")))
                if (url.isNotBlank()) {
                    val itemTitle = itemObj.optString("title", itemObj.optString("name", "Track ${i + 1}"))
                    val ext = url.substringAfterLast('.', "").lowercase()
                    val format = if (ext in AUDIO_EXTENSIONS) "MP3" else if (ext in VIDEO_EXTENSIONS) "MP4" else preferredFormat
                    items.add(
                        MediaItemCandidate(
                            title = itemTitle,
                            url = url,
                            format = format,
                            durationSeconds = itemObj.optInt("duration", 0).takeIf { it > 0 }
                        )
                    )
                }
            }
            if (items.isNotEmpty()) {
                return DetectResult.Playlist(title, items)
            }
        } catch (e: Exception) {
            // Ignore JSON errors
        }
        return null
    }

    private fun extractMediaLinksFromHtml(html: String, baseUrl: String, preferredFormat: String): List<MediaItemCandidate> {
        val links = mutableListOf<MediaItemCandidate>()
        val regex = Regex("""href=["']([^"']+\.(?:mp3|mp4|m4a|wav|ogg|flac|aac|webm))["']""", RegexOption.IGNORE_CASE)
        val matches = regex.findAll(html)
        val seen = mutableSetOf<String>()

        for (match in matches) {
            val relUrl = match.groupValues[1]
            val absUrl = resolveAbsoluteUrl(baseUrl, relUrl)
            if (seen.add(absUrl)) {
                val ext = absUrl.substringAfterLast('.', "").lowercase()
                val filename = absUrl.substringAfterLast('/').substringBeforeLast('.')
                val title = filename.replace(Regex("[-_+]"), " ").replaceFirstChar { it.uppercase() }
                val format = if (ext in AUDIO_EXTENSIONS) "MP3" else "MP4"
                links.add(
                    MediaItemCandidate(
                        title = title,
                        url = absUrl,
                        format = format
                    )
                )
            }
        }
        return links
    }

    private fun extractHtmlTitle(html: String): String? {
        val match = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(html)
        return match?.groupValues?.get(1)?.trim()
    }

    private fun resolveAbsoluteUrl(base: String, relative: String): String {
        return try {
            URI(base).resolve(relative).toString()
        } catch (e: Exception) {
            if (relative.startsWith("http://") || relative.startsWith("https://")) {
                relative
            } else {
                base.substringBeforeLast('/') + "/" + relative.removePrefix("/")
            }
        }
    }
}
