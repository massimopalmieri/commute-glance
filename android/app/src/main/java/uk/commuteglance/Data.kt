package uk.commuteglance

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.*

class Settings(c: Context) {
    val prefs = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun get(k: String, fallback: String) = prefs.getString(k, fallback) ?: fallback
    val base get() = get("base", "").trimEnd('/')
    val token get() = get("token", "")
    val stop get() = get("stop", "490007669N")
    val stopName get() = get("stopName", "Haling Park Gardens · GW")
    val routes get() = get("routes", "166,405")
    val walk get() = get("walk", "3").toIntOrNull()?.coerceIn(0,60) ?: 3
    val notifications get() = prefs.getBoolean("notifications", false)
    fun mode(): String = CommuteRules.mode(ZonedDateTime.now(), get("days", "1,4").split(',').mapNotNull { it.trim().toIntOrNull() }.toSet(),
        LocalTime.parse(get("morningStart","07:00")), LocalTime.parse(get("morningEnd","08:30")),
        LocalTime.parse(get("eveningStart","16:00")), LocalTime.parse(get("eveningEnd","19:00")))
}
fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
class Repository(val context: Context) {
    private val cache = context.getSharedPreferences("boards", Context.MODE_PRIVATE)
    val settings = Settings(context)
    fun request(path: String): String {
        check(settings.base.isNotBlank()) { "Set your backend address in Settings" }
        val uri = URI(settings.base + path)
        require(uri.scheme == "https" || (uri.scheme == "http" && context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)) { "Use HTTPS for the backend" }
        require(uri.host != null && uri.userInfo == null) { "Invalid backend address" }
        val con = uri.toURL().openConnection() as HttpURLConnection
        con.connectTimeout = 12000; con.readTimeout = 30000; con.instanceFollowRedirects = false
        con.setRequestProperty("Accept", "application/json")
        if (settings.token.isNotBlank()) con.setRequestProperty("Authorization", "Bearer " + settings.token)
        try {
            val status = con.responseCode
            val stream = if (status in 200..299) con.inputStream else con.errorStream
            val bytes = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) { val n=input.read(chunk); if(n<0) break; out.write(chunk,0,n); check(out.size()<=2*1024*1024) { "Response too large" } }
                out.toByteArray()
            } ?: byteArrayOf()
            check(bytes.size <= 2 * 1024 * 1024) { "Response too large" }
            val body = bytes.toString(Charsets.UTF_8)
            check(status in 200..299) { runCatching { JSONObject(body).optString("error", "HTTP $status") }.getOrDefault("HTTP $status") }
            return body
        } finally { con.disconnect() }
    }
    fun key(kind: String) = when(kind) {
        "bus" -> "/v1/buses?stop=${encode(settings.stop)}&routes=${encode(settings.routes)}"
        "out" -> "/v1/trains?from=ECR&to=LBG&maxMinutes=20"
        else -> "/v1/trains?from=LBG&to=ECR&maxMinutes=20"
    }
    private fun cacheKey(kind: String) = settings.base + key(kind)
    fun refresh(kind: String) {
        val path=key(kind); val savedKey=settings.base+path
        try { val raw=request(path); parse(raw); cache.edit().putString(savedKey,raw).remove(savedKey+":error").apply() }
        catch(e:Exception) { cache.edit().putString(savedKey+":error",e.message?:"Unable to refresh").apply(); throw e }
    }
    fun recordError(kind: String, e: Exception) { cache.edit().putString(cacheKey(kind)+":error", e.message ?: "Unable to refresh").apply() }
    fun error(kind: String) = cache.getString(cacheKey(kind)+":error", "") ?: ""
    fun board(kind: String): Board? = cache.getString(cacheKey(kind), null)?.let { runCatching { parse(it) }.getOrNull() }
    fun parse(raw: String): Board {
        val o = JSONObject(raw); val a = o.getJSONArray("departures")
        fun instant(o: JSONObject, key: String): Instant? = if (o.isNull(key) || !o.has(key)) null else Instant.parse(o.getString(key))
        val rows = (0 until a.length()).map { i -> val d = a.getJSONObject(i); Departure(d.getString("route"), d.getString("destination"), instant(d,"expectedAt"), instant(d,"scheduledAt"), d.optString("platform"), d.optString("status"), d.optInt("durationMinutes")) }
        return Board(o.getString("source"), Instant.parse(o.getString("fetchedAt")), instant(o,"sourceAt"), o.optBoolean("stale"), o.optString("warning"), rows)
    }
    fun summary(kind: String, limit: Int, countdown: Boolean): String {
        val b = board(kind) ?: return error(kind).ifBlank { "No data yet · refresh" }
        val now = Instant.now(); val age = Duration.between(b.fetched, now).seconds.coerceAtLeast(0)
        if (age > 600) return "Data expired · last checked ${CommuteRules.time(b.fetched)}\nRefresh required" + if (error(kind).isNotBlank()) "\n${error(kind)}" else ""
        val rows = b.departures.filter { d -> val at=d.expected?:d.scheduled; at==null || at>now.minusSeconds(60) }.take(limit)
        val stale = b.stale || age > 90 || error(kind).isNotBlank() || b.sourceAt?.let { Duration.between(it,now).seconds > 120 } == true
        return (if (rows.isEmpty()) "No upcoming departures in this feed" else rows.joinToString("\n\n") { it.line(now,settings.walk,countdown) }) +
            "\n${if(stale) "STALE · " else ""}${b.source} checked ${CommuteRules.time(b.fetched)}" +
            if (error(kind).isNotBlank() || b.warning.isNotBlank()) "\nRefresh unavailable · tap to retry" else ""
    }
}
