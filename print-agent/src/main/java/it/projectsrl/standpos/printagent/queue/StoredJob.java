package it.projectsrl.standpos.printagent.queue;

import it.projectsrl.standpos.print.protocol.JobAck;
import java.time.Instant;
import java.util.UUID;

/** A job as the local queue keeps it: the raw JSON received from the server plus its local printing state. */
public record StoredJob(
        UUID jobId, String printer, String payload, Status status, int attempts, String error, Instant receivedAt) {

    public enum Status {
        /** Received, not yet printed (possibly between retries). */
        QUEUED,
        PRINTED,
        FAILED
    }

    public boolean isFinished() {
        return status != Status.QUEUED;
    }

    /** The acknowledgement matching a finished job, to answer a redelivery. */
    public JobAck ack() {
        if (!isFinished()) {
            throw new IllegalStateException("Job " + jobId + " is not finished");
        }
        return new JobAck(jobId, status == Status.PRINTED ? JobAck.Status.PRINTED : JobAck.Status.FAILED, attempts,
                status == Status.FAILED ? error : null);
    }
}
