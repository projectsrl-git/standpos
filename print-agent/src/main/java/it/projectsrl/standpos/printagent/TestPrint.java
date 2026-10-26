package it.projectsrl.standpos.printagent;

import it.projectsrl.standpos.print.protocol.PrintDocument;
import it.projectsrl.standpos.print.protocol.PrintElement.Align;
import it.projectsrl.standpos.print.protocol.PrintElement.Size;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The local sample printed by {@code test-print}, without the server: it exercises everything a receipt needs on a new
 * printer (code page with € and Italian accented letters, bold, double size, rows, the en dash of the fixed line,
 * cut and drawer kick).
 */
final class TestPrint {

    private TestPrint() {}

    static PrintDocument document(String printerName, String codePage, int columns, String agentVersion) {
        return PrintDocument.builder()
                .text("StandPOS", Align.CENTER, true, Size.DOUBLE)
                .centered("Prova di stampa")
                .centered(ZonedDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")))
                .separator()
                .text("N. 42", Align.CENTER, true, Size.DOUBLE)
                .separator()
                .row("2 x Panino con salamella", "€ 9,00")
                .row("1 x Caffè", "€ 1,20")
                .row("1 x Patatine fritte con ketchup e maionese", "€ 3,50")
                .separator()
                .row("TOTALE", "€ 13,70", true, Size.DOUBLE_HEIGHT)
                .text("Contanti", Align.RIGHT, false, Size.NORMAL)
                .feed(1)
                .text("Accenti: à è é ì ò ù  À È É Ì Ò Ù")
                .text("Euro: € 1.234,56")
                .text("Grassetto", Align.LEFT, true, Size.NORMAL)
                .text("Doppia larghezza", Align.LEFT, false, Size.DOUBLE_WIDTH)
                .text("Doppia altezza", Align.LEFT, false, Size.DOUBLE_HEIGHT)
                .text("1234567890".repeat(8).substring(0, columns))
                .feed(1)
                .text("Scontrino di cortesia – documento non fiscale", Align.CENTER, false, Size.NORMAL)
                .feed(1)
                .text("Stampante: " + printerName)
                .text("Code page: " + codePage + ", colonne: " + columns)
                .text("Agente: " + agentVersion)
                .cut()
                .drawerKick()
                .build();
    }
}
