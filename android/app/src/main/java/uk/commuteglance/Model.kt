package uk.commuteglance

import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

object CommuteRules {
    val zone: ZoneId = ZoneId.of("Europe/London")
    fun mode(now: ZonedDateTime, days: Set<Int>, morningStart: LocalTime, morningEnd: LocalTime, eveningStart: LocalTime, eveningEnd: LocalTime): String {
        val local = now.withZoneSameInstant(zone)
        if (local.dayOfWeek.value !in days) return "off"
        val t = local.toLocalTime()
        return when { t >= morningStart && t < morningEnd -> "morning"; t >= eveningStart && t < eveningEnd -> "evening"; else -> "off" }
    }
    // Used only outside active windows. Construct each start in London civil time,
    // rather than adding 24 hours across a daylight-saving transition.
    fun nextWindow(now: ZonedDateTime, days: Set<Int>, morningStart: LocalTime, eveningStart: LocalTime): Pair<ZonedDateTime, String>? {
        val local = now.withZoneSameInstant(zone)
        return (0L..7L).flatMap { offset ->
            val date = local.toLocalDate().plusDays(offset)
            if (date.dayOfWeek.value !in days) emptyList()
            else listOf(date.atTime(morningStart).atZone(zone) to "morning", date.atTime(eveningStart).atZone(zone) to "evening")
        }.filter { it.first.isAfter(local) }.minByOrNull { it.first.toInstant() }
    }
    fun minutesRemaining(at: Instant, now: Instant) = ceil(Duration.between(now, at).seconds / 60.0).toLong()
    fun time(at: Instant): String = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(at)
}
data class Departure(val route: String, val destination: String, val expected: Instant?, val scheduled: Instant?, val platform: String, val status: String, val duration: Int) {
    fun widgetLine(now: Instant, walk: Int): String {
        val at = expected ?: scheduled
        val time = at?.let { CommuteRules.time(it) } ?: "Time unknown"
        val remaining = expected?.let { CommuteRules.minutesRemaining(it, now) }
        val state = when {
            status == "Cancelled" -> "CANCELLED"
            remaining == null -> status.ifBlank { "No prediction" }
            remaining <= 0 -> "elapsed · refresh"
            duration == 0 && remaining < walk -> "too soon"
            else -> "est."
        }
        val delay = if (duration > 0 && expected != null && scheduled != null && status != "Cancelled")
            Duration.between(scheduled, expected).toMinutes() else 0
        val details = if (duration > 0) " · ${duration}m · P${platform.ifBlank { "—" }}" else ""
        return "$time $route$details · $state" + if (delay > 0) " +${delay}m late" else ""
    }
    fun line(now: Instant, walk: Int, countdown: Boolean = true): String {
        val at = expected ?: scheduled
        val whenText = if (at == null) "Time unknown" else CommuteRules.time(at)
        val remaining = expected?.let { CommuteRules.minutesRemaining(it, now) }
        val estimate = when {
            status == "Cancelled" -> "CANCELLED"
            remaining == null -> status
            remaining <= 0 -> "prediction elapsed · refresh"
            countdown -> "~${remaining}m" + if (duration == 0 && remaining < walk) " · too soon" else ""
            else -> "estimated"
        }
        val delay = if (duration > 0 && expected != null && scheduled != null) Duration.between(scheduled,expected).toMinutes() else 0
        val delayText = if (delay > 0 && status != "Cancelled") " · +${delay}m late" else ""
        val details = if (duration > 0) " · ${duration}m journey · P${platform.ifBlank { "—" }}" else ""
        return "$whenText  $route → $destination$details\n$estimate$delayText"
    }
}
data class Board(val source: String, val fetched: Instant, val sourceAt: Instant?, val stale: Boolean, val warning: String, val departures: List<Departure>)
