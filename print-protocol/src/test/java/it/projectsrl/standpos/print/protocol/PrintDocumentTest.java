package it.projectsrl.standpos.print.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrintDocumentTest {

    @Test
    void linesAreCopiedDefensively() {
        List<String> lines = new ArrayList<>(List.of("Ordine 1"));
        PrintDocument document = new PrintDocument(PrintDocument.CURRENT_VERSION, lines);
        lines.add("changed");
        assertThat(document.lines()).containsExactly("Ordine 1");
    }
}
