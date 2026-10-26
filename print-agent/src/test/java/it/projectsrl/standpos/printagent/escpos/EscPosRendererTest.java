package it.projectsrl.standpos.printagent.escpos;

import static org.assertj.core.api.Assertions.assertThat;

import it.projectsrl.standpos.print.protocol.PrintDocument;
import it.projectsrl.standpos.print.protocol.PrintElement.Align;
import it.projectsrl.standpos.print.protocol.PrintElement.Size;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class EscPosRendererTest {

    private static final int ESC = 0x1B;
    private static final int GS = 0x1D;

    private final EscPosRenderer cp858 = new EscPosRenderer(48, CodePage.CP858);

    @Test
    void initialisesThePrinterAndSelectsTheCodePage() {
        byte[] bytes = cp858.render(PrintDocument.builder().text("x").build());

        assertThat(startsWith(bytes, ESC, '@', ESC, 't', 19)).isTrue();
    }

    @Test
    void encodesTheEuroSignAndAccentsInCp858() {
        byte[] bytes = cp858.render(PrintDocument.builder().text("€ àèéìòù").build());

        assertThat(indexOf(bytes, 0xD5, ' ', 0x85, 0x8A, 0x82, 0x8D, 0x95, 0x97)).isPositive();
    }

    @Test
    void transliteratesTheEnDashOfTheFixedReceiptLine() {
        byte[] bytes = cp858.render(PrintDocument.builder()
                .centered("Scontrino di cortesia – documento non fiscale")
                .build());

        assertThat(text(bytes, "IBM00858")).contains("Scontrino di cortesia - documento non fiscale");
        assertThat(text(bytes, "IBM00858")).doesNotContain("?");
    }

    @Test
    void appliesBoldAndCharacterSizes() {
        byte[] bold = cp858.render(PrintDocument.builder().text("A", Align.LEFT, true, Size.NORMAL).build());
        byte[] big = cp858.render(PrintDocument.builder().text("A", Align.LEFT, false, Size.DOUBLE).build());
        byte[] wide = cp858.render(PrintDocument.builder().text("A", Align.LEFT, false, Size.DOUBLE_WIDTH).build());
        byte[] tall = cp858.render(PrintDocument.builder().text("A", Align.LEFT, false, Size.DOUBLE_HEIGHT).build());

        assertThat(indexOf(bold, ESC, 'E', 1)).isPositive();
        assertThat(indexOf(big, GS, '!', 0x11)).isPositive();
        assertThat(indexOf(wide, GS, '!', 0x10)).isPositive();
        assertThat(indexOf(tall, GS, '!', 0x01)).isPositive();
        assertThat(indexOf(big, ESC, 'E', 0)).isPositive();
    }

    @Test
    void laysOutRowsAtHalfTheColumnsInDoubleWidth() {
        byte[] normal = new EscPosRenderer(32, CodePage.CP858)
                .render(PrintDocument.builder().row("TOTALE", "13,70").build());
        byte[] wide = new EscPosRenderer(32, CodePage.CP858)
                .render(PrintDocument.builder().row("TOTALE", "13,70", true, Size.DOUBLE).build());

        assertThat(text(normal, "IBM00858")).contains("TOTALE" + " ".repeat(32 - 11) + "13,70\n");
        assertThat(text(wide, "IBM00858")).contains("TOTALE" + " ".repeat(16 - 11) + "13,70\n");
    }

    @Test
    void centresTextWithSpacesFromTheSharedLayout() {
        byte[] bytes = new EscPosRenderer(20, CodePage.CP858).render(PrintDocument.builder().centered("N. 7").build());

        assertThat(text(bytes, "IBM00858")).contains("        N. 7\n");
    }

    @Test
    void separatorSpansTheFullWidth() {
        byte[] bytes = cp858.render(PrintDocument.builder().separator().build());

        assertThat(text(bytes, "IBM00858")).contains("-".repeat(48) + "\n");
    }

    @Test
    void feedsBeforeCuttingAndKicksTheDrawerOnPin2() {
        byte[] bytes = cp858.render(PrintDocument.builder().text("x").feed(2).cut().drawerKick().build());

        // escpos-coffee feeds with line feeds: 1 after "x", 2 fed, 5 right before the cut (GS V 48, full cut)
        int lf = 0x0A;
        assertThat(IntStream.range(0, bytes.length).filter(i -> bytes[i] == lf).count()).isEqualTo(8);
        int cut = indexOf(bytes, lf, lf, lf, lf, lf, GS, 'V', 48);
        int kick = indexOf(bytes, ESC, 'p', '0', 25, 250); // m = 48 ('0') selects pin 2, like m = 0
        assertThat(cut).isPositive();
        assertThat(kick).isGreaterThan(cut);
    }

    @Test
    void otherCodePagesSelectTheirTableAndSpellTheEuroWhenMissing() {
        byte[] cp437 = new EscPosRenderer(48, CodePage.CP437).render(PrintDocument.builder().text("€ 5").build());
        byte[] cp1252 = new EscPosRenderer(48, CodePage.CP1252).render(PrintDocument.builder().text("€ 5").build());

        assertThat(startsWith(cp437, ESC, '@', ESC, 't', 0)).isTrue();
        assertThat(text(cp437, "IBM437")).contains("EUR 5");
        assertThat(startsWith(cp1252, ESC, '@', ESC, 't', 16)).isTrue();
        assertThat(indexOf(cp1252, 0x80, ' ', '5')).isPositive();
    }

    @Test
    void textCannotInjectPrinterCommands() {
        byte[] bytes = cp858.render(PrintDocument.builder().text("Panino\u001Bp\u0000\u0019ú").build());

        assertThat(indexOf(bytes, 'P', 'a', 'n', 'i', 'n', 'o', 'p')).isPositive();
        assertThat(indexOf(bytes, ESC, 'p')).isNegative();
    }

    @Test
    void printsALogoAsARasterImageAndSkipsABrokenOne() throws IOException {
        byte[] logo = cp858.render(PrintDocument.builder().logo(png(40, 20)).build());
        byte[] broken = cp858.render(PrintDocument.builder().logo("not a png").text("after").build());

        assertThat(indexOf(logo, GS, 'v', '0')).isPositive();
        assertThat(indexOf(logo, ESC, 'a', '1')).isPositive();
        assertThat(indexOf(broken, GS, 'v', '0')).isNegative();
        assertThat(text(broken, "IBM00858")).contains("after");
    }

    @Test
    void scalesWideLogosToTheHead() {
        BufferedImage wide = new BufferedImage(1000, 100, BufferedImage.TYPE_INT_ARGB);

        BufferedImage flat = EscPosRenderer.flatten(wide, 576);

        assertThat(flat.getWidth()).isEqualTo(576);
        assertThat(flat.getHeight()).isEqualTo(58);
        assertThat(flat.getRGB(0, 0)).isEqualTo(Color.WHITE.getRGB()); // transparent becomes white
    }

    private static String png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        g.fillRect(5, 5, 10, 10);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static String text(byte[] bytes, String charset) {
        return new String(bytes, Charset.forName(charset));
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        return indexOf(bytes, prefix) == 0;
    }

    static int indexOf(byte[] bytes, int... pattern) {
        outer:
        for (int i = 0; i <= bytes.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if ((bytes[i + j] & 0xFF) != (pattern[j] & 0xFF)) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
