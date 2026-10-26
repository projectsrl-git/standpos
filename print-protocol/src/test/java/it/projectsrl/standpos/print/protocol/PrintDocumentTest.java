package it.projectsrl.standpos.print.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PrintDocumentTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void jobsSurviveAJsonRoundTripWithTypedElements() {
        PrintDocument document = PrintDocument.builder()
                .text("Bar oratorio", PrintElement.Align.CENTER, true, PrintElement.Size.DOUBLE)
                .row("2 x Panino", "9,00 €")
                .separator()
                .feed(1)
                .cut()
                .drawerKick()
                .build();
        PrintJob job = new PrintJob(UUID.randomUUID(), PrintJob.Kind.RECEIPT,
                new PrintJob.PrinterTarget("POS80", 48, "CP858"), document);

        String text = json.writeValueAsString(job);
        assertThat(text).contains("\"type\":\"text\"", "\"type\":\"row\"", "\"type\":\"cut\"", "\"type\":\"drawer\"");
        PrintJob back = json.readValue(text, PrintJob.class);

        assertThat(back).isEqualTo(job);
        assertThat(back.document().version()).isEqualTo(PrintDocument.CURRENT_VERSION);
    }

    @Test
    void elementsAreCopiedDefensively() {
        PrintDocument.Builder builder = PrintDocument.builder().text("Ordine 1");
        PrintDocument document = builder.build();
        builder.text("changed");
        assertThat(document.elements()).hasSize(1);
    }

    @Test
    void plainTextLaysOutRowsAlignmentAndDoubleWidth() {
        PlainTextRenderer renderer = new PlainTextRenderer(20);
        List<String> lines = renderer.render(PrintDocument.builder()
                .text("N. 7", PrintElement.Align.CENTER, true, PrintElement.Size.DOUBLE)
                .row("Panino con salamella", "4,50")
                .separator()
                .cut()
                .build());

        assertThat(lines).containsExactly(
                "   N. 7",
                "Panino con",
                "salamella       4,50",
                "--------------------",
                "[cut]");
    }

    @Test
    void wrapSplitsLongWords() {
        assertThat(PlainTextRenderer.wrap("abcdefghij kl", 4)).containsExactly("abcd", "efgh", "ij", "kl");
        assertThat(PlainTextRenderer.wrap("", 4)).containsExactly("");
        assertThat(PlainTextRenderer.wrap("  > senza cipolla", 10)).containsExactly("  > senza", "  cipolla");
    }
}
