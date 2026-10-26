package it.projectsrl.standpos.printagent.config;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * java.util.logging to the console and to {@code logs/agent-N.log} (5 files of 5 MB, rotating), one line per record
 * in UTC. Plain text rather than JSON: the reader is whoever opens the file on the register PC. The code never logs
 * the device token or personal data.
 */
public final class AgentLogging {

    private static final int FILE_LIMIT = 5 * 1024 * 1024;
    private static final int FILE_COUNT = 5;

    private AgentLogging() {}

    /** Console only (before the data directory is known, or when it is not writable). */
    public static void console(Level level) {
        Logger root = resetRoot(level);
        root.addHandler(consoleHandler(level));
    }

    /** Console plus rotating file; falls back to console only, with a warning, if the directory is not writable. */
    public static void configure(Path logsDir, Level level) {
        Logger root = resetRoot(level);
        root.addHandler(consoleHandler(level));
        try {
            Files.createDirectories(logsDir);
            FileHandler file = new FileHandler(logsDir.resolve("agent-%g.log").toString(), FILE_LIMIT, FILE_COUNT,
                    true);
            file.setEncoding(StandardCharsets.UTF_8.name());
            file.setFormatter(new LineFormatter());
            file.setLevel(level);
            root.addHandler(file);
        } catch (IOException | SecurityException e) {
            root.warning("Logging to the console only: cannot write to " + logsDir + " (" + e.getMessage() + ")");
        }
        // Quieten the JDK's own HTTP client and AWT loggers
        Logger.getLogger("jdk.internal.httpclient").setLevel(Level.WARNING);
        Logger.getLogger("sun.awt").setLevel(Level.WARNING);
    }

    private static Logger resetRoot(Level level) {
        LogManager.getLogManager().reset();
        Logger root = Logger.getLogger("");
        root.setLevel(level);
        return root;
    }

    private static Handler consoleHandler(Level level) {
        ConsoleHandler console = new ConsoleHandler();
        console.setFormatter(new LineFormatter());
        console.setLevel(level);
        try {
            console.setEncoding(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            // Keep the platform encoding
        }
        return console;
    }

    /** {@code 2026-10-26T08:00:00.123Z INFO  [thread] logger - message}, then the stack trace if any. */
    static final class LineFormatter extends Formatter {

        @Override
        public String format(LogRecord record) {
            StringBuilder out = new StringBuilder(160);
            out.append(Instant.ofEpochMilli(record.getMillis()))
                    .append(' ')
                    .append(String.format("%-7s", record.getLevel().getName()))
                    .append(" [")
                    .append(Thread.currentThread().getName())
                    .append("] ")
                    .append(shortName(record.getLoggerName()))
                    .append(" - ")
                    .append(formatMessage(record))
                    .append(System.lineSeparator());
            if (record.getThrown() != null) {
                StringWriter trace = new StringWriter();
                record.getThrown().printStackTrace(new PrintWriter(trace));
                out.append(trace);
            }
            return out.toString();
        }

        private static String shortName(String name) {
            if (name == null) {
                return "";
            }
            int dot = name.lastIndexOf('.');
            return dot < 0 ? name : name.substring(dot + 1);
        }
    }
}
