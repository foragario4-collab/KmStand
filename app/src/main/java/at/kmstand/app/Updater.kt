package at.kmstand.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** In-App-Update: prüft das neueste GitHub-Release, lädt die APK und öffnet die Installation. */
object Updater {
    private const val DEFAULT_REPO = "foragario4-collab/KmStand"
    private const val AUTO_CHECK_MS = 6L * 60 * 60 * 1000   // automatisch höchstens alle 6 Stunden

    fun repo(): String = BuildConfig.GH_REPO.ifBlank { DEFAULT_REPO }

    /** Nummer des installierten Builds (versionCode = 100 + Build-Nummer). */
    fun currentBuild(): Int = BuildConfig.VERSION_CODE - 100

    class Release(val build: Int, val name: String, val apkUrl: String, val size: Long)

    sealed class State {
        object Idle : State()
        object Checking : State()
        object UpToDate : State()
        class Available(val r: Release) : State()
        class Downloading(val r: Release, val percent: Int) : State()
        class Ready(val r: Release, val file: File) : State()
        class Failed(val msg: String) : State()
    }

    @Volatile
    var state: State = State.Idle
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()

    fun listen(l: () -> Unit) { if (l !in listeners) listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }

    private fun set(s: State) {
        state = s
        main.post { listeners.toList().forEach { it() } }
    }

    fun shouldAutoCheck(c: Context): Boolean {
        val s = state
        if (s is State.Available || s is State.Downloading || s is State.Ready || s is State.Checking) return false
        val last = Store.prefs(c).getLong("updCheckedAt", 0L)
        return System.currentTimeMillis() - last > AUTO_CHECK_MS
    }

    fun check(c: Context, silent: Boolean) {
        val cur = state
        if (cur is State.Checking || cur is State.Downloading) return
        val app = c.applicationContext
        set(State.Checking)
        Thread {
            try {
                val conn = URL("https://api.github.com/repos/${repo()}/releases/latest").openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "KmStand-App")
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                val code = conn.responseCode
                if (code != 200) {
                    conn.disconnect()
                    val msg = if (code == 404) "Kein Release gefunden. Ist das GitHub-Repository öffentlich?"
                    else "GitHub antwortet gerade nicht (Fehler $code)."
                    set(if (silent) State.Idle else State.Failed(msg))
                    return@Thread
                }
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                conn.disconnect()

                val tag = json.optString("tag_name")
                val build = tag.filter { it.isDigit() }.toIntOrNull() ?: 0
                var url = ""
                var size = 0L
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) {
                            url = a.optString("browser_download_url")
                            size = a.optLong("size", 0L)
                            break
                        }
                    }
                }
                Store.prefs(app).edit().putLong("updCheckedAt", System.currentTimeMillis()).apply()

                if (url.isEmpty() || build <= currentBuild()) {
                    set(State.UpToDate)
                } else if (currentBuild() <= 0 && build <= Store.prefs(app).getInt("updInstalledTag", 0)) {
                    // Ältere Version ohne eigene Nummer: dieses Release wurde schon installiert
                    set(State.UpToDate)
                } else {
                    val name = json.optString("name").ifBlank { tag }
                    set(State.Available(Release(build, name, url, size)))
                }
            } catch (e: Exception) {
                set(if (silent) State.Idle else State.Failed("Keine Verbindung zu GitHub. Bist du online?"))
            }
        }.start()
    }

    fun download(c: Context, r: Release) {
        if (state is State.Downloading) return
        val app = c.applicationContext
        set(State.Downloading(r, 0))
        Thread {
            try {
                val dir = File(app.cacheDir, "updates")
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "Km-Stand-${r.build}.apk")

                val conn = URL(r.apkUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "KmStand-App")
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
                val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else r.size

                var done = 0L
                var lastPct = -1
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) { lastPct = pct; set(State.Downloading(r, pct)) }
                            }
                        }
                    }
                }
                conn.disconnect()
                if (done == 0L) throw IllegalStateException("leer")
                set(State.Ready(r, file))
            } catch (e: Exception) {
                set(State.Failed("Download fehlgeschlagen. Bitte nochmal versuchen."))
            }
        }.start()
    }

    /** Darf die App andere Apps installieren? (Einstellung „Unbekannte Apps installieren“) */
    fun canInstall(c: Context): Boolean = c.packageManager.canRequestPackageInstalls()

    fun install(c: Context, file: File) {
        val s = state
        if (s is State.Ready) Store.prefs(c).edit().putInt("updInstalledTag", s.r.build).apply()
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.updates", file)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(i)
    }
}
