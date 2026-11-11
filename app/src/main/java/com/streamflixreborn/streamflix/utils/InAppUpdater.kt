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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object InAppUpdater {

    private const val VERSION_URL =
        "https://raw.githubusercontent.com/AgronBellum/Vela_Filmes/main/version.json"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String = "",
    )

    suspend fun getUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val separator = if (VERSION_URL.contains('?')) '&' else '?'
        val connection = (URL("$VERSION_URL${separator}t=${System.currentTimeMillis()}")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "Vela/${BuildConfig.VERSION_NAME}")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("Falha ao verificar atualização: HTTP $status")
            }

            val info = connection.inputStream.bufferedReader().use { reader ->
                json.decodeFromString<UpdateInfo>(reader.readText())
            }

            info.takeIf {
                it.versionCode > BuildConfig.VERSION_CODE &&
                    it.versionName.isNotBlank() &&
                    it.apkUrl.startsWith("https://")
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadApk(context: Context, update: UpdateInfo): File {
        context.cacheDir.listFiles()
            ?.filter { it.extension == "apk" }
            ?.forEach { it.delete() }

        val apk = File(context.cacheDir, "vela-${update.versionCode}.apk")

        withContext(Dispatchers.IO) {
            val connection = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Vela/${BuildConfig.VERSION_NAME}")
            }

            try {
                val status = connection.responseCode
                if (status !in 200..299) {
                    throw IllegalStateException("Falha ao baixar atualização: HTTP $status")
                }

                connection.inputStream.use { input ->
                    FileOutputStream(apk).use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }

            if (apk.length() < 100_000L || apk.inputStream().use { input ->
                    input.read() != 'P'.code || input.read() != 'K'.code
                }) {
                apk.delete()
                throw IllegalStateException("O arquivo baixado não é um APK válido")
            }
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
