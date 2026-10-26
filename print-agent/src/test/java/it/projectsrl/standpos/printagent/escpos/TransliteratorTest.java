package it.projectsrl.standpos.printagent.escpos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TransliteratorTest {

    private final Transliterator cp858 = new Transliterator(CodePage.CP858.charset());

    @Test
    void keepsWhatTheCodePageHas() {
        assertThat(cp858.apply("Caffè € 1,20 à é ì ò ù ç ñ ü «»")).isEqualTo("Caffè € 1,20 à é ì ò ù ç ñ ü «»");
    }

    @Test
    void replacesTypographicPunctuation() {
        assertThat(cp858.apply("a – b — c ‘d’ “e” f…")).isEqualTo("a - b - c 'd' \"e\" f...");
    }

    @Test
    void dropsAccentsTheCodePageLacksAndMarksTheRest() {
        assertThat(cp858.apply("Łódź ő ș")).isEqualTo("?ódz o s");
        assertThat(cp858.apply("pizza 🍕")).isEqualTo("pizza ?");
    }

    @Test
    void removesControlCharactersButKeepsLineBreaks() {
        assertThat(cp858.apply("a\u001Bb\tc\r\nd\u0007")).isEqualTo("ab c \nd");
    }

    @Test
    void spellsTheEuroWhereTheTableHasNone() {
        assertThat(new Transliterator(CodePage.CP850.charset()).apply("€ 5")).isEqualTo("EUR 5");
    }

    @Test
    void parsesCodePageNames() {
        assertThat(CodePage.parse("CP858")).isEqualTo(CodePage.CP858);
        assertThat(CodePage.parse("cp-858")).isEqualTo(CodePage.CP858);
        assertThat(CodePage.parse("IBM00858")).isEqualTo(CodePage.CP858);
        assertThat(CodePage.parse("850")).isEqualTo(CodePage.CP850);
        assertThat(CodePage.parse("WPC1252")).isEqualTo(CodePage.CP1252);
        assertThat(CodePage.parse("windows-1252")).isEqualTo(CodePage.CP1252);
        assertThat(CodePage.parse(" ")).isEqualTo(CodePage.CP858);
        assertThatThrownBy(() -> CodePage.parse("CP866")).hasMessageContaining("Unsupported code page");
    }
}
