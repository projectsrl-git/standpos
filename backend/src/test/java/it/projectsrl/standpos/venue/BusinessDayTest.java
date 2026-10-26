package it.projectsrl.standpos.venue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class BusinessDayTest {

    private final BusinessDay day = new BusinessDay(ZoneId.of("Europe/Rome"), LocalTime.of(4, 0));

    @Test
    void beforeTheDayChangeASaleBelongsToThePreviousDate() {
        // 26 July 2026, CEST (UTC+2): 03:59 local is still the 25th, 04:00 is the 26th.
        assertThat(day.dateOf(Instant.parse("2026-07-26T01:59:00Z"))).isEqualTo(LocalDate.of(2026, 7, 25));
        assertThat(day.dateOf(Instant.parse("2026-07-26T02:00:00Z"))).isEqualTo(LocalDate.of(2026, 7, 26));
    }

    @Test
    void nextChangeFollowsTheVenueTimeZoneAcrossDaylightSaving() {
        // Summer time ends on 25 October 2026: the change on the 26th is at 04:00 CET = 03:00 UTC.
        assertThat(day.nextChangeAfter(Instant.parse("2026-10-25T20:00:00Z")))
                .isEqualTo(Instant.parse("2026-10-26T03:00:00Z"));
        assertThat(day.nextChangeAfter(Instant.parse("2026-07-25T20:00:00Z")))
                .isEqualTo(Instant.parse("2026-07-26T02:00:00Z"));
        assertThat(day.nextChangeAfter(Instant.parse("2026-07-26T01:00:00Z")))
                .isEqualTo(Instant.parse("2026-07-26T02:00:00Z"));
    }
}
