package it.projectsrl.standpos.printagent.escpos;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.text.Normalizer;
import java.util.Map;

/**
 * Makes text printable in a code page before it is laid out. Typographic punctuation the table lacks becomes its ASCII
 * look-alike (the fixed receipt line has an en dash, which CP858 does not have), accented letters the table lacks lose
 * the accent, the euro sign becomes {@code EUR} where the table has none, and anything else unmappable becomes
 * {@code ?}. Control characters are removed so that text from the catalogue can never inject printer commands; line
 * breaks are kept for the layout to split on.
 */
public final class Transliterator {

    private static final Map<Integer, String> FALLBACKS = Map.ofEntries(
            Map.entry(0x2013, "-"), // en dash
            Map.entry(0x2014, "-"), // em dash
            Map.entry(0x2212, "-"), // minus sign
            Map.entry(0x2010, "-"), // hyphen
            Map.entry(0x2011, "-"), // non-breaking hyphen
            Map.entry(0x2018, "'"),
            Map.entry(0x2019, "'"),
            Map.entry(0x201A, "'"),
            Map.entry(0x2032, "'"),
            Map.entry(0x201C, "\""),
            Map.entry(0x201D, "\""),
            Map.entry(0x201E, "\""),
            Map.entry(0x00AB, "\""),
            Map.entry(0x00BB, "\""),
            Map.entry(0x2026, "..."),
            Map.entry(0x2022, "*"),
            Map.entry(0x00A0, " "),
            Map.entry(0x202F, " "),
            Map.entry(0x2009, " "),
            Map.entry(0x20AC, "EUR"));

    private final CharsetEncoder encoder;

    public Transliterator(Charset charset) {
        this.encoder = charset.newEncoder();
    }

    public String apply(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> out.append(map(cp)));
        return out.toString();
    }

    private String map(int cp) {
        if (cp == '\n') {
            return "\n";
        }
        if (cp == '\t' || cp == '\r') {
            return " ";
        }
        if (Character.isISOControl(cp)) {
            return "";
        }
        String s = Character.toString(cp);
        if (canEncode(s)) {
            return s;
        }
        String fallback = FALLBACKS.get(cp);
        if (fallback != null && canEncode(fallback)) {
            return fallback;
        }
        String stripped = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        if (!stripped.isEmpty() && !stripped.equals(s) && canEncode(stripped)) {
            return stripped;
        }
        return "?";
    }

    private boolean canEncode(String s) {
        return encoder.canEncode(s);
    }
}
