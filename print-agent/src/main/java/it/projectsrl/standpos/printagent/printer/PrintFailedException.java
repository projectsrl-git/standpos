package it.projectsrl.standpos.printagent.printer;

/** A print attempt failed; the message is short, technical and goes into the job's FAILED acknowledgement. */
public class PrintFailedException extends RuntimeException {

    public PrintFailedException(String message) {
        super(message);
    }

    public PrintFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
