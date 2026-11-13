package com.ahmady.ytdownloader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Port dari server.js: server HTTP lokal (127.0.0.1:3000) yang dipakai index.html lewat WebView.
 * Bedanya: yt-dlp/ffmpeg berjalan lewat library youtubedl-android, bukan binary Mac.
 */
object AppServer {
    const val PORT = 3000

    @Volatile var uiGone = false

    private lateinit var app: Context
    private var server: Server? = null
    private val ready = CountDownLatch(1)
    @Volatile private var initError: String? = null

    private val jobs = ConcurrentHashMap<String, Job>()
    private val counter = AtomicInteger()
    private val infoCache = ConcurrentHashMap<String, JSONObject>()

    private val YT_URL = Regex("^https?://(www\\.|m\\.|music\\.)?(youtube\\.com|youtu\\.be)/")

    private val FORMATS: Map<String, List<Pair<String, String?>>> = mapOf(
        "best" to listOf("-f" to "bv*+ba/b", "--merge-output-format" to "mkv"),
        "1080" to listOf("-f" to "bv*[height<=1080]+ba/b[height<=1080]", "-S" to "vcodec:h264,res,acodec:m4a", "--merge-output-format" to "mp4"),
        "720" to listOf("-f" to "bv*[height<=720]+ba/b[height<=720]", "-S" to "vcodec:h264,res,acodec:m4a", "--merge-output-format" to "mp4"),
        "audio" to listOf("-x" to null, "--audio-format" to "mp3", "--audio-quality" to "0"),
    )

    class Job(val clip: Boolean) {
        @Volatile var status = "running"
        @Volatile var percent = 0.0
        @Volatile var stage = "Menyiapkan..."
        @Volatile var eta: Long? = null
        @Volatile var file: String? = null
        @Volatile var error = ""
    }

    class Clip(val start: Double, val end: Double?, val precise: Boolean)

    @Synchronized
    fun start(ctx: Context) {
        if (server != null) return
        app = ctx.applicationContext
        server = Server().also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        Thread { initEngine() }.start()
    }

