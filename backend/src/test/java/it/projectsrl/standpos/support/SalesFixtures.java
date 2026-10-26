package it.projectsrl.standpos.support;

import it.projectsrl.standpos.auth.AuthLookup;
import it.projectsrl.standpos.auth.CurrentDevice;
import it.projectsrl.standpos.catalog.CatalogService;
import it.projectsrl.standpos.device.DeviceKind;
import it.projectsrl.standpos.device.DeviceRepository;
import it.projectsrl.standpos.device.DeviceTokens;
import it.projectsrl.standpos.printing.Printer;
import it.projectsrl.standpos.printing.PrinterRepository;
import it.projectsrl.standpos.sales.OrderService;
import it.projectsrl.standpos.sales.Payment;
import it.projectsrl.standpos.sales.RegisterSession;
import it.projectsrl.standpos.sales.ShiftService;
import it.projectsrl.standpos.stock.GoodsReceiptService;
import it.projectsrl.standpos.support.Fixtures.TenantFixture;
import it.projectsrl.standpos.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.support.TransactionTemplate;

/** Catalogue, stock, registers, printers and sales for integration tests, through the application's services. */
@TestComponent
public class SalesFixtures {

    /** An enrolled register with its token (for a {@link Browser}) and the session services expect. */
    public record Register(String token, CurrentDevice device) {

        public RegisterSession session(UUID cashierId) {
            return new RegisterSession(device.venueId(), device.deviceId(), cashierId, device);
        }
    }

    private final Fixtures fixtures;
    private final CatalogService catalog;
    private final GoodsReceiptService receipts;
    private final OrderService orders;
    private final ShiftService shifts;
    private final AuthLookup lookup;
    private final PrinterRepository printers;
    private final DeviceRepository devices;
    private final TransactionTemplate tx;

    SalesFixtures(Fixtures fixtures, CatalogService catalog, GoodsReceiptService receipts, OrderService orders,
            ShiftService shifts, AuthLookup lookup, PrinterRepository printers, DeviceRepository devices,
            TransactionTemplate tx) {
        this.fixtures = fixtures;
        this.catalog = catalog;
        this.receipts = receipts;
        this.orders = orders;
        this.shifts = shifts;
        this.lookup = lookup;
        this.printers = printers;
        this.devices = devices;
        this.tx = tx;
    }

    public UUID category(TenantFixture t, UUID venueId, String name) {
        return TenantContext.callAs(t.tenantId(), () -> catalog.createCategory(venueId,
                new CatalogService.CategoryData(name, "#1E88E5", 0, null)).getId());
    }

    /** A product; tracked products start at zero stock (sold out until goods come in). */
    public UUID product(TenantFixture t, UUID venueId, UUID categoryId, String name, long priceCents, boolean tracked,
            Integer lowThreshold) {
        return TenantContext.callAs(t.tenantId(), () -> catalog.createProduct(venueId, new CatalogService.ProductData(
                categoryId, name, priceCents, null, true, tracked, lowThreshold, false, 0)).getId());
    }

    /** Posts a goods receipt of one line. */
    public UUID receive(TenantFixture t, UUID venueId, UUID productId, int quantity, Long unitCostCents) {
        return TenantContext.callAs(t.tenantId(), () -> {
            UUID id = receipts.createReceipt(venueId, new GoodsReceiptService.ReceiptData(null, "DDT 1",
                    LocalDate.now(), null, List.of(new GoodsReceiptService.LineData(productId, quantity,
                            unitCostCents)))).receipt().getId();
            receipts.postReceipt(venueId, id);
            return id;
        });
    }

    public Register register(TenantFixture t, UUID venueId) {
        String token = fixtures.enrol(t, venueId, DeviceKind.REGISTER);
        return new Register(token, lookup.findDevice(DeviceTokens.hash(token)).orElseThrow());
    }

    /** A print agent device and a printer on it, linked to {@code register}. Returns the agent token. */
    public String printer(TenantFixture t, UUID venueId, Register register) {
        String agentToken = fixtures.enrol(t, venueId, DeviceKind.PRINT_AGENT);
        CurrentDevice agent = lookup.findDevice(DeviceTokens.hash(agentToken)).orElseThrow();
        fixtures.inTenant(t.tenantId(), () -> {
            Printer printer = printers.saveAndFlush(new Printer(venueId, agent.deviceId(), "POS80", "file:test"));
            devices.findById(register.device().deviceId()).orElseThrow().linkPrinter(printer.getId());
            return null;
        });
        return agentToken;
    }

    public void openShift(TenantFixture t, Register register, UUID cashierId) {
        TenantContext.runAs(t.tenantId(), () -> shifts.open(register.session(cashierId), 5000));
    }

    /** A cash sale of {@code quantity} units, with or without receipt. */
    public OrderService.Result sell(TenantFixture t, Register register, UUID cashierId, UUID productId, int quantity,
            boolean printReceipt) {
        return TenantContext.callAs(t.tenantId(), () -> orders.create(register.session(cashierId),
                new OrderService.NewOrder(UUID.randomUUID(), List.of(new OrderService.NewLine(productId, quantity,
                        null)), Payment.Method.CASH, null, printReceipt)));
    }

    public <T> T inTx(UUID tenantId, java.util.function.Supplier<T> action) {
        return TenantContext.callAs(tenantId, () -> tx.execute(status -> action.get()));
    }
}
