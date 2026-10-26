package it.projectsrl.standpos.venue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * A venue's business day: it starts at the venue's day change time in its time zone, so a sale at 01:30 with a 04:00
 * day change belongs to the previous date. Daily order numbers and the end of the usage-cap grace period follow it.
 */
public record BusinessDay(ZoneId zone, LocalTime dayChange) {

    /** The business date an instant belongs to. */
    public LocalDate dateOf(Instant instant) {
        ZonedDateTime local = instant.atZone(zone);
        LocalDate date = local.toLocalDate();
        return local.toLocalTime().isBefore(dayChange) ? date.minusDays(1) : date;
    }

    /** When the business date {@code date} starts (DST-safe: a skipped local time moves forward). */
    public Instant startOf(LocalDate date) {
        return ZonedDateTime.of(date, dayChange, zone).toInstant();
    }

    /** The first day change strictly after {@code instant}. */
    public Instant nextChangeAfter(Instant instant) {
        return startOf(dateOf(instant).plusDays(1));
    }
}
