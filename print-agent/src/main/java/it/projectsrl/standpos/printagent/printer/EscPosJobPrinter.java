package it.projectsrl.standpos.printagent.printer;

import it.projectsrl.standpos.print.protocol.PlainTextRenderer;
import it.projectsrl.standpos.print.protocol.PrintDocument;
import it.projectsrl.standpos.print.protocol.PrintElement;
import it.projectsrl.standpos.print.protocol.PrintJob;
import it.projectsrl.standpos.printagent.escpos.CodePage;
import it.projectsrl.standpos.printagent.escpos.EscPosRenderer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Renders a job for its printer's columns and code page, then spools the bytes. A {@code text:} printer gets the
 * same layout as readable text instead (see {@link #preview}), so a demo shows the receipt without a printer.
 */
public final class EscPosJobPrinter implements JobPrinter {

    private final boolean checkStatus;
    private final Clock clock;

    /** @param checkStatus see {@link Printers#send(String, byte[], boolean)} */
    public EscPosJobPrinter(boolean checkStatus) {
        this(checkStatus, Clock.systemDefaultZone());
    }

    EscPosJobPrinter(boolean checkStatus, Clock clock) {
        this.checkStatus = checkStatus;
        this.clock = clock;
    }

    @Override
    public void print(PrintJob job) {
        PrintJob.PrinterTarget target = job.printer();
        if (Printers.isText(target.windowsName())) {
            Printers.sendText(target.windowsName(), preview(job, clock.instant()));
            return;
        }
        CodePage codePage;
        try {
            codePage = CodePage.parse(target.codePage());
        } catch (IllegalArgumentException e) {
            throw new PrintFailedException(e.getMessage(), e);
        }
        byte[] bytes = new EscPosRenderer(target.columns(), codePage).render(job.document());
        Printers.send(target.windowsName(), bytes, checkStatus);
    }

    /**
     * The job as the paper would show it, laid out by {@link PlainTextRenderer} at the printer's columns: a header
     * line with the kind, the job id and the time, then the lines, with {@code [cut]} and {@code [drawer]} markers.
     * Double-width text is spread out ({@code N. 7} becomes {@code N .   7}) so it keeps its place on the line.
     * Unicode is kept as is (no code page), so the € sign and accented letters read correctly.
     */
    static String preview(PrintJob job, Instant printedAt) {
        int columns = job.printer().columns();
        PlainTextRenderer renderer = new PlainTextRenderer(columns);
        StringBuilder text = new StringBuilder();
        String header = "== " + job.kind() + " " + job.jobId() + " " + printedAt + " ";
        text.append(header).append("=".repeat(Math.max(0, columns - header.length()))).append(System.lineSeparator());
        for (PrintElement element : job.document().elements()) {
            boolean wide = switch (element) {
                case PrintElement.Text t -> t.size().doubleWidth();
                case PrintElement.Row r -> r.size().doubleWidth();
                default -> false;
            };
            for (String line : renderer.render(new PrintDocument(PrintDocument.CURRENT_VERSION, List.of(element)))) {
                text.append(wide ? spread(line) : line).append(System.lineSeparator());
            }
        }
        return text.append(System.lineSeparator()).toString();
    }

    /** A double-width line as it looks on paper: each character takes two columns. */
    private static String spread(String line) {
        StringBuilder wide = new StringBuilder(line.length() * 2);
        line.codePoints().forEach(c -> wide.appendCodePoint(c).append(' '));
        return wide.toString().stripTrailing();
    }
}
