package it.projectsrl.standpos.printagent.printer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.JobName;
import javax.print.attribute.standard.PrinterIsAcceptingJobs;

/**
 * Sends raw bytes to a printer of the Windows spooler, found by its exact name (case-insensitive, as Windows compares
 * printer names). A name starting with {@code file:} appends the bytes to that file instead, for tests and for
 * checking the output without hardware; {@code text:} appends a readable plain-text preview of each job, for demos.
 *
 * <p>The bytes go through {@link DocPrintJob#print} directly rather than escpos-coffee's {@code PrinterOutputStream}:
 * that stream prints on a background thread and only logs failures, while the agent needs the error synchronously to
 * retry and to report it; and its name lookup falls back to a substring match, which could print on the wrong printer.
 */
public final class Printers {

    public static final String FILE_PREFIX = "file:";

    /** Writes a readable plain-text preview instead of ESC/POS: for demos without a receipt printer. */
    public static final String TEXT_PREFIX = "text:";

    private static final String JOB_NAME = "StandPOS";

    private Printers() {}

    /** Names of the printers the spooler offers, sorted; empty where there is no print system (CI). */
    public static List<String> localPrinterNames() {
        try {
            return Arrays.stream(PrintServiceLookup.lookupPrintServices(null, null))
                    .map(PrintService::getName)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Prints the bytes as one spooler job.
     *
     * @param checkStatus refuse the job when the spooler says the printer is not accepting jobs (offline, paused), so
     *     the failure is reported instead of the job waiting silently in the Windows queue
     * @throws PrintFailedException when the printer is missing, refuses the job or the spooler fails
     */
    public static void send(String printerName, byte[] data, boolean checkStatus) {
        if (printerName.startsWith(FILE_PREFIX)) {
            writeFile(Path.of(printerName.substring(FILE_PREFIX.length())), data);
            return;
        }
        PrintService service = find(printerName)
                .orElseThrow(() -> new PrintFailedException("printer not found: " + printerName));
        if (checkStatus
                && service.getAttribute(PrinterIsAcceptingJobs.class) == PrinterIsAcceptingJobs.NOT_ACCEPTING_JOBS) {
            throw new PrintFailedException("printer not accepting jobs (offline or paused): " + printerName);
        }
        DocPrintJob job = service.createPrintJob();
        PrintRequestAttributeSet attributes = new HashPrintRequestAttributeSet();
        attributes.add(new JobName(JOB_NAME, null));
        try {
            job.print(new SimpleDoc(data, DocFlavor.BYTE_ARRAY.AUTOSENSE, null), attributes);
        } catch (PrintException e) {
            throw new PrintFailedException("spooler error: " + e.getMessage(), e);
        }
    }

    public static boolean isText(String printerName) {
        return printerName.startsWith(TEXT_PREFIX);
    }

    /** Appends a text preview (UTF-8) to the file named after {@code text:}. */
    public static void sendText(String printerName, String text) {
        writeFile(Path.of(printerName.substring(TEXT_PREFIX.length())), text.getBytes(StandardCharsets.UTF_8));
    }

    static Optional<PrintService> find(String name) {
        return Arrays.stream(PrintServiceLookup.lookupPrintServices(null, null))
                .filter(s -> s.getName().equalsIgnoreCase(name.trim()))
                .findFirst();
    }

    private static void writeFile(Path path, byte[] data) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new PrintFailedException("cannot write " + path + ": " + e.getMessage(), e);
        }
    }
}
