package it.projectsrl.standpos.print.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays a document out as plain text lines, the way the printer does: used for previews, tests and by the agent's
 * ESC/POS renderer for wrapping and alignment. Double-width text takes two columns per character. Logos, cuts and
 * drawer kicks appear as markers in brackets.
 */
public final class PlainTextRenderer {

    private final int columns;

    public PlainTextRenderer(int columns) {
        this.columns = columns;
    }

    public List<String> render(PrintDocument document) {
        List<String> out = new ArrayList<>();
        for (PrintElement element : document.elements()) {
            switch (element) {
                case PrintElement.Text t -> out.addAll(text(t));
                case PrintElement.Row r -> out.addAll(row(r.left(), r.right(), width(r.size())));
                case PrintElement.Separator s -> out.add(separator());
                case PrintElement.Feed f -> {
                    for (int i = 0; i < f.lines(); i++) {
                        out.add("");
                    }
                }
                case PrintElement.Logo l -> out.add("[logo]");
                case PrintElement.Cut c -> out.add("[cut]");
                case PrintElement.DrawerKick d -> out.add("[drawer]");
            }
        }
        return out;
    }

    /** Characters per line at the given size. */
    public int width(PrintElement.Size size) {
        return size.doubleWidth() ? columns / 2 : columns;
    }

    /** The wrapped and aligned lines of a text element, padded on the left for centre and right alignment. */
    public List<String> text(PrintElement.Text text) {
        int width = width(text.size());
        return wrap(text.text(), width).stream().map(line -> align(line, width, text.align())).toList();
    }

    public String separator() {
        return "-".repeat(columns);
    }

    /** Lays out an item and an amount: the amount right-aligned on the last line of the (wrapped) item. */
    public static List<String> row(String left, String right, int width) {
        List<String> lines = new ArrayList<>();
        int room = Math.max(1, width - right.length() - 1);
        List<String> wrapped = wrap(left, room);
        for (int i = 0; i < wrapped.size() - 1; i++) {
            lines.add(wrapped.get(i));
        }
        String last = wrapped.getLast();
        lines.add(last + " ".repeat(Math.max(1, width - last.length() - right.length())) + right);
        return lines;
    }

    /** Word wrap; words longer than the width are split. Always returns at least one line. */
    public static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                while (word.length() > width) {
                    if (!line.isEmpty()) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    lines.add(word.substring(0, width));
                    word = word.substring(width);
                }
                if (line.isEmpty()) {
                    line.append(word);
                } else if (line.length() + 1 + word.length() <= width) {
                    line.append(' ').append(word);
                } else {
                    lines.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    static String align(String line, int width, PrintElement.Align align) {
        int pad = Math.max(0, width - line.length());
        return switch (align) {
            case LEFT -> line;
            case CENTER -> " ".repeat(pad / 2) + line;
            case RIGHT -> " ".repeat(pad) + line;
        };
    }
}