    private fun initEngine() {
        try {
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
            // yt-dlp bawaan library cepat usang; YouTube sering berubah, jadi update tiap app dibuka
            try { YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE) } catch (_: Exception) {}
        } catch (e: Exception) {
            initError = e.message ?: e.toString()
        }
        ready.countDown()
    }

    private fun awaitReady() {
        if (!ready.await(180, TimeUnit.SECONDS)) throw Exception("Mesin yt-dlp belum siap, coba lagi sebentar.")
        initError?.let { throw Exception("Gagal menyiapkan yt-dlp: $it") }
    }

    fun maybeStopService() {
        if (uiGone && jobs.values.none { it.status == "running" }) {
            app.stopService(Intent(app, DownloadService::class.java))
        }
    }

    // "90", "1:30", "01:02:03", "1:30.5" -> detik. Kosong -> null. Salah -> NaN.
    private fun parseTime(v: String?): Double? {
        if (v == null || v.trim().isEmpty()) return null
        val parts = v.trim().split(":")
        if (parts.size > 3 || parts.any { !Regex("^\\d+(\\.\\d+)?$").matches(it) }) return Double.NaN
        return parts.fold(0.0) { acc, p -> acc * 60 + p.toDouble() }
    }

    private fun tag(sec: Double): String {
        val s = sec.toLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        val x = s % 60
        return (if (h > 0) "${h}h" else "") + (if (h > 0 || m > 0) "${m}m" else "") + "${x}s"
    }

    private fun oembed(url: String): JSONObject {
        val c = URL("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, "UTF-8")).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        return JSONObject()
            .put("title", j.optString("title"))
            .put("channel", j.optString("author_name"))
            .put("thumbnail", j.optString("thumbnail_url"))
    }

    private fun info(url: String): JSONObject {
        infoCache[url]?.let { return it }
        awaitReady()
        val req = YoutubeDLRequest(url)
        req.addOption("--no-playlist")
        req.addOption("--skip-download")
        req.addOption("--no-warnings")
        req.addOption("--dump-single-json")
        val out = YoutubeDL.getInstance().execute(req).out
        val j = JSONObject(out.substring(out.indexOf('{')))
        val r = JSONObject()
            .put("title", j.optString("title"))
            .put("channel", j.optString("channel", j.optString("uploader")))
            .put("duration", if (j.has("duration") && !j.isNull("duration")) j.getDouble("duration") else JSONObject.NULL)
            .put("thumbnail", j.optString("thumbnail"))
            .put("live", j.optBoolean("is_live"))
        infoCache[url] = r
        return r
    }

    private fun startJob(url: String, quality: String, clip: Clip?): String {
        val id = counter.incrementAndGet().toString()
        val job = Job(clip != null)
        jobs[id] = job
        val processId = "job-$id"
        val work = File(app.externalCacheDir ?: app.cacheDir, "dl-$id").apply { deleteRecursively(); mkdirs() }
        val suffix = if (clip != null) " (" + tag(clip.start) + "-" + (clip.end?.let { tag(it) } ?: "akhir") + ")" else ""
        val streams = if (quality == "audio") 1 else 2

        Thread {
            try {
                awaitReady()
                val req = YoutubeDLRequest(url)
                req.addOption("--no-playlist")
                req.addOption("-N", "8")
                req.addOption("--http-chunk-size", "10M")
                req.addOption("--throttled-rate", "100K")
                if (clip != null) {
                    req.addOption("--download-sections", "*" + clip.start + "-" + (clip.end ?: "inf"))
                    if (clip.precise && quality != "audio") req.addOption("--force-keyframes-at-cuts")
                    job.stage = "Mengunduh potongan..."
                }
                req.addOption("-o", File(work, "%(title).150B [%(id)s]$suffix.%(ext)s").absolutePath)
                for ((k, v) in FORMATS.getValue(quality)) if (v == null) req.addOption(k) else req.addOption(k, v)

                var stream = 0
                var lastP = 0f
                val postProcess = Regex("^\\[(Merger|ExtractAudio|VideoRemuxer|VideoConvertor|FFmpeg\\w*|Fixup\\w*)\\]")
                YoutubeDL.getInstance().execute(req, processId) { progress, eta, line ->
                    if (progress >= 0f) {
                        if (progress + 20f < lastP && stream < streams - 1) stream++ // stream kedua (audio) dimulai
                        lastP = progress
                        job.percent = minOf(((stream + progress / 100.0) / streams) * 100, 99.0)
                        job.stage = if (quality == "audio" || stream == 1) "Mengunduh audio" else "Mengunduh video"
                        job.eta = if (eta >= 0) eta.toLong() else null
                    }
                    if (postProcess.containsMatchIn(line)) {
                        job.stage = "Memproses file..."; job.percent = 99.0; job.eta = null
                    }
                }

                if (job.status == "cancelled") return@Thread
                val result = work.listFiles()
                    ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !Regex("\\.f\\d+\\.").containsMatchIn(it.name) }
                    ?.maxByOrNull { it.lastModified() }
                    ?: throw Exception("File hasil unduhan tidak ditemukan")
                job.stage = "Menyimpan ke folder Download/YT..."; job.eta = null
                saveToDownloads(result)
                job.file = result.name
                job.status = "done"; job.percent = 100.0; job.stage = "Selesai"
            } catch (e: Exception) {
                if (job.status != "cancelled") {
                    job.status = "error"
                    job.error = e.message ?: e.toString()
                }
            } finally {
                work.deleteRecursively()
                maybeStopService()
            }
        }.start()
        return id
    }

    private fun saveToDownloads(file: File) {
        val mime = when (file.extension.lowercase()) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YT")
        }
        val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("Gagal membuat file di folder Download")
        app.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    private class Server : NanoHTTPD("127.0.0.1", PORT) {
        private fun json(status: Response.Status, o: JSONObject): Response =
            newFixedLengthResponse(status, "application/json", o.toString())

        private fun err(status: Response.Status, msg: String) = json(status, JSONObject().put("error", msg))

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            try {
                if (session.method == Method.GET && uri == "/") {
                    val html = app.assets.open("index.html").bufferedReader().use { it.readText() }
                    return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
                }

                if (session.method == Method.GET && uri.startsWith("/api/status/")) {
                    val job = jobs[uri.substringAfterLast('/')] ?: return err(Response.Status.NOT_FOUND, "Job tidak ada")
                    return json(Response.Status.OK, JSONObject()
                        .put("status", job.status).put("percent", job.percent).put("stage", job.stage)
                        .put("speed", 0).put("eta", job.eta ?: JSONObject.NULL)
                        .put("downloaded", 0).put("total", 0)
                        .put("file", job.file ?: JSONObject.NULL).put("error", job.error).put("clip", job.clip))
                }

                if (session.method == Method.POST) {
                    val files = HashMap<String, String>()
                    session.parseBody(files)
                    val data = try { JSONObject(files["postData"] ?: "") } catch (e: Exception) { null }

                    if (uri.startsWith("/api/cancel/")) {
                        val id = uri.substringAfterLast('/')
                        val job = jobs[id]
                        if (job != null && job.status == "running") {
                            job.status = "cancelled"; job.stage = "Dibatalkan"
                            try { YoutubeDL.getInstance().destroyProcessById("job-$id") } catch (_: Exception) {}
                        }
                        return json(Response.Status.OK, JSONObject().put("ok", true))
                    }

                    val url = data?.optString("url") ?: ""
                    if (data == null) return err(Response.Status.BAD_REQUEST, "Request tidak valid")
                    if (!YT_URL.containsMatchIn(url)) return err(Response.Status.BAD_REQUEST, "Link harus dari YouTube")

                    if (uri == "/api/oembed") return json(Response.Status.OK, oembed(url))
                    if (uri == "/api/info") return json(Response.Status.OK, info(url))

                    if (uri == "/api/download") {
                        val quality = data.optString("quality")
                        if (!FORMATS.containsKey(quality)) return err(Response.Status.BAD_REQUEST, "Kualitas tidak valid")
                        var clip: Clip? = null
                        data.optJSONObject("clip")?.let { c ->
                            val start = parseTime(c.optString("start"))
                            val end = parseTime(c.optString("end"))
                            if ((start != null && start.isNaN()) || (end != null && end.isNaN()))
                                return err(Response.Status.BAD_REQUEST, "Format waktu salah. Contoh: 1:30 atau 01:02:03")
                            if (end != null && end <= (start ?: 0.0))
                                return err(Response.Status.BAD_REQUEST, "Waktu selesai harus lebih besar dari waktu mulai")
                            clip = Clip(start ?: 0.0, end, c.optBoolean("precise"))
                        }
                        return json(Response.Status.OK, JSONObject().put("id", startJob(url, quality, clip)))
                    }
                }
                return err(Response.Status.NOT_FOUND, "Not found")
            } catch (e: Exception) {
                return err(Response.Status.INTERNAL_ERROR, e.message ?: e.toString())
            }
        }
    }
}
