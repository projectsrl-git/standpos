package it.projectsrl.standpos.printagent.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * What {@code enrol} writes and {@code run} reads: the server's base URL, this device's id, venue and name, and the
 * device token. The token is a secret: {@link #toString()} hides it and nothing logs it.
 *
 * @param checkPrinterStatus refuse jobs when Windows reports the printer offline (default true); set
 *     {@code printer.check-status=false} if a driver misreports its status
 */
public record AgentConfig(
        URI serverUrl, String deviceId, String venueId, String deviceName, String token, boolean checkPrinterStatus) {

    private static final String SERVER = "server.url";
    private static final String DEVICE_ID = "device.id";
    private static final String VENUE_ID = "device.venue-id";
    private static final String NAME = "device.name";
    private static final String TOKEN = "device.token";
    private static final String CHECK_STATUS = "printer.check-status";

    public AgentConfig {
        Objects.requireNonNull(serverUrl, "serverUrl");
        Objects.requireNonNull(token, "token");
    }

    public static AgentConfig load(Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new IOException("Not enrolled: " + file + " is missing. Run 'enrol' first.");
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        String server = p.getProperty(SERVER);
        String token = p.getProperty(TOKEN);
        if (server == null || token == null || token.isBlank()) {
            throw new IOException(file + " has no server URL or token. Run 'enrol' again.");
        }
        return new AgentConfig(parseServerUrl(server), p.getProperty(DEVICE_ID), p.getProperty(VENUE_ID),
                p.getProperty(NAME), token.trim(),
                !"false".equalsIgnoreCase(p.getProperty(CHECK_STATUS, "true").trim()));
    }

    /** Writes the file atomically (a temporary file, then a rename), so a crash never leaves half a token. */
    public void save(Path file) throws IOException {
        Properties p = new Properties();
        p.setProperty(SERVER, serverUrl.toString());
        if (deviceId != null) {
            p.setProperty(DEVICE_ID, deviceId);
        }
        if (venueId != null) {
            p.setProperty(VENUE_ID, venueId);
        }
        if (deviceName != null) {
            p.setProperty(NAME, deviceName);
        }
        p.setProperty(TOKEN, token);
        p.setProperty(CHECK_STATUS, Boolean.toString(checkPrinterStatus));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.store(out, "StandPOS print agent. Contains the device token: keep this file private.");
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Validates the server's base URL. Plain {@code http} is accepted only for local development hosts, because the
     * token travels on every connection.
     */
    public static URI parseServerUrl(String text) {
        URI uri;
        try {
            uri = new URI(text.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid server URL: " + text);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || !(scheme.equals("https") || scheme.equals("http"))) {
            throw new IllegalArgumentException("The server URL must look like https://pos.example.org");
        }
        if (scheme.equals("http") && !isDevelopmentHost(host)) {
            throw new IllegalArgumentException("Use https:// for the server (http is accepted only for localhost)");
        }
        String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
        return URI.create(scheme + "://" + uri.getRawAuthority() + path);
    }

    static boolean isDevelopmentHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1")
                || h.endsWith(".localhost") || h.endsWith(".test");
    }

    /** {@code https://host/base} → {@code wss://host/base/ws}. */
    public URI webSocketUri(String endpoint) {
        String scheme = serverUrl.getScheme().equals("https") ? "wss" : "ws";
        return URI.create(scheme + "://" + serverUrl.getRawAuthority() + serverUrl.getRawPath() + endpoint);
    }

    public URI resolve(String path) {
        return URI.create(serverUrl + path);
    }

    @Override
    public String toString() {
        return "AgentConfig[serverUrl=" + serverUrl + ", deviceId=" + deviceId + ", deviceName=" + deviceName
                + ", token=***]";
    }
}
