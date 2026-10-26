package it.projectsrl.standpos.printagent.printer;

import it.projectsrl.standpos.print.protocol.PrintJob;
import it.projectsrl.standpos.printagent.escpos.CodePage;
import it.projectsrl.standpos.printagent.escpos.EscPosRenderer;

/** Renders a job for its printer's columns and code page, then spools the bytes. */
public final class EscPosJobPrinter implements JobPrinter {

    private final boolean checkStatus;

    /** @param checkStatus see {@link Printers#send(String, byte[], boolean)} */
    public EscPosJobPrinter(boolean checkStatus) {
        this.checkStatus = checkStatus;
    }

    @Override
    public void print(PrintJob job) {
        PrintJob.PrinterTarget target = job.printer();
        CodePage codePage;
        try {
            codePage = CodePage.parse(target.codePage());
        } catch (IllegalArgumentException e) {
            throw new PrintFailedException(e.getMessage(), e);
        }
        byte[] bytes = new EscPosRenderer(target.columns(), codePage).render(job.document());
        Printers.send(target.windowsName(), bytes, checkStatus);
    }
}
