package app.chargelimit

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Finds, downloads and installs a newer release from GitHub.
 * Android accepts the file only when it has the signing key of the installed app.
 */
object Updater {
    class Release(val version: String, val apkUrl: String)

    private const val LATEST = "https://api.github.com/repos/dz0ny/charge-limiter/releases/latest"
    private const val MAX_APK_BYTES = 50L * 1024 * 1024
    private const val TIMEOUT_MS = 15_000

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** Runs [work] on a new thread and gives the result to [done] on the main thread. */
    fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
        Thread {
            val result = runCatching(work)
            Handler(Looper.getMainLooper()).post { done(result) }
        }.start()
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "charge-limiter")
        return connection
    }

    /** Returns the latest release when it is newer than this app. This call uses the network. */
    fun newerRelease(context: Context): Release? {
        val connection = open(LATEST)
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub answered ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            if (!UpdatePolicy.isNewer(tag, currentVersion(context))) return null
            val list = json.getJSONArray("assets")
            val assets = (0 until list.length()).map { i ->
                val asset = list.getJSONObject(i)
                asset.getString("name") to asset.getString("browser_download_url")
            }
            val url = UpdatePolicy.apkUrl(assets) ?: return null
            return Release(tag.removePrefix("v"), url)
        } finally {
            connection.disconnect()
        }
    }

    /** Downloads the APK of [release] into the cache folder. This call uses the network. */
    fun download(context: Context, release: Release): File {
        val target = File(context.cacheDir, "update.apk")
        val connection = open(release.apkUrl)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Download answered ${connection.responseCode}")
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_APK_BYTES) throw IOException("The file is too large")
                        output.write(buffer, 0, read)
                    }
                }
            }
            return target
        } finally {
            connection.disconnect()
        }
    }

    /** Gives [apk] to the system installer. [UpdateReceiver] gets the result. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android can then install later updates without a question.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("update", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            // The system adds the result to this intent, so the intent must be mutable.
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val result = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, UpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or mutable,
            )
            session.commit(result.intentSender)
        }
        apk.delete()
    }
}
