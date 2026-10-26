package it.projectsrl.standpos.printing;

import it.projectsrl.standpos.common.ApiException;
import it.projectsrl.standpos.device.Device;
import it.projectsrl.standpos.device.DeviceKind;
import it.projectsrl.standpos.device.DeviceRepository;
import it.projectsrl.standpos.venue.Venue;
import it.projectsrl.standpos.venue.VenueRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Printers of a venue, the register-to-printer link and test pages: owners and the venue's managers. */
@RestController
@RequestMapping("/api/v1/venues/{venueId}")
class PrinterController {

    record PrinterRequest(@NotNull UUID agentId, @NotBlank @Size(max = 60) String name,
            @NotBlank @Size(max = 200) String windowsName, Integer widthMm,
            @Pattern(regexp = "CP858|CP850|CP437|CP1252") String codePage, Boolean active) {}

    record PrinterResponse(UUID id, UUID agentId, String agentName, String name, String windowsName, int widthMm,
            String codePage, boolean active, boolean online, Instant lastHeartbeat, List<String> localPrinters) {}

    record LinkRequest(UUID printerId) {}

    record LinkResponse(UUID registerId, UUID printerId) {}

    record TestResponse(UUID jobId, PrintJobEntity.Status status) {}

    private final PrinterRepository printers;
    private final DeviceRepository devices;
    private final VenueRepository venues;
    private final PrintJobRepository jobs;
    private final PrintService prints;
    private final AgentPresence presence;

    PrinterController(PrinterRepository printers, DeviceRepository devices, VenueRepository venues,
            PrintJobRepository jobs, PrintService prints, AgentPresence presence) {
        this.printers = printers;
        this.devices = devices;
        this.venues = venues;
        this.jobs = jobs;
        this.prints = prints;
        this.presence = presence;
    }

    @GetMapping("/printers")
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    @Transactional(readOnly = true)
    public List<PrinterResponse> list(@PathVariable UUID venueId) {
        venue(venueId);
        return printers.findAllByVenueIdOrderByName(venueId).stream().map(this::response).toList();
    }

    @PostMapping("/printers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    @Transactional
    public PrinterResponse create(@PathVariable UUID venueId, @Valid @RequestBody PrinterRequest r) {
        venue(venueId);
        agent(venueId, r.agentId());
        Printer printer = new Printer(venueId, r.agentId(), r.name().trim(), r.windowsName().trim());
        apply(printer, r);
        return response(printers.saveAndFlush(printer));
    }

    @PutMapping("/printers/{printerId}")
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    @Transactional
    public PrinterResponse update(@PathVariable UUID venueId, @PathVariable UUID printerId,
            @Valid @RequestBody PrinterRequest r) {
        Printer printer = printer(venueId, printerId);
        agent(venueId, r.agentId());
        apply(printer, r);
        return response(printers.saveAndFlush(printer));
    }

    /** Only a printer that never printed and no register uses; otherwise deactivate it. */
    @DeleteMapping("/printers/{printerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    @Transactional
    public void delete(@PathVariable UUID venueId, @PathVariable UUID printerId) {
        Printer printer = printer(venueId, printerId);
        boolean linked = devices.findAllByVenueIdOrderByCreatedAtDesc(venueId).stream()
                .anyMatch(d -> printerId.equals(d.getPrinterId()));
        boolean printed = !jobs.findAllByPrinterIdInAndStatusInOrderByCreatedAt(List.of(printerId),
                List.of(PrintJobEntity.Status.values())).isEmpty();
        if (linked || printed) {
            throw ApiException.conflict("printing.printer_in_use", "Registers or print jobs use this printer");
        }
        printers.delete(printer);
    }

    @PostMapping("/printers/{printerId}/test")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    public TestResponse test(@PathVariable UUID venueId, @PathVariable UUID printerId) {
        ZoneId zone = ZoneId.of(venue(venueId).getTimeZone());
        PrintJobEntity job = prints.testPage(venueId, printerId, zone);
        return new TestResponse(job.getId(), job.getStatus());
    }

    /** Links a register to the printer its receipts go to (null unlinks it). */
    @PutMapping("/devices/{registerId}/printer")
    @PreAuthorize("@authz.canManageVenue(#venueId)")
    @Transactional
    public LinkResponse link(@PathVariable UUID venueId, @PathVariable UUID registerId,
            @RequestBody LinkRequest r) {
        Device register = devices.findByIdAndVenueId(registerId, venueId)
                .filter(d -> d.getKind() == DeviceKind.REGISTER)
                .orElseThrow(() -> ApiException.notFound("Register"));
        if (r.printerId() != null) {
            printer(venueId, r.printerId());
        }
        register.linkPrinter(r.printerId());
        return new LinkResponse(register.getId(), register.getPrinterId());
    }

    private void apply(Printer printer, PrinterRequest r) {
        int width = r.widthMm() == null ? printer.getWidthMm() : r.widthMm();
        if (width != 58 && width != 80) {
            throw ApiException.badRequest("printing.invalid_width", "Paper width is 58 or 80 mm");
        }
        printer.update(r.agentId(), r.name().trim(), r.windowsName().trim(), width,
                r.codePage() == null ? printer.getCodePage() : r.codePage(), r.active() == null || r.active());
    }

    private PrinterResponse response(Printer p) {
        String agentName = devices.findById(p.getAgentId()).map(Device::getName).orElse(null);
        var state = presence.state(p.getAgentId());
        return new PrinterResponse(p.getId(), p.getAgentId(), agentName, p.getName(), p.getWindowsName(),
                p.getWidthMm(), p.getCodePage(), p.isActive(), presence.isOnline(p.getAgentId()),
                state.map(AgentPresence.State::lastHeartbeat).orElse(null),
                state.map(AgentPresence.State::localPrinters).orElse(List.of()));
    }

    private Device agent(UUID venueId, UUID agentId) {
        return devices.findByIdAndVenueId(agentId, venueId)
                .filter(d -> d.getKind() == DeviceKind.PRINT_AGENT && !d.isRevoked())
                .orElseThrow(() -> ApiException.badRequest("printing.invalid_agent",
                        "The agent must be an enrolled print agent of this venue"));
    }

    private Venue venue(UUID venueId) {
        return venues.findById(venueId).orElseThrow(() -> ApiException.notFound("Venue"));
    }

    private Printer printer(UUID venueId, UUID printerId) {
        return printers.findByIdAndVenueId(printerId, venueId).orElseThrow(() -> ApiException.notFound("Printer"));
    }
}
