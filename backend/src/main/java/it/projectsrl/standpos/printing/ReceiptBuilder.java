package it.projectsrl.standpos.printing;

import it.projectsrl.standpos.common.Money;
import it.projectsrl.standpos.print.protocol.PrintDocument;
import it.projectsrl.standpos.print.protocol.PrintElement.Align;
import it.projectsrl.standpos.print.protocol.PrintElement.Size;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Lays out the courtesy receipt (the layout lives here, the agent only renders it): venue header, the daily order
 * number printed large, time and register, lines with prices, total, payment, the venue's footer and the fixed
 * non-fiscal notice, which tenants cannot change or remove.
 */
public final class ReceiptBuilder {

    /** Fixed by the platform (CLAUDE.md rule 1): never configurable, always printed. */
    public static final String NON_FISCAL_NOTICE = "Scontrino di cortesia – documento non fiscale";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public record Line(String name, int quantity, long unitPriceCents, long lineTotalCents, String note) {}

    public enum PaymentMethod {
        CASH,
        MANUAL_CARD,
        SUMUP_CARD
    }

    public record Receipt(String venueName, String header, String footer, ZoneId zone, int dailyNumber,
            Instant paidAt, String registerName, List<Line> lines, long totalCents, PaymentMethod method,
            Long tenderedCents, Long changeCents, boolean kickDrawer, boolean reprint) {}

    private ReceiptBuilder() {}

    public static PrintDocument build(Receipt r) {
        PrintDocument.Builder doc = PrintDocument.builder();
        if (r.kickDrawer()) {
            doc.drawerKick(); // first, so the drawer opens while the receipt prints
        }
        doc.text(r.venueName(), Align.CENTER, true, Size.DOUBLE_HEIGHT);
        if (r.header() != null && !r.header().isBlank()) {
            for (String line : r.header().strip().split("\\R")) {
                doc.centered(line);
            }
        }
        doc.separator();
        doc.text("ORDINE", Align.CENTER, false, Size.NORMAL);
        doc.text("N. " + r.dailyNumber(), Align.CENTER, true, Size.DOUBLE);
        if (r.reprint()) {
            doc.text("RISTAMPA", Align.CENTER, true, Size.NORMAL);
        }
        doc.row(TIME.format(r.paidAt().atZone(r.zone())), r.registerName());
        doc.separator();
        for (Line line : r.lines()) {
            doc.row(line.name(), Money.format(line.lineTotalCents()));
            if (line.quantity() > 1) {
                doc.text("  " + line.quantity() + " x " + Money.format(line.unitPriceCents()));
            }
            if (line.note() != null && !line.note().isBlank()) {
                doc.text("  > " + line.note().strip());
            }
        }
        doc.separator();
        doc.row("TOTALE", Money.euro(r.totalCents()), true, Size.DOUBLE_HEIGHT);
        switch (r.method()) {
            case CASH -> {
                doc.row("Contanti", Money.euro(r.tenderedCents() == null ? r.totalCents() : r.tenderedCents()));
                doc.row("Resto", Money.euro(r.changeCents() == null ? 0 : r.changeCents()));
            }
            case MANUAL_CARD, SUMUP_CARD -> doc.row("Carta", Money.euro(r.totalCents()));
        }
        if (r.footer() != null && !r.footer().isBlank()) {
            doc.feed(1);
            for (String line : r.footer().strip().split("\\R")) {
                doc.centered(line);
            }
        }
        doc.feed(1);
        doc.text(NON_FISCAL_NOTICE, Align.CENTER, false, Size.NORMAL);
        doc.cut();
        return doc.build();
    }

    /** Opens the cash drawer without printing anything ("Senza scontrino" with cash). */
    public static PrintDocument drawerKick() {
        return PrintDocument.builder().drawerKick().build();
    }

    /** A test page for the manager: € sign, accented letters, sizes, cut. */
    public static PrintDocument testPage(String printerName, String windowsName, String codePage, Instant now,
            ZoneId zone) {
        return PrintDocument.builder()
                .text("StandPOS", Align.CENTER, true, Size.DOUBLE)
                .text("Prova di stampa", Align.CENTER, false, Size.NORMAL)
                .separator()
                .row("Stampante", printerName)
                .row("Windows", windowsName)
                .row("Code page", codePage)
                .row("Ora", TIME.format(now.atZone(zone)))
                .separator()
                .row("Euro", Money.euro(123456))
                .text("Accenti: à è é ì ò ù")
                .text("Grassetto", Align.LEFT, true, Size.NORMAL)
                .text("Doppia altezza", Align.LEFT, false, Size.DOUBLE_HEIGHT)
                .text("N. 88", Align.CENTER, true, Size.DOUBLE)
                .feed(1)
                .text(NON_FISCAL_NOTICE, Align.CENTER, false, Size.NORMAL)
                .cut()
                .build();
    }
}
