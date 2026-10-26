package it.projectsrl.standpos.printing;

import it.projectsrl.standpos.auth.Authz;
import it.projectsrl.standpos.auth.StandposPrincipal;
import it.projectsrl.standpos.common.ApiException;
import it.projectsrl.standpos.device.Device;
import it.projectsrl.standpos.device.DeviceRepository;
import it.projectsrl.standpos.print.protocol.JobAck;
import it.projectsrl.standpos.print.protocol.PrintDocument;
import it.projectsrl.standpos.print.protocol.PrintJob;
import it.projectsrl.standpos.tenant.TenantContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The server-side print queue. Jobs are stored with their document and delivered to the printer's agent when it is
 * connected; they wait while it is offline and are re-delivered until acknowledged. The agent deduplicates by job id.
 */
@Service
public class PrintService {

    private static final Logger log = LoggerFactory.getLogger(PrintService.class);

    /** Unacknowledged jobs are re-delivered after this long (the agent deduplicates). */
    static final Duration REDELIVER_AFTER = Duration.ofSeconds(30);

    /** A receipt that could not print for this long is no longer useful: it fails instead of printing hours late. */
    static final Duration EXPIRE_AFTER = Duration.ofHours(12);

    private static final EnumSet<PrintJobEntity.Status> PENDING =
            EnumSet.of(PrintJobEntity.Status.QUEUED, PrintJobEntity.Status.SENT);

    private final PrintJobRepository jobs;
    private final PrinterRepository printers;
    private final DeviceRepository devices;
    private final ApplicationEventPublisher events;
    private final JsonMapper json;
    private final Clock clock;

    public PrintService(PrintJobRepository jobs, PrinterRepository printers, DeviceRepository devices,
            ApplicationEventPublisher events, JsonMapper json, Clock clock) {
        this.jobs = jobs;
        this.printers = printers;
        this.devices = devices;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    /** The active printer linked to a register, if any. */
    @Transactional(readOnly = true)
    public Optional<Printer> printerOf(UUID registerId) {
        return devices.findById(registerId)
                .map(Device::getPrinterId)
                .flatMap(printers::findById)
                .filter(Printer::isActive);
    }

    /**
     * Queues a document on the register's printer, in the caller's transaction. Empty when the register has no
     * printer: the sale stands, the receipt can be printed later once a printer is linked.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PrintJobEntity> queueForRegister(UUID registerId, UUID orderId, PrintJob.Kind kind,
            PrintDocument document) {
        return printerOf(registerId).map(printer -> queue(printer, orderId, kind, document));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PrintJobEntity queue(Printer printer, UUID orderId, PrintJob.Kind kind, PrintDocument document) {
        UUID createdBy = Authz.principal().map(StandposPrincipal::userId).orElse(null);
        PrintJobEntity job = jobs.saveAndFlush(new PrintJobEntity(printer.getVenueId(), printer.getId(), orderId,
                kind, json.writeValueAsString(document), createdBy));
        events.publishEvent(new PrintEvents.JobQueued(TenantContext.require(), job.getId(), printer.getAgentId()));
        return job;
    }

    /** Jobs to deliver to an agent now: never sent, or sent without an answer for a while. Marks them sent. */
    @Transactional
    public List<PrintJob> takeForDelivery(UUID agentId, Collection<UUID> onlyJobs) {
        Instant now = clock.instant();
        List<Printer> agentPrinters = printers.findAllByAgentId(agentId);
        if (agentPrinters.isEmpty()) {
            return List.of();
        }
        return jobs.findAllByPrinterIdInAndStatusInOrderByCreatedAt(
                        agentPrinters.stream().map(Printer::getId).toList(), PENDING).stream()
                .filter(j -> onlyJobs == null || onlyJobs.contains(j.getId()))
                .filter(j -> j.getStatus() == PrintJobEntity.Status.QUEUED || j.getSentAt() == null
                        || !j.getSentAt().plus(REDELIVER_AFTER).isAfter(now))
                .flatMap(j -> {
                    if (!j.getCreatedAt().plus(EXPIRE_AFTER).isAfter(now)) {
                        finish(j, now, JobAck.Status.FAILED, "expired: not printed within 12 hours");
                        return java.util.stream.Stream.empty();
                    }
                    Printer printer = agentPrinters.stream()
                            .filter(p -> p.getId().equals(j.getPrinterId()))
                            .findFirst()
                            .orElseThrow();
                    j.sent(now);
                    return java.util.stream.Stream.of(message(j, printer));
                })
                .toList();
    }

    /** The agent's answer. Only jobs of printers driven by that agent are accepted. */
    @Transactional
    public void acknowledge(UUID agentId, JobAck ack) {
        PrintJobEntity job = jobs.findById(ack.jobId()).orElse(null);
        if (job == null) {
            log.warn("Ack for unknown job {} from agent {}", ack.jobId(), agentId);
            return;
        }
        Printer printer = printers.findById(job.getPrinterId()).orElseThrow();
        if (!printer.getAgentId().equals(agentId)) {
            log.warn("Agent {} acknowledged job {} of another agent", agentId, ack.jobId());
            return;
        }
        if (!job.isPending()) {
            return; // duplicate ack
        }
        finish(job, clock.instant(), ack.status(), ack.error());
    }

    /** Print jobs of some orders, oldest first, to show the receipt state in the order list. */
    @Transactional(readOnly = true)
    public List<PrintJobEntity> jobsOfOrders(Collection<UUID> orderIds) {
        return orderIds.isEmpty() ? List.of() : jobs.findAllByOrderIdInOrderByCreatedAt(orderIds);
    }

    /** Queues a test page on a printer (manager area). */
    @Transactional
    public PrintJobEntity testPage(UUID venueId, UUID printerId, java.time.ZoneId zone) {
        Printer printer = printers.findByIdAndVenueId(printerId, venueId)
                .orElseThrow(() -> ApiException.notFound("Printer"));
        return queue(printer, null, PrintJob.Kind.TEST, ReceiptBuilder.testPage(printer.getName(),
                printer.getWindowsName(), printer.getCodePage(), clock.instant(), zone));
    }

    private void finish(PrintJobEntity job, Instant now, JobAck.Status status, String error) {
        if (status == JobAck.Status.PRINTED) {
            job.printed(now);
            if (job.getKind() == PrintJob.Kind.RECEIPT && job.getOrderId() != null) {
                events.publishEvent(new PrintEvents.ReceiptPrinted(job.getOrderId()));
            }
        } else {
            job.failed(now, error == null || error.isBlank() ? "print failed" : error);
            log.info("Print job {} failed: {}", job.getId(), job.getLastError());
        }
        events.publishEvent(new PrintEvents.JobFinished(job.getVenueId(), job.getId(), job.getOrderId(),
                job.getKind().name(), job.getStatus().name(), job.getLastError()));
    }

    private PrintJob message(PrintJobEntity job, Printer printer) {
        return new PrintJob(job.getId(), job.getKind(),
                new PrintJob.PrinterTarget(printer.getWindowsName(), printer.columns(), printer.getCodePage()),
                json.readValue(job.getDocument(), PrintDocument.class));
    }
}
