package it.projectsrl.standpos.sales;

import it.projectsrl.standpos.common.ApiException;
import it.projectsrl.standpos.device.Device;
import it.projectsrl.standpos.device.DeviceRepository;
import it.projectsrl.standpos.print.protocol.PlainTextRenderer;
import it.projectsrl.standpos.print.protocol.PrintDocument;
import it.projectsrl.standpos.print.protocol.PrintElement;
import it.projectsrl.standpos.printing.PrintService;
import it.projectsrl.standpos.printing.Printer;
import it.projectsrl.standpos.printing.ReceiptBuilder;
import it.projectsrl.standpos.venue.Venue;
import it.projectsrl.standpos.venue.VenueRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The courtesy receipt of an order as it prints, for the screen (docs/adr/0013-on-screen-receipt-preview.md): the
 * server builds the same print document the agent receives and lays it out with the protocol's renderer, so the
 * browser only draws styled monospace lines and never duplicates the layout.
 */
@Service
public class ReceiptPreviews {

    /** Font A columns on 80 mm paper: the width of the preview when the register has no printer. */
    public static final int DEFAULT_COLUMNS = 48;

    public record Preview(int columns, List<PlainTextRenderer.Line> lines) {}

    private final CustomerOrderRepository orders;
    private final OrderLineRepository orderLines;
    private final PaymentRepository payments;
    private final VenueRepository venues;
    private final DeviceRepository devices;
    private final PrintService prints;

    public ReceiptPreviews(CustomerOrderRepository orders, OrderLineRepository orderLines, PaymentRepository payments,
            VenueRepository venues, DeviceRepository devices, PrintService prints) {
        this.orders = orders;
        this.orderLines = orderLines;
        this.payments = payments;
        this.venues = venues;
        this.devices = devices;
        this.prints = prints;
    }

    /** The receipt of a paid (or since voided) order of the venue, at the width of its register's printer. */
    @Transactional(readOnly = true)
    public Preview of(UUID venueId, UUID orderId) {
        CustomerOrder order = orders.findByIdAndVenueId(orderId, venueId)
                .orElseThrow(() -> ApiException.notFound("Order"));
        List<Payment> paid = payments.findAllByOrderId(orderId);
        if (paid.isEmpty() || (order.getStatus() != CustomerOrder.Status.PAID
                && order.getStatus() != CustomerOrder.Status.VOIDED)) {
            throw ApiException.conflict("sales.not_printable", "Only paid orders have a receipt");
        }
        Venue venue = venues.findById(venueId).orElseThrow(() -> ApiException.notFound("Venue"));
        String register = devices.findById(order.getDeviceId()).map(Device::getName).orElse("");
        PrintDocument document = ReceiptBuilder.build(OrderService.receipt(venue, register, order,
                orderLines.findAllByOrderIdOrderByLineNo(orderId), paid.getFirst(), false, false));
        // The paper's end is drawn by the preview itself: no cut marker.
        PrintDocument shown = new PrintDocument(document.version(), document.elements().stream()
                .filter(e -> !(e instanceof PrintElement.Cut))
                .toList());
        int columns = prints.printerOf(order.getDeviceId()).map(Printer::columns).orElse(DEFAULT_COLUMNS);
        return new Preview(columns, new PlainTextRenderer(columns).lines(shown));
    }
}
