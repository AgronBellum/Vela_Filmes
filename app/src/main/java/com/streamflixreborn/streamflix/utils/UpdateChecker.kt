package com.streamflixreborn.streamflix.utils

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class UpdateChecker(private val context: Context) {

    @Serializable
    data class VersionInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String
    )

    companion object {
        private const val VERSION_URL = "https://raw.githubusercontent.com/AgronBellum/Vela_Filmes/main/version.json"
        private const val TAG = "UpdateChecker"
    }

    suspend fun checkAndPrompt() = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val request = Request.Builder().url(VERSION_URL).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) return@withContext

            val json = response.body?.string() ?: return@withContext
            val info = Json.decodeFromString<VersionInfo>(json)

            val currentVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
            }

            if (info.versionCode > currentVersion) {
                withContext(Dispatchers.Main) {
                    showUpdateDialog(info)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro: ${e.message}")
        }
    }

    private fun showUpdateDialog(info: VersionInfo) {
        AlertDialog.Builder(context)
            .setTitle("Atualização disponível")
            .setMessage("v${info.versionName}\n\n${info.changelog}")
            .setPositiveButton("Baixar") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl))
                context.startActivity(intent)
            }
            .setNegativeButton("Depois", null)
            .show()
    }
}