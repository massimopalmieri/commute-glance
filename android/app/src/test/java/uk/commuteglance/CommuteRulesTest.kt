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
    @Test fun elapsedNeverSaysDue(){val now=Instant.parse("2026-11-02T07:30:00Z");val d=Departure("166","Croydon",now.minusSeconds(1),null,"","Predicted",0);assertTrue(d.line(now,3).contains("elapsed"))}
    @Test fun cancelledNeverCountsDown(){val now=Instant.now();val d=Departure("Train","LBG",now.plusSeconds(300),now,"","Cancelled",15);assertTrue(d.line(now,3).contains("CANCELLED"))}
    @Test fun delayedTrainShowsDelay(){val now=Instant.now();val d=Departure("Train","LBG",now.plusSeconds(600),now.plusSeconds(300),"4","Expected",15);assertTrue(d.line(now,3).contains("+5m late"))}
}
