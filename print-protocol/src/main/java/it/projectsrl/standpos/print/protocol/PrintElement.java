package it.projectsrl.standpos.print.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Objects;

/**
 * One element of a {@link PrintDocument}. The JSON form carries a {@code type} property ({@code text}, {@code row},
 * {@code separator}, {@code feed}, {@code logo}, {@code cut}, {@code drawer}).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = PrintElement.Text.class, name = "text"),
    @JsonSubTypes.Type(value = PrintElement.Row.class, name = "row"),
    @JsonSubTypes.Type(value = PrintElement.Separator.class, name = "separator"),
    @JsonSubTypes.Type(value = PrintElement.Feed.class, name = "feed"),
    @JsonSubTypes.Type(value = PrintElement.Logo.class, name = "logo"),
    @JsonSubTypes.Type(value = PrintElement.Cut.class, name = "cut"),
    @JsonSubTypes.Type(value = PrintElement.DrawerKick.class, name = "drawer")
})
public sealed interface PrintElement {

    enum Align {
        LEFT,
        CENTER,
        RIGHT
    }

    /** Character size. {@code DOUBLE} is double width and double height. */
    enum Size {
        NORMAL,
        DOUBLE_HEIGHT,
        DOUBLE_WIDTH,
        DOUBLE;

        public boolean doubleWidth() {
            return this == DOUBLE_WIDTH || this == DOUBLE;
        }

        public boolean doubleHeight() {
            return this == DOUBLE_HEIGHT || this == DOUBLE;
        }
    }

    /** A line of text, wrapped by the renderer when longer than the printer's columns. */
    record Text(String text, Align align, boolean bold, Size size) implements PrintElement {

        public Text {
            text = text == null ? "" : text;
            align = align == null ? Align.LEFT : align;
            size = size == null ? Size.NORMAL : size;
        }
    }

    /** A left text and a right-aligned text on the same line (an item and its price). */
    record Row(String left, String right, boolean bold, Size size) implements PrintElement {

        public Row {
            left = left == null ? "" : left;
            right = right == null ? "" : right;
            size = size == null ? Size.NORMAL : size;
        }
    }

    /** A full-width line of dashes. */
    record Separator() implements PrintElement {}

    /** Blank lines. */
    record Feed(int lines) implements PrintElement {

        public Feed {
            if (lines < 1 || lines > 10) {
                throw new IllegalArgumentException("Feed lines must be 1 to 10");
            }
        }
    }

    /** A monochrome bitmap, base64-encoded PNG, centred. */
    record Logo(String png) implements PrintElement {

        public Logo {
            Objects.requireNonNull(png, "png");
        }
    }

    /** Feeds past the cutter and cuts the paper. */
    record Cut() implements PrintElement {}

    /** Sends the cash drawer kick pulse through the printer. */
    record DrawerKick() implements PrintElement {}
}
