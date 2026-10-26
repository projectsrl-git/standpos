package it.projectsrl.standpos.printagent.escpos;

import com.github.anastaciocintra.escpos.EscPos;
import com.github.anastaciocintra.escpos.EscPosConst;
import com.github.anastaciocintra.escpos.Style;
import com.github.anastaciocintra.escpos.image.BitonalThreshold;
import com.github.anastaciocintra.escpos.image.CoffeeImageImpl;
import com.github.anastaciocintra.escpos.image.EscPosImage;
import com.github.anastaciocintra.escpos.image.RasterBitImageWrapper;
import it.projectsrl.standpos.print.protocol.PlainTextRenderer;
import it.projectsrl.standpos.print.protocol.PrintDocument;
import it.projectsrl.standpos.print.protocol.PrintElement;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Turns a {@link PrintDocument} into ESC/POS bytes with escpos-coffee. Layout (wrapping, alignment, rows) comes from
 * {@link PlainTextRenderer}, so the printed receipt matches the server's preview; alignment is done with spaces, which
 * keeps it identical across printers. Text is transliterated for the code page first, so the layout counts the
 * characters that are actually printed. The whole job is rendered into memory and spooled as one Windows print job.
 */
public final class EscPosRenderer {

    private static final System.Logger log = System.getLogger(EscPosRenderer.class.getName());

    /** Lines fed before a cut so the last printed line clears the cutter. */
    static final int CUT_FEED_LINES = 5;

    /** Drawer kick on pin 2: {@code ESC p 48 25 250} (m = 48 is the same pin as 0; 50 ms on, 500 ms off). */
    static final int DRAWER_ON = 25;

    static final int DRAWER_OFF = 250;

    /** Dots per character at normal size (font A, 12x24). */
    private static final int DOTS_PER_COLUMN = 12;

    /** Printable width of an 80 mm head. */
    private static final int MAX_DOTS = 576;

    private final int columns;
    private final CodePage codePage;
    private final Transliterator transliterator;
    private final PlainTextRenderer layout;

    public EscPosRenderer(int columns, CodePage codePage) {
        this.columns = columns;
        this.codePage = codePage;
        this.transliterator = new Transliterator(codePage.charset());
        this.layout = new PlainTextRenderer(columns);
    }

    public byte[] render(PrintDocument document) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        try (EscPos escpos = new EscPos(out)) {
            escpos.initializePrinter();
            escpos.setPrinterCharacterTable(codePage.escPosTable());
            escpos.setCharsetName(codePage.javaCharsetName());
            for (PrintElement element : document.elements()) {
                render(escpos, element);
            }
            escpos.flush();
        } catch (IOException e) {
            // Only a ByteArrayOutputStream is involved
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private void render(EscPos escpos, PrintElement element) throws IOException {
        switch (element) {
            case PrintElement.Text t -> {
                var clean = new PrintElement.Text(transliterator.apply(t.text()), t.align(), t.bold(), t.size());
                writeLines(escpos, style(t.bold(), t.size()), layout.text(clean));
            }
            case PrintElement.Row r -> writeLines(escpos, style(r.bold(), r.size()), PlainTextRenderer.row(
                    transliterator.apply(r.left()).replace('\n', ' '),
                    transliterator.apply(r.right()).replace('\n', ' '),
                    layout.width(r.size())));
            case PrintElement.Separator s -> escpos.writeLF(new Style(), layout.separator());
            case PrintElement.Feed f -> escpos.feed(f.lines());
            case PrintElement.Logo l -> logo(escpos, l);
            case PrintElement.Cut c -> {
                escpos.feed(CUT_FEED_LINES);
                escpos.cut(EscPos.CutMode.FULL);
            }
            case PrintElement.DrawerKick d -> escpos.pulsePin(EscPos.PinConnector.Pin_2, DRAWER_ON, DRAWER_OFF);
        }
    }

    private static void writeLines(EscPos escpos, Style style, List<String> lines) throws IOException {
        for (String line : lines) {
            escpos.writeLF(style, line);
        }
    }

    static Style style(boolean bold, PrintElement.Size size) {
        Style.FontSize width = size.doubleWidth() ? Style.FontSize._2 : Style.FontSize._1;
        Style.FontSize height = size.doubleHeight() ? Style.FontSize._2 : Style.FontSize._1;
        return new Style().setBold(bold).setFontSize(width, height);
    }

    /** A broken logo is skipped with a warning: it must not stop the receipt from printing. */
    private void logo(EscPos escpos, PrintElement.Logo logo) throws IOException {
        BufferedImage image;
        try {
            byte[] png = Base64.getMimeDecoder().decode(logo.png());
            image = ImageIO.read(new ByteArrayInputStream(png));
        } catch (IllegalArgumentException | IOException e) {
            image = null;
        }
        if (image == null) {
            log.log(System.Logger.Level.WARNING, "Logo skipped: not a readable PNG");
            return;
        }
        RasterBitImageWrapper wrapper = new RasterBitImageWrapper();
        wrapper.setJustification(EscPosConst.Justification.Center);
        escpos.write(wrapper, new EscPosImage(new CoffeeImageImpl(flatten(image, maxDots())), new BitonalThreshold()));
    }

    private int maxDots() {
        return Math.min(MAX_DOTS, columns * DOTS_PER_COLUMN);
    }

    /** Draws the image on white (transparent PNGs would otherwise print black) and scales it down to the head width. */
    static BufferedImage flatten(BufferedImage source, int maxWidth) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width > maxWidth) {
            height = Math.max(1, Math.round(height * (maxWidth / (float) width)));
            width = maxWidth;
        }
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }
}
