package it.projectsrl.standpos.printagent.queue;

import it.projectsrl.standpos.print.protocol.JobAck;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The agent's durable queue in a local SQLite file. A job is stored when it arrives, before printing, so it survives
 * a crash or a reboot; it is then marked printed or failed. Acknowledgements are stored in the same transaction as
 * the outcome and deleted only once written to the server, so an ack is never lost while the connection is down.
 * The job id is the primary key, which is what deduplicates redeliveries.
 *
 * <p>One connection, guarded by this object's monitor: the agent's volume is a few jobs a minute.
 */
public final class JobQueue implements AutoCloseable {

    /** Finished jobs are kept this long, to answer redeliveries, then pruned. */
    public static final Duration RETENTION = Duration.ofDays(7);

    private final Connection connection;
    private final Clock clock;

    private JobQueue(Connection connection, Clock clock) {
        this.connection = connection;
        this.clock = clock;
    }

    public static JobQueue open(Path file, Clock clock) {
        try {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=FULL");
                s.execute("PRAGMA busy_timeout=5000");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS job (
                          seq         INTEGER PRIMARY KEY AUTOINCREMENT,
                          job_id      TEXT    NOT NULL UNIQUE,
                          printer     TEXT    NOT NULL,
                          payload     TEXT    NOT NULL,
                          status      TEXT    NOT NULL,
                          attempts    INTEGER NOT NULL DEFAULT 0,
                          error       TEXT,
                          received_at INTEGER NOT NULL,
                          updated_at  INTEGER NOT NULL
                        )""");
                s.execute("CREATE INDEX IF NOT EXISTS job_status ON job (status, seq)");
                s.execute("""
                        CREATE TABLE IF NOT EXISTS pending_ack (
                          job_id     TEXT    PRIMARY KEY,
                          status     TEXT    NOT NULL,
                          attempts   INTEGER NOT NULL,
                          error      TEXT,
                          created_at INTEGER NOT NULL
                        )""");
            }
            return new JobQueue(connection, clock);
        } catch (SQLException e) {
            throw new QueueException("Cannot open the job queue " + file, e);
        }
    }

    /** Stores a newly received job; returns false (and changes nothing) if a job with that id is already known. */
    public synchronized boolean insertIfAbsent(UUID jobId, String printer, String payload) {
        long now = clock.millis();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT OR IGNORE INTO job (job_id, printer, payload, status, attempts, received_at, updated_at)
                VALUES (?, ?, ?, 'QUEUED', 0, ?, ?)""")) {
            ps.setString(1, jobId.toString());
            ps.setString(2, printer);
            ps.setString(3, payload);
            ps.setLong(4, now);
            ps.setLong(5, now);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new QueueException("Cannot store job " + jobId, e);
        }
    }

    public synchronized Optional<StoredJob> find(UUID jobId) {
        try (PreparedStatement ps = connection.prepareStatement(SELECT + " WHERE job_id = ?")) {
            ps.setString(1, jobId.toString());
            List<StoredJob> jobs = read(ps);
            return jobs.stream().findFirst();
        } catch (SQLException e) {
            throw new QueueException("Cannot read job " + jobId, e);
        }
    }

    /** Jobs still to print, in arrival order (resumed after a restart). */
    public synchronized List<StoredJob> queued() {
        try (PreparedStatement ps = connection.prepareStatement(SELECT + " WHERE status = 'QUEUED' ORDER BY seq")) {
            return read(ps);
        } catch (SQLException e) {
            throw new QueueException("Cannot list queued jobs", e);
        }
    }

    public synchronized int queuedCount() {
        try (Statement s = connection.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM job WHERE status = 'QUEUED'")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new QueueException("Cannot count queued jobs", e);
        }
    }

    /** Records a failed attempt of a job that will be retried. */
    public synchronized void recordAttempt(UUID jobId, int attempts, String error) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE job SET attempts = ?, error = ?, updated_at = ? WHERE job_id = ? AND status = 'QUEUED'")) {
            ps.setInt(1, attempts);
            ps.setString(2, error);
            ps.setLong(3, clock.millis());
            ps.setString(4, jobId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new QueueException("Cannot update job " + jobId, e);
        }
    }

    /** Marks a job printed or failed and stores its acknowledgement for delivery, atomically. */
    public synchronized JobAck finish(UUID jobId, JobAck.Status status, int attempts, String error) {
        JobAck ack = new JobAck(jobId, status, attempts, status == JobAck.Status.FAILED ? error : null);
        long now = clock.millis();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement job = connection.prepareStatement(
                            "UPDATE job SET status = ?, attempts = ?, error = ?, updated_at = ? WHERE job_id = ?");
                    PreparedStatement pending = connection.prepareStatement("""
                            INSERT OR REPLACE INTO pending_ack (job_id, status, attempts, error, created_at)
                            VALUES (?, ?, ?, ?, ?)""")) {
                job.setString(1, status.name());
                job.setInt(2, attempts);
                job.setString(3, ack.error());
                job.setLong(4, now);
                job.setString(5, jobId.toString());
                job.executeUpdate();
                pending.setString(1, jobId.toString());
                pending.setString(2, status.name());
                pending.setInt(3, attempts);
                pending.setString(4, ack.error());
                pending.setLong(5, now);
                pending.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
            return ack;
        } catch (SQLException e) {
            throw new QueueException("Cannot finish job " + jobId, e);
        }
    }

    /** Acknowledgements not yet written to the server, oldest first. */
    public synchronized List<JobAck> pendingAcks() {
        try (Statement s = connection.createStatement();
                ResultSet rs = s.executeQuery(
                        "SELECT job_id, status, attempts, error FROM pending_ack ORDER BY created_at, job_id")) {
            List<JobAck> acks = new ArrayList<>();
            while (rs.next()) {
                acks.add(new JobAck(UUID.fromString(rs.getString(1)), JobAck.Status.valueOf(rs.getString(2)),
                        rs.getInt(3), rs.getString(4)));
            }
            return acks;
        } catch (SQLException e) {
            throw new QueueException("Cannot list pending acknowledgements", e);
        }
    }

    public synchronized void ackDelivered(UUID jobId) {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM pending_ack WHERE job_id = ?")) {
            ps.setString(1, jobId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new QueueException("Cannot clear acknowledgement " + jobId, e);
        }
    }

    /** Deletes finished jobs older than {@link #RETENTION} whose acknowledgement has been delivered. */
    public synchronized int prune() {
        long cutoff = clock.millis() - RETENTION.toMillis();
        try (PreparedStatement ps = connection.prepareStatement("""
                DELETE FROM job WHERE status IN ('PRINTED', 'FAILED') AND updated_at < ?
                AND job_id NOT IN (SELECT job_id FROM pending_ack)""")) {
            ps.setLong(1, cutoff);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new QueueException("Cannot prune the job queue", e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new QueueException("Cannot close the job queue", e);
        }
    }

    private static final String SELECT =
            "SELECT job_id, printer, payload, status, attempts, error, received_at FROM job";

    private static List<StoredJob> read(PreparedStatement ps) throws SQLException {
        List<StoredJob> jobs = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                jobs.add(new StoredJob(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3),
                        StoredJob.Status.valueOf(rs.getString(4)), rs.getInt(5), rs.getString(6),
                        Instant.ofEpochMilli(rs.getLong(7))));
            }
        }
        return jobs;
    }

    /** The SQLite file cannot be used; unrecoverable for the current operation. */
    public static class QueueException extends RuntimeException {

        public QueueException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
