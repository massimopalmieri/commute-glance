package uk.commuteglance
import org.junit.Assert.*
import org.junit.Test
import java.time.*
class CommuteRulesTest {
    private fun mode(at:String)=CommuteRules.mode(ZonedDateTime.parse(at),setOf(1,4),LocalTime.of(7,0),LocalTime.of(8,30),LocalTime.of(16,0),LocalTime.of(19,0))
    @Test fun londonSummerTime(){assertEquals("morning",mode("2026-07-06T06:30:00Z"))}
    @Test fun windowEndExcluded(){assertEquals("off",mode("2026-11-02T08:30:00Z"))}
    @Test fun weekendOff(){assertEquals("off",mode("2026-11-01T07:30:00Z"))}
    @Test fun evening(){assertEquals("evening",mode("2026-11-02T16:00:00Z"))}
    @Test fun nextWindowUsesConfiguredDaysAndBothDirections(){
        val next=CommuteRules.nextWindow(ZonedDateTime.parse("2026-11-03T21:30:00Z"),setOf(1,4),LocalTime.of(7,0),LocalTime.of(16,0))!!
        assertEquals(Instant.parse("2026-11-05T07:00:00Z"),next.first.toInstant())
        assertEquals("morning",next.second)
        val afternoon=CommuteRules.nextWindow(ZonedDateTime.parse("2026-11-05T09:00:00Z"),setOf(1,4),LocalTime.of(7,0),LocalTime.of(16,0))!!
        assertEquals("evening",afternoon.second)
        assertEquals(16,afternoon.first.hour)
    }
    @Test fun nextWindowCrossesClockChangeInLondonTime(){
        val next=CommuteRules.nextWindow(ZonedDateTime.parse("2026-10-24T20:00:00Z"),setOf(1),LocalTime.of(7,0),LocalTime.of(16,0))!!
        assertEquals(Instant.parse("2026-10-26T07:00:00Z"),next.first.toInstant())
        assertNull(CommuteRules.nextWindow(ZonedDateTime.now(),emptySet(),LocalTime.of(7,0),LocalTime.of(16,0)))
    }
    @Test fun elapsedNeverSaysDue(){val now=Instant.parse("2026-11-02T07:30:00Z");val d=Departure("166","Croydon",now.minusSeconds(1),null,"","Predicted",0);assertTrue(d.line(now,3).contains("elapsed"))}
    @Test fun cancelledNeverCountsDown(){val now=Instant.now();val d=Departure("Train","LBG",now.plusSeconds(300),now,"","Cancelled",15);assertTrue(d.line(now,3).contains("CANCELLED"))}
    @Test fun delayedTrainShowsDelay(){val now=Instant.now();val d=Departure("Train","LBG",now.plusSeconds(600),now.plusSeconds(300),"4","Expected",15);assertTrue(d.line(now,3).contains("+5m late"))}
    @Test fun compactBusMarksTooSoonAndElapsed(){
        val now=Instant.parse("2026-11-02T07:30:00Z")
        val d=Departure("166","West Croydon",now.plusSeconds(60),null,"","Predicted",0)
        assertTrue(d.widgetLine(now,3).contains("too soon"))
        assertTrue(d.copy(expected=now.minusSeconds(1)).widgetLine(now,3).contains("elapsed"))
        assertFalse(d.widgetLine(now,3).contains("\n"))
    }
    @Test fun compactRailRetainsCancellationAndDelay(){
        val now=Instant.parse("2026-11-02T07:30:00Z")
        val d=Departure("Train","LBG",now.plusSeconds(600),now.plusSeconds(300),"4","Expected",15)
        assertTrue(d.widgetLine(now,3).contains("P4"))
        assertTrue(d.widgetLine(now,3).contains("+5m late"))
        val cancelled=d.copy(expected=null,platform="",status="Cancelled").widgetLine(now,3)
        assertTrue(cancelled.contains("CANCELLED"))
        assertFalse(cancelled.contains("est."))
    }
    @Test fun singleLineRailSummaryKeepsPredictionPlatformAndDelay(){
        val scheduled=Instant.parse("2026-11-02T07:30:00Z")
        val d=Departure("Southern","LBG",scheduled.plusSeconds(180),scheduled,"4","Expected",17)
        assertEquals("~07:33 · 17m · P4 · +3m",d.railWidgetLine())
        assertEquals("07:30 CANCELLED",d.copy(expected=null,platform="",status="Cancelled").railWidgetLine())
        assertTrue(d.copy(expected=null,status="Delayed").railWidgetLine().contains("scheduled"))
    }
}
