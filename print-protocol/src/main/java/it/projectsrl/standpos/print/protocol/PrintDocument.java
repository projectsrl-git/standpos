package it.projectsrl.standpos.print.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured document the server sends to a print agent. The layout lives on the server; the agent only renders it
 * on its printer (ESC/POS), applying the printer's columns and code page.
 *
 * <p>{@code version} lets an agent refuse documents newer than it understands.
 */
public record PrintDocument(int version, List<PrintElement> elements) {

    public static final int CURRENT_VERSION = 1;

    public PrintDocument {
        elements = List.copyOf(elements);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent construction of a document at {@link #CURRENT_VERSION}. */
    public static final class Builder {

        private final List<PrintElement> elements = new ArrayList<>();

        private Builder() {}

        public Builder text(String text) {
            return add(new PrintElement.Text(text, PrintElement.Align.LEFT, false, PrintElement.Size.NORMAL));
        }

        public Builder text(String text, PrintElement.Align align, boolean bold, PrintElement.Size size) {
            return add(new PrintElement.Text(text, align, bold, size));
        }

        public Builder centered(String text) {
            return add(new PrintElement.Text(text, PrintElement.Align.CENTER, false, PrintElement.Size.NORMAL));
        }

        public Builder row(String left, String right) {
            return add(new PrintElement.Row(left, right, false, PrintElement.Size.NORMAL));
        }

        public Builder row(String left, String right, boolean bold, PrintElement.Size size) {
            return add(new PrintElement.Row(left, right, bold, size));
        }

        public Builder separator() {
            return add(new PrintElement.Separator());
        }

        public Builder feed(int lines) {
            return add(new PrintElement.Feed(lines));
        }

        public Builder logo(String pngBase64) {
            return add(new PrintElement.Logo(pngBase64));
        }

        public Builder cut() {
            return add(new PrintElement.Cut());
        }

        public Builder drawerKick() {
            return add(new PrintElement.DrawerKick());
        }

        public Builder add(PrintElement element) {
            elements.add(element);
            return this;
        }

        public PrintDocument build() {
            return new PrintDocument(CURRENT_VERSION, elements);
        }
    }
}
