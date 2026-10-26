package it.projectsrl.standpos.printagent;

import it.projectsrl.standpos.print.protocol.PlainTextRenderer;
import it.projectsrl.standpos.print.protocol.PrintDocument;
import it.projectsrl.standpos.print.protocol.PrintJob;
import it.projectsrl.standpos.printagent.config.AgentConfig;
import it.projectsrl.standpos.printagent.config.AgentHome;
import it.projectsrl.standpos.printagent.config.AgentLogging;
import it.projectsrl.standpos.printagent.enrol.EnrolmentClient;
import it.projectsrl.standpos.printagent.escpos.CodePage;
import it.projectsrl.standpos.printagent.printer.EscPosJobPrinter;
import it.projectsrl.standpos.printagent.printer.Printers;
import it.projectsrl.standpos.printagent.queue.JobQueue;
import it.projectsrl.standpos.printagent.service.AgentService;
import it.projectsrl.standpos.printagent.service.Backoff;
import it.projectsrl.standpos.printagent.service.PrintDispatcher;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Command line of the print agent. {@code run} is what the Windows service starts; the other commands are for
 * installation and troubleshooting. Exit codes: 0 success, 1 failure, 2 wrong usage.
 */
public final class Main {

    private static final String USAGE = """
            StandPOS print agent %s

            Usage: standpos-print-agent <command> [options]

              enrol --server <url> --code <code> [--name <name>]
                                    Enrol this PC with a code from the manager area (Dispositivi > Agente di stampa)
              run                   Connect to the server and print jobs until stopped (the Windows service)
              list-printers         List the printers Windows offers, by exact name
              test-print --printer <name> [--code-page CP858] [--columns 48]
                                    Print a local sample (euro sign, accents, sizes, cut, drawer kick)
              version               Print the agent version

            Every command accepts --home <dir> (default %%ProgramData%%\\StandPOS\\PrintAgent, or STANDPOS_AGENT_HOME).
            A printer name starting with file: writes the ESC/POS bytes to that file.
            """;

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out, System.err));
    }

    static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            out.print(USAGE.formatted(version()));
            return 2;
        }
        Map<String, String> options;
        try {
            options = options(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 2;
        }
        AgentHome home = AgentHome.resolve(options.get("home"), env);
        try {
            return switch (args[0]) {
                case "enrol" -> enrol(home, options, out, err);
                case "run" -> runService(home);
                case "list-printers" -> listPrinters(out);
                case "test-print" -> testPrint(options, out, err);
                case "version", "--version" -> {
                    out.println(version());
                    yield 0;
                }
                case "help", "--help", "-h" -> {
                    out.print(USAGE.formatted(version()));
                    yield 0;
                }
                default -> {
                    err.println("Unknown command: " + args[0]);
                    out.print(USAGE.formatted(version()));
                    yield 2;
                }
            };
        } catch (UsageException e) {
            err.println(e.getMessage());
            return 2;
        } catch (IOException | RuntimeException e) {
            err.println("Error: " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    private static int enrol(AgentHome home, Map<String, String> options, PrintStream out, PrintStream err)
            throws IOException, InterruptedException {
        URI server;
        try {
            server = AgentConfig.parseServerUrl(required(options, "server"));
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }
        String code = required(options, "code");
        String name = options.getOrDefault("name", defaultName());
        home.create();
        AgentLogging.configure(home.logsDir(), Level.INFO);
        if (java.nio.file.Files.exists(home.configFile())) {
            out.println("This PC was already enrolled: the new token replaces the old one.");
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        EnrolmentClient.Enrolment enrolment;
        try {
            enrolment = new EnrolmentClient(http).enrol(server, code, name);
        } catch (EnrolmentClient.EnrolmentException e) {
            err.println(e.getMessage());
            return 1;
        } catch (IOException e) {
            err.println("Cannot reach " + server + ": "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return 1;
        }
        EnrolmentClient.toConfig(server, enrolment, name).save(home.configFile());
        out.println("Enrolled as \"" + (enrolment.name() != null ? enrolment.name() : name) + "\" (device "
                + enrolment.deviceId() + ").");
        out.println("Configuration saved to " + home.configFile());
        out.println("Start the service (StandPOSPrintAgent) or run: standpos-print-agent run");
        return 0;
    }

    private static int runService(AgentHome home) throws IOException {
        home.create();
        AgentLogging.configure(home.logsDir(), Level.INFO);
        // sqlite-jdbc extracts its native library; keep it next to the data rather than in a shared temp folder
        System.setProperty("org.sqlite.tmpdir", home.dir().resolve("native").toString());
        java.nio.file.Files.createDirectories(home.dir().resolve("native"));
        AgentConfig config = AgentConfig.load(home.configFile());
        try (FileChannel lockFile = FileChannel.open(home.dir().resolve("agent.lock"), StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
                FileLock lock = lockFile.tryLock()) {
            if (lock == null) {
                throw new IOException("Another print agent is already running with " + home.dir());
            }
            try (JobQueue queue = JobQueue.open(home.queueFile(), Clock.systemUTC())) {
                AgentService service = new AgentService(config, queue,
                        new EscPosJobPrinter(config.checkPrinterStatus()), Printers::localPrinterNames, version(),
                        AgentService.Settings.DEFAULT, sleeper(), new Backoff(),
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(), Clock.systemUTC());
                Thread hook = Thread.ofPlatform().name("shutdown").unstarted(() -> {
                    service.stop();
                    try {
                        service.awaitTermination(Duration.ofSeconds(10));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                Runtime.getRuntime().addShutdownHook(hook);
                service.run();
            }
        }
        return 0;
    }

    private static PrintDispatcher.Sleeper sleeper() {
        return d -> Thread.sleep(d);
    }

    private static int listPrinters(PrintStream out) {
        AgentLogging.console(Level.WARNING);
        List<String> names = Printers.localPrinterNames();
        if (names.isEmpty()) {
            out.println("No printers found. Install the printer driver and check Settings > Printers & scanners.");
            return 1;
        }
        names.forEach(out::println);
        return 0;
    }

    private static int testPrint(Map<String, String> options, PrintStream out, PrintStream err) {
        AgentLogging.console(Level.INFO);
        String printer = required(options, "printer");
        CodePage codePage;
        int columns;
        try {
            codePage = CodePage.parse(options.get("code-page"));
            columns = Integer.parseInt(options.getOrDefault("columns", "48"));
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }
        if (columns < 16 || columns > 80) {
            throw new UsageException("--columns must be 16 to 80");
        }
        PrintDocument document = TestPrint.document(printer, codePage.name(), columns, version());
        PrintJob job = new PrintJob(UUID.randomUUID(), PrintJob.Kind.TEST,
                new PrintJob.PrinterTarget(printer, columns, codePage.name()), document);
        new PlainTextRenderer(columns).render(document).forEach(out::println);
        new EscPosJobPrinter(false).print(job);
        out.println();
        out.println("Sent to " + printer + " (" + codePage + ", " + columns + " columns).");
        return 0;
    }

    static Map<String, String> options(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + arg);
            }
            String name = arg.substring(2);
            String value;
            int eq = name.indexOf('=');
            if (eq >= 0) {
                value = name.substring(eq + 1);
                name = name.substring(0, eq);
            } else if (i + 1 < args.length) {
                value = args[++i];
            } else {
                throw new IllegalArgumentException("Missing value for --" + name);
            }
            options.put(name, value);
        }
        return options;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new UsageException("Missing --" + name);
        }
        return value;
    }

    private static String defaultName() {
        String computer = System.getenv("COMPUTERNAME");
        return "Agente " + (computer == null || computer.isBlank() ? "di stampa" : computer);
    }

    static String version() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    /** Wrong command-line usage (exit code 2). */
    private static final class UsageException extends RuntimeException {

        UsageException(String message) {
            super(message);
        }
    }
}
