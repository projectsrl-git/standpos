package it.projectsrl.standpos.print.protocol;

import java.util.Objects;
import java.util.UUID;

/**
 * A job delivered by the server to a print agent on {@link AgentDestinations#JOBS}. The agent stores it in its local
 * queue, prints {@code document} on {@code printer} and answers with a {@link JobAck}. A job may be delivered more
 * than once (for example after a reconnection): the agent deduplicates by {@code jobId}.
 */
public record PrintJob(UUID jobId, Kind kind, PrinterTarget printer, PrintDocument document) {

    public enum Kind {
        RECEIPT,
        TICKET,
        REPORT,
        DRAWER,
        TEST
    }

    public PrintJob {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(printer, "printer");
        Objects.requireNonNull(document, "document");
    }

    /**
     * Where and how to print: the Windows printer name (spooler), the characters per line at normal size and the
     * ESC/POS code page (for example {@code CP858}, which has the euro sign).
     */
    public record PrinterTarget(String windowsName, int columns, String codePage) {

        public PrinterTarget {
            Objects.requireNonNull(windowsName, "windowsName");
            if (columns < 16 || columns > 80) {
                throw new IllegalArgumentException("columns must be 16 to 80");
            }
            codePage = codePage == null || codePage.isBlank() ? "CP858" : codePage;
        }
    }
}
