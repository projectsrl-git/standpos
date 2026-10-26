package it.projectsrl.standpos.printagent.stomp;

/** The peer sent something that is not valid STOMP 1.2; the session is dropped and re-established. */
public class StompProtocolException extends RuntimeException {

    public StompProtocolException(String message) {
        super(message);
    }
}
