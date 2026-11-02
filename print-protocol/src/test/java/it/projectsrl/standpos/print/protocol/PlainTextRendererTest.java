package it.projectsrl.standpos.print.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import it.projectsrl.standpos.print.protocol.PlainTextRenderer.Line;
import it.projectsrl.standpos.print.protocol.PrintElement.Align;
import it.projectsrl.standpos.print.protocol.PrintElement.Size;
import org.junit.jupiter.api.Test;

class PlainTextRendererTest {

    @Test
    void styledLinesKeepBoldAndSizeAndHalveTheColumnsOfDoubleWidthText() {
        PrintDocument document = PrintDocument.builder()
                .text("N. 12", Align.CENTER, true, Size.DOUBLE)
                .row("TOTALE", "9,00 €", true, Size.DOUBLE_HEIGHT)
                .separator()
                .feed(1)
                .cut()
                .build();

        assertThat(new PlainTextRenderer(20).lines(document)).containsExactly(
                new Line("  N. 12", true, Size.DOUBLE),
                new Line("TOTALE        9,00 €", true, Size.DOUBLE_HEIGHT),
                new Line("-".repeat(20), false, Size.NORMAL),
                new Line("", false, Size.NORMAL),
                new Line("[cut]", false, Size.NORMAL));
        assertThat(new PlainTextRenderer(20).render(document)).first().isEqualTo("  N. 12");
    }
}
