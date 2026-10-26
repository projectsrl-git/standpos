package it.projectsrl.standpos.printagent.printer;

import static org.assertj.core.api.Assertions.assertThat;

import it.projectsrl.standpos.print.protocol.PrintDocument;
import it.projectsrl.standpos.print.protocol.PrintElement;
import it.projectsrl.standpos.print.protocol.PrintJob;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextPreviewTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T19:15:00Z"), ZoneOffset.UTC);

    private static PrintJob receipt(String printer, int columns) {
        PrintDocument document = PrintDocument.builder()
                .drawerKick()
                .text("Bar oratorio", PrintElement.Align.CENTER, true, PrintElement.Size.DOUBLE_HEIGHT)
                .text("N. 7", PrintElement.Align.CENTER, true, PrintElement.Size.DOUBLE)
                .row("Caffè", "1,20")
                .row("TOTALE", "€ 1,20")
                .text("Scontrino di cortesia – documento non fiscale", PrintElement.Align.CENTER, false,
                        PrintElement.Size.NORMAL)
                .cut()
                .build();
        return new PrintJob(UUID.fromString("01a17738-dedb-72b3-9895-c3b75ad33182"), PrintJob.Kind.RECEIPT,
                new PrintJob.PrinterTarget(printer, columns, "CP858"), document);
    }

    @Test
    void aTextPrinterAppendsAReadablePreviewOfEachJob(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("receipts.txt");
        EscPosJobPrinter printer = new EscPosJobPrinter(true, CLOCK);

        printer.print(receipt("text:" + file, 48));
        printer.print(receipt("text:" + file, 48));

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines.getFirst())
                .startsWith("== RECEIPT 01a17738-dedb-72b3-9895-c3b75ad33182 2026-10-26T19:15:00Z");
        // Double-width text is spread out so it stays centred on the 48 columns.
        assertThat(lines).contains("[drawer]", "[cut]", " ".repeat(18) + "Bar oratorio", " ".repeat(20) + "N .   7");
        // Unicode is kept: the euro sign, the accent and the en dash read as on the screen.
        assertThat(lines).anyMatch(l -> l.startsWith("Caffè") && l.endsWith("1,20"));
        assertThat(lines).anyMatch(l -> l.startsWith("TOTALE") && l.endsWith("€ 1,20"));
        assertThat(lines).anyMatch(l -> l.contains("Scontrino di cortesia – documento non fiscale"));
        assertThat(lines).filteredOn(l -> l.startsWith("== RECEIPT")).hasSize(2);
    }

    @Test
    void thePreviewUsesThePrinterColumns() {
        String text = EscPosJobPrinter.preview(receipt("text:x", 32), CLOCK.instant());
        assertThat(text.lines().filter(l -> l.startsWith("TOTALE")))
                .singleElement().satisfies(l -> assertThat(l).hasSize(32).endsWith(" € 1,20"));
        assertThat(text.lines().skip(1)).allSatisfy(l -> assertThat(l.length()).isLessThanOrEqualTo(32));
    }

    @Test
    void aTextPrinterNeverReachesTheSpooler(@TempDir Path dir) {
        // A spooler lookup would fail on CI (no printers): reaching the file proves it was skipped.
        new EscPosJobPrinter(true, CLOCK).print(receipt("text:" + dir.resolve("out.txt"), 48));
        assertThat(dir.resolve("out.txt")).exists();
    }
}
