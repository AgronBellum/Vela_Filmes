package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.streamflixreborn.streamflix.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

object InAppUpdater {

    // MUDAR AQUI: Seu GitHub
    private const val GITHUB_OWNER = "AgronBellum"
    private const val GITHUB_REPO = "Vela_Filmes"

    private data class Version(val name: String) : Comparable<Version> {
        override operator fun compareTo(other: Version): Int {
            val thisParts = this.name.split(".").toTypedArray()
            val thatParts = other.name.split(".").toTypedArray()
            for (i in 0 until max(thisParts.size, thatParts.size)) {
                val thisPart = thisParts.getOrNull(i)?.toIntOrNull() ?: 0
                val thatPart = thatParts.getOrNull(i)?.toIntOrNull() ?: 0
                if (thisPart < thatPart) return -1
                if (thisPart > thatPart) return 1
            }
            return 0
        }
    }

    suspend fun getReleaseUpdate(): GitHub.Release? {
        val latestRelease = GitHub.Releases.getLatestRelease(GITHUB_OWNER, GITHUB_REPO)
        val currentVersion = BuildConfig.VERSION_NAME

        if (Version(latestRelease.tagName.substringAfter("v")) > Version(currentVersion)) {
            return latestRelease
        }
        return null
    }

    suspend fun getNewReleases(): List<GitHub.Release> {
        val releases = GitHub.Releases.getReleases(GITHUB_OWNER, GITHUB_REPO)
        val currentVersion = BuildConfig.VERSION_NAME

        val newReleases = releases
            .filter { Version(it.tagName.substringAfter("v")) > Version(currentVersion) }
            .filter { getCompatibleAsset(it) != null }

        return newReleases
    }

    fun getCompatibleAsset(release: GitHub.Release): GitHub.Release.Asset? =
        release.assets.firstOrNull { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) && when (BuildConfig.APP_LAYOUT) {
                "mobile" -> asset.name.endsWith("-mobile.apk", ignoreCase = true)
                "tv" -> asset.name.endsWith("-tv.apk", ignoreCase = true)
                else -> !asset.name.endsWith("-mobile.apk", ignoreCase = true) &&
                    !asset.name.endsWith("-tv.apk", ignoreCase = true)
            }
        }

    suspend fun downloadApk(context: Context, asset: GitHub.Release.Asset): File {
        context.cacheDir.listFiles()
            ?.filter { it.extension == "apk" }
            ?.forEach { it.delete() }

        val apk = File(context.cacheDir, "update-${asset.id}.apk")

        withContext(Dispatchers.IO) {
            val connection = (URL(asset.browserDownloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            connection.inputStream.use { input ->
                FileOutputStream(apk).use { output -> input.copyTo(output) }
            }
            connection.disconnect()
        }

        return apk
    }

    fun installApk(context: Context, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        }

        val contentUri = FileProvider.getUriForFile(
            context,
            BuildConfig.APPLICATION_ID + ".provider",
            apk
        )
        val intent = Intent(Intent.ACTION_VIEW).also { intent ->
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            intent.setDataAndType(contentUri, "application/vnd.android.package-archive")
        }
        context.startActivity(intent)
    }
}
