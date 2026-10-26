package it.projectsrl.standpos.printing;

import static org.assertj.core.api.Assertions.assertThat;

import it.projectsrl.standpos.common.Money;
import it.projectsrl.standpos.print.protocol.PlainTextRenderer;
import it.projectsrl.standpos.print.protocol.PrintDocument;
import it.projectsrl.standpos.print.protocol.PrintElement;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReceiptBuilderTest {

    private static ReceiptBuilder.Receipt receipt(boolean kick, boolean reprint) {
        return new ReceiptBuilder.Receipt("Bar oratorio", "Parrocchia San Luigi", "Grazie!", ZoneId.of("Europe/Rome"),
                42, Instant.parse("2026-10-26T19:15:00Z"), "Cassa 1",
                List.of(new ReceiptBuilder.Line("Panino con salamella", 2, 450, 900, "senza cipolla"),
                        new ReceiptBuilder.Line("Acqua", 1, 100, 100, null)),
                1000, ReceiptBuilder.PaymentMethod.CASH, 2000L, 1000L, kick, reprint);
    }

    @Test
    void layoutHasTheLargeNumberLinesTotalPaymentAndTheFixedNotice() {
        PrintDocument document = ReceiptBuilder.build(receipt(true, false));
        List<String> lines = new PlainTextRenderer(48).render(document);

        assertThat(document.elements().getFirst()).isInstanceOf(PrintElement.DrawerKick.class);
        assertThat(document.elements().getLast()).isInstanceOf(PrintElement.Cut.class);
        assertThat(document.elements()).contains(
                new PrintElement.Text("N. 42", PrintElement.Align.CENTER, true, PrintElement.Size.DOUBLE));
        assertThat(lines).anyMatch(l -> l.startsWith("26/10/2026 20:15") && l.endsWith("Cassa 1"));
        assertThat(lines).anyMatch(l -> l.startsWith("Panino con salamella") && l.endsWith("9,00"));
        assertThat(lines).contains("  2 x 4,50", "  > senza cipolla");
        assertThat(lines).anyMatch(l -> l.startsWith("Resto") && l.endsWith("€ 10,00"));
        assertThat(lines).anyMatch(l -> l.contains(ReceiptBuilder.NON_FISCAL_NOTICE));
        assertThat(lines).noneMatch(l -> l.contains("RISTAMPA"));
        assertThat(lines).allSatisfy(l -> assertThat(l.length()).isLessThanOrEqualTo(48));
    }

    @Test
    void theNoticeIsFixedAndPresentEvenWithoutHeaderOrFooter() {
        assertThat(ReceiptBuilder.NON_FISCAL_NOTICE).isEqualTo("Scontrino di cortesia – documento non fiscale");
        ReceiptBuilder.Receipt bare = new ReceiptBuilder.Receipt("Sagra", null, null, ZoneId.of("Europe/Rome"), 1,
                Instant.now(), "Cassa", List.of(), 0, ReceiptBuilder.PaymentMethod.MANUAL_CARD, null, null, false,
                true);
        List<String> lines = new PlainTextRenderer(32).render(ReceiptBuilder.build(bare));
        assertThat(lines).anyMatch(l -> l.contains("RISTAMPA"));
        assertThat(String.join(" ", lines)).contains("Scontrino di cortesia");
        assertThat(lines).anyMatch(l -> l.startsWith("Carta"));
    }

    @Test
    void moneyIsFormattedTheItalianWay() {
        assertThat(Money.format(0)).isEqualTo("0,00");
        assertThat(Money.format(450)).isEqualTo("4,50");
        assertThat(Money.format(123456789)).isEqualTo("1.234.567,89");
        assertThat(Money.format(-5)).isEqualTo("-0,05");
        assertThat(Money.euro(900)).isEqualTo("€ 9,00");
    }
}
