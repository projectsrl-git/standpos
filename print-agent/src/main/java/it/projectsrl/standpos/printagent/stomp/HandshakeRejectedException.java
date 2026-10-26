package it.projectsrl.standpos.printagent.stomp;

/**
 * The server answered the WebSocket handshake with an HTTP error. 401 and 403 mean the device token is invalid or the
 * device was revoked: reconnecting quickly will not help.
 */
public class HandshakeRejectedException extends Exception {

    private final int status;

    public HandshakeRejectedException(int status) {
        super("WebSocket handshake rejected with HTTP " + status);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public boolean isUnauthorized() {
        return status == 401 || status == 403;
    }
}
