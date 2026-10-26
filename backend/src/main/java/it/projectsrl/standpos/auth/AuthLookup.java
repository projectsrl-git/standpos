package it.projectsrl.standpos.auth;

import it.projectsrl.standpos.device.DeviceKind;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Calls the {@code auth.*} SECURITY DEFINER functions: the only reads that happen before a tenant is known.
 * See docs/adr/0003-pre-tenant-lookups.md.
 */
@Component
public class AuthLookup {

    public record LoginRow(UUID userId, String passwordHash, String status, boolean platformAdmin, Instant lockedUntil) {}

    public record MembershipRow(UUID tenantId, UUID venueId, Role role) {}

    public record ClaimedCode(UUID codeId, UUID tenantId, UUID venueId, DeviceKind kind, boolean claimed) {}

    private final JdbcTemplate jdbc;

    public AuthLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LoginRow> findLogin(String email) {
        return jdbc.query("select * from auth.find_login(?)", (rs, i) -> new LoginRow(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("password_hash"),
                        rs.getString("status"),
                        rs.getBoolean("platform_admin"),
                        instant(rs, "locked_until")),
                email.trim()).stream().findFirst();
    }

    /** Returns the lock expiry when this failure locked the account, otherwise empty. */
    public Optional<Instant> recordLoginFailure(UUID userId, int maxAttempts, Duration lock) {
        Timestamp lockedUntil = jdbc.queryForObject(
                "select auth.record_login_failure(?, ?, make_interval(secs => ?))",
                Timestamp.class, userId, maxAttempts, (double) lock.toSeconds());
        return Optional.ofNullable(lockedUntil).map(Timestamp::toInstant).filter(t -> t.isAfter(Instant.now()));
    }

    public void recordLoginSuccess(UUID userId) {
        jdbc.query("select auth.record_login_success(?)", rs -> {}, userId);
    }

    public List<MembershipRow> userMemberships(UUID userId) {
        return jdbc.query("select * from auth.user_memberships(?)", (rs, i) -> new MembershipRow(
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("venue_id", UUID.class),
                Role.valueOf(rs.getString("role"))), userId);
    }

    public Optional<CurrentDevice> findDevice(byte[] tokenHash) {
        return jdbc.query("select * from auth.find_device(?)", (rs, i) -> new CurrentDevice(
                        rs.getObject("device_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("venue_id", UUID.class),
                        DeviceKind.valueOf(rs.getString("kind")),
                        rs.getString("name"),
                        instant(rs, "last_seen_at")),
                (Object) tokenHash).stream().findFirst();
    }

    /**
     * Claims a valid code if its kind is one of {@code kinds}. A valid code of another kind comes back with
     * {@code claimed = false} and stays usable; an unknown, used or expired code comes back empty.
     */
    public Optional<ClaimedCode> claimEnrolmentCode(String code, Collection<DeviceKind> kinds) {
        String[] kindNames = kinds.stream().map(DeviceKind::name).toArray(String[]::new);
        return jdbc.query(con -> {
            PreparedStatement statement = con.prepareStatement("select * from auth.claim_enrolment_code(?, ?)");
            statement.setString(1, code.trim());
            statement.setArray(2, con.createArrayOf("text", kindNames));
            return statement;
        }, (rs, i) -> new ClaimedCode(
                rs.getObject("code_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("venue_id", UUID.class),
                DeviceKind.valueOf(rs.getString("kind")),
                rs.getBoolean("claimed"))).stream().findFirst();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
