package it.projectsrl.standpos.printagent.escpos;

import com.github.anastaciocintra.escpos.EscPos.CharacterCodeTable;
import java.nio.charset.Charset;
import java.util.Locale;

/**
 * The ESC/POS character tables the agent supports, each paired with the Java charset that encodes text for it. The
 * table is selected on the printer with {@code ESC t n}; text must then be encoded in the same table, or accented
 * letters and the euro sign come out wrong. {@link #CP858} is the default because it is CP850 plus {@code €}.
 */
public enum CodePage {
    CP437(CharacterCodeTable.CP437_USA_Standard_Europe, "IBM437"),
    CP850(CharacterCodeTable.CP850_Multilingual, "IBM850"),
    CP858(CharacterCodeTable.CP858_Euro, "IBM00858"),
    CP1252(CharacterCodeTable.WPC1252, "windows-1252");

    public static final CodePage DEFAULT = CP858;

    private final CharacterCodeTable table;
    private final String javaCharset;

    CodePage(CharacterCodeTable table, String javaCharset) {
        this.table = table;
        this.javaCharset = javaCharset;
    }

    /** The {@code n} of {@code ESC t n}. */
    public int escPosTable() {
        return table.value;
    }

    public String javaCharsetName() {
        return javaCharset;
    }

    public Charset charset() {
        return Charset.forName(javaCharset);
    }

    /**
     * Parses the name the manager configures ({@code CP858}, {@code cp858}, {@code 858}, {@code WPC1252}). Blank means
     * the default.
     *
     * @throws IllegalArgumentException for an unsupported code page
     */
    public static CodePage parse(String name) {
        if (name == null || name.isBlank()) {
            return DEFAULT;
        }
        String n = name.trim().toUpperCase(Locale.ROOT).replace("-", "").replace("_", "");
        if (n.startsWith("WPC")) {
            n = "CP" + n.substring(3);
        } else if (n.startsWith("WINDOWS")) {
            n = "CP" + n.substring(7);
        } else if (n.startsWith("IBM")) {
            n = "CP" + n.substring(3).replaceFirst("^0+", "");
        } else if (!n.startsWith("CP")) {
            n = "CP" + n;
        }
        try {
            return valueOf(n);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported code page: " + name + " (use CP858, CP850, CP437 or CP1252)");
        }
    }
}
