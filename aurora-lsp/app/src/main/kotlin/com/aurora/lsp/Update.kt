package com.aurora.lsp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads `latest.json` from the AURORA release host.
 *
 * GitHub Releases was the original source and needed no backend, but the direct connection is
 * unreliable from the mainland; the release host is the same Hong Kong node that serves the blog.
 */
object Update {

    const val ENDPOINT = "https://lingluandf.online/aurora/latest.json"

    data class Release(
        val versionName: String,
        val versionCode: Int,
        val notes: String,
        val url: String
    )

    sealed interface Result {
        data class Newer(val release: Release) : Result
        data class UpToDate(val versionName: String) : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun check(currentVersionCode: Int, currentVersionName: String): Result =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("User-Agent", "AURORA-Android")
                connection.setRequestProperty("Cache-Control", "no-cache")
                try {
                    if (connection.responseCode != 200) {
                        return@withContext Result.Failed("服务器返回 ${connection.responseCode}")
                    }
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val code = json.optInt("versionCode", 0)
                    val name = json.optString("versionName").trim()
                    val url = json.optString("url").trim()
                    if (code <= 0 || name.isEmpty() || url.isEmpty()) {
                        return@withContext Result.Failed("更新信息不完整")
                    }
                    val release = Release(
                        versionName = name,
                        versionCode = code,
                        notes = json.optString("notes").trim(),
                        url = url
                    )
                    if (code > currentVersionCode) Result.Newer(release)
                    else Result.UpToDate(currentVersionName)
                } finally {
                    connection.disconnect()
                }
            } catch (t: Throwable) {
                Result.Failed("网络不可用：${t.javaClass.simpleName}")
            }
        }
}