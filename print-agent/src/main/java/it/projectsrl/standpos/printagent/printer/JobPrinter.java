package it.projectsrl.standpos.printagent.printer;

import it.projectsrl.standpos.print.protocol.PrintJob;

/** Prints one job, or throws; the dispatcher owns retries. An interface so tests can fail on purpose. */
@FunctionalInterface
public interface JobPrinter {

    void print(PrintJob job) throws Exception;
}
