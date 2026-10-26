package it.projectsrl.standpos.venue;

import it.projectsrl.standpos.common.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalTime;

/** A place that sells: a bar open all year or a three-day festival. Unit of stock and reporting. */
@Entity
@Table(name = "venue")
public class Venue extends TenantOwnedEntity {

    public enum Kind {
        PERMANENT,
        EVENT
    }

    public enum TicketMode {
        RECEIPT_ONLY,
        RECEIPT_AND_TICKETS
    }

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind = Kind.PERMANENT;

    @Column(name = "time_zone", nullable = false)
    private String timeZone = "Europe/Rome";

    @Column(name = "day_change_time", nullable = false)
    private LocalTime dayChangeTime = LocalTime.of(4, 0);

    @Column(name = "receipt_header")
    private String receiptHeader;

    @Column(name = "receipt_footer")
    private String receiptFooter;

    @Enumerated(EnumType.STRING)
    @Column(name = "ticket_mode", nullable = false)
    private TicketMode ticketMode = TicketMode.RECEIPT_ONLY;

    @Column(nullable = false)
    private boolean active = true;

    protected Venue() {}

    public Venue(String name, Kind kind) {
        this.name = name;
        this.kind = kind;
    }

    public void update(String name, Kind kind, String timeZone, LocalTime dayChangeTime, String receiptHeader,
            String receiptFooter, boolean active) {
        this.name = name;
        this.kind = kind;
        this.timeZone = timeZone;
        this.dayChangeTime = dayChangeTime;
        this.receiptHeader = receiptHeader;
        this.receiptFooter = receiptFooter;
        this.active = active;
    }

    public String getName() {
        return name;
    }

    public Kind getKind() {
        return kind;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public LocalTime getDayChangeTime() {
        return dayChangeTime;
    }

    public String getReceiptHeader() {
        return receiptHeader;
    }

    public String getReceiptFooter() {
        return receiptFooter;
    }

    public TicketMode getTicketMode() {
        return ticketMode;
    }

    public boolean isActive() {
        return active;
    }
}
