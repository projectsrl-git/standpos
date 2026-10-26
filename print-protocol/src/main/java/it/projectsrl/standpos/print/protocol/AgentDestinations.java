package it.projectsrl.standpos.print.protocol;

/**
 * The contract between the server and a print agent. The agent enrols once on {@link #ENROL}, then opens a WebSocket
 * to {@link #ENDPOINT} with {@code Authorization: Bearer <device token>} on the HTTP handshake, sends STOMP
 * {@code CONNECT}, subscribes to {@link #JOBS} and sends {@link AgentHeartbeat} and {@link JobAck} frames as JSON.
 */
public final class AgentDestinations {

    /** WebSocket endpoint (STOMP over plain WebSocket), relative to the server's base URL. */
    public static final String ENDPOINT = "/ws";

    /** Jobs for this agent (a user destination: the server resolves it to the connected agent). */
    public static final String JOBS = "/user/queue/print-jobs";

    /** Acknowledgements of printed or failed jobs. */
    public static final String ACK = "/app/agent/ack";

    /** Liveness; the register shows "stampante offline" when heartbeats stop. */
    public static final String HEARTBEAT = "/app/agent/heartbeat";

    public static final int HEARTBEAT_SECONDS = 15;

    /**
     * Enrolment: {@code POST {"code": "...", "name": "..."}}, answered with {@code {"device": {...}, "token": "..."}}.
     * No cookie, no CSRF token; the token appears only in this response.
     */
    public static final String ENROL = "/api/v1/agents/enrol";

    private AgentDestinations() {}
}
