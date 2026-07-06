package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object SubtitleEncoding {

    private val windows1252: Charset = Charset.forName("windows-1252")
    private val textSubtitleExtensions = setOf("srt", "vtt", "ass", "ssa", "txt")
    private val httpClient by lazy { OkHttpClient() }

    fun normalizeFile(file: File): File {
        if (!file.name.isTextSubtitle()) return file

        val bytes = file.readBytes()
        val text = decode(bytes)
        file.writeText(text, StandardCharsets.UTF_8)
        return file
    }

    fun normalizeToCacheFile(context: Context, uri: Uri, fileName: String): Uri {
        if (!fileName.isTextSubtitle()) return uri

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return uri
        val text = decode(bytes)
        val extension = File(fileName).extension.ifBlank { "srt" }
        val output = File.createTempFile(
            "${File(fileName).nameWithoutExtension.ifBlank { "subtitle" }}-",
            ".$extension",
            context.cacheDir
        )
        output.writeText(text, StandardCharsets.UTF_8)
        return output.toUri()
    }

    suspend fun normalizeVideoSubtitles(
        context: Context,
        subtitles: List<Video.Subtitle>,
        headers: Map<String, String> = emptyMap()
    ): List<Video.Subtitle> = withContext(Dispatchers.IO) {
        subtitles.map { subtitle ->
            runCatching {
                val normalizedUri = normalizeSubtitleUri(context, subtitle.file, subtitle.label, headers)
                subtitle.copy(file = normalizedUri.toString())
            }.onFailure {
                Log.w("SubtitleEncoding", "Keeping original subtitle ${subtitle.label}: ${it.message}")
            }.getOrDefault(subtitle)
        }
    }

    fun isTextSubtitleFileName(fileName: String): Boolean =
        File(fileName).extension.lowercase() in textSubtitleExtensions

    private fun String.isTextSubtitle(): Boolean = isTextSubtitleFileName(this)

    private fun normalizeSubtitleUri(
        context: Context,
        file: String,
        label: String,
        headers: Map<String, String>
    ): Uri {
        val uri = file.toUri()
        val fileName = subtitleFileName(uri, label)
        if (!fileName.isTextSubtitle()) return uri

        val bytes = when (uri.scheme?.lowercase()) {
            "http", "https" -> downloadSubtitle(file, headers)
            "content" -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            else -> uri.path?.let { File(it).takeIf(File::exists)?.readBytes() }
        } ?: return uri

        val text = decode(bytes)
        val extension = File(fileName).extension.ifBlank { "srt" }
        val output = File.createTempFile(
            "${File(fileName).nameWithoutExtension.ifBlank { "subtitle" }}-",
            ".$extension",
            context.cacheDir
        )
        output.writeText(text, StandardCharsets.UTF_8)
        return output.toUri()
    }

    private fun downloadSubtitle(url: String, headers: Map<String, String>): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", headers["User-Agent"] ?: "Mozilla/5.0")
            .apply {
                headers.forEach { (name, value) ->
                    if (!name.equals("User-Agent", ignoreCase = true)) header(name, value)
                }
            }
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body?.bytes() ?: error("Empty subtitle body")
        }
    }

    private fun subtitleFileName(uri: Uri, label: String): String {
        val pathName = uri.lastPathSegment?.substringBefore("?")
        val labelName = label.substringBefore("?")
        return when {
            pathName?.isTextSubtitle() == true -> pathName
            labelName.isTextSubtitle() -> labelName
            else -> pathName ?: labelName
        }
    }

    private fun decode(bytes: ByteArray): String {
        return when {
            bytes.startsWith(0xEF, 0xBB, 0xBF) ->
                String(bytes.copyOfRange(3, bytes.size), StandardCharsets.UTF_8)

            bytes.startsWith(0xFF, 0xFE) ->
                String(bytes.copyOfRange(2, bytes.size), StandardCharsets.UTF_16LE)

            bytes.startsWith(0xFE, 0xFF) ->
                String(bytes.copyOfRange(2, bytes.size), StandardCharsets.UTF_16BE)

            else -> chooseBestDecodedText(bytes)
        }
    }

    private fun chooseBestDecodedText(bytes: ByteArray): String {
        val strictUtf8 = tryDecodeUtf8(bytes)
        if (strictUtf8 != null && !looksLikeMojibake(strictUtf8)) return strictUtf8

        val relaxedUtf8 = String(bytes, StandardCharsets.UTF_8)
        val legacy = String(bytes, windows1252)

        return when {
            scoreText(relaxedUtf8) >= scoreText(legacy) -> relaxedUtf8
            else -> legacy
        }
    }

    private fun scoreText(text: String): Int {
        val replacementPenalty = text.count { it == '\uFFFD' } * 4
        val mojibakePenalty = mojibakeMarkers.sumOf { marker ->
            marker.toRegex(RegexOption.IGNORE_CASE).findAll(text).count()
        } * 3
        val portugueseBonus = text.count { it in portugueseChars }
        return portugueseBonus - replacementPenalty - mojibakePenalty
    }

    private fun looksLikeMojibake(text: String): Boolean =
        mojibakeMarkers.any { marker -> text.contains(marker, ignoreCase = true) }

    private fun tryDecodeUtf8(bytes: ByteArray): String? {
        return try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun ByteArray.startsWith(vararg values: Int): Boolean {
        if (size < values.size) return false
        return values.indices.all { this[it].toInt() and 0xFF == values[it] }
    }

    private val mojibakeMarkers = listOf("Ã", "Â", "â€™", "â€œ", "â€", "�")
    private val portugueseChars = "áàâãäéèêëíìîïóòôõöúùûüçÁÀÂÃÄÉÈÊËÍÌÎÏÓÒÔÕÖÚÙÛÜÇ"
}
