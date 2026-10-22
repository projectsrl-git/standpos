package it.projectsrl.standpos.print.protocol;

import java.util.List;

/**
 * Structured document the server sends to a print agent. The layout lives on the server; the agent only renders it.
 *
 * <p>Phase 0 placeholder: the full model (alignment, styles, logo, cut, drawer kick) arrives in Phase 1.
 */
public record PrintDocument(int version, List<String> lines) {

    public static final int CURRENT_VERSION = 0;

    public PrintDocument {
        lines = List.copyOf(lines);
    }
}
