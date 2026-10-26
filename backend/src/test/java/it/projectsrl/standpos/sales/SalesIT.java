package it.projectsrl.standpos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import it.projectsrl.standpos.audit.AuditLog;
import it.projectsrl.standpos.audit.AuditLogRepository;
import it.projectsrl.standpos.auth.Role;
import it.projectsrl.standpos.common.ApiException;
import it.projectsrl.standpos.device.DeviceTokens;
import it.projectsrl.standpos.print.protocol.PrintJob;
import it.projectsrl.standpos.printing.PrintJobEntity;
import it.projectsrl.standpos.printing.PrintJobRepository;
import it.projectsrl.standpos.stock.MovementType;
import it.projectsrl.standpos.stock.StockLevelRepository;
import it.projectsrl.standpos.stock.StockMovement;
import it.projectsrl.standpos.stock.StockMovementRepository;
import it.projectsrl.standpos.support.Browser;
import it.projectsrl.standpos.support.Fixtures;
import it.projectsrl.standpos.support.Fixtures.TenantFixture;
import it.projectsrl.standpos.support.IntegrationTest;
import it.projectsrl.standpos.support.SalesFixtures;
import it.projectsrl.standpos.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Cash sales end to end: acceptance criteria 2 (concurrency), 3 (idempotency), 7 (usage cap), 8 (daily number) and
 * 9 (referenced products), plus receipts, "Senza scontrino", shifts and voids.
 */
class SalesIT extends IntegrationTest {

    @Autowired
    OrderService orders;

    @Autowired
    StockLevelRepository levels;

    @Autowired
    StockMovementRepository movements;

    @Autowired
    PrintJobRepository jobs;

    @Autowired
    CustomerOrderRepository orderRepository;

    @Autowired
    AuditLogRepository auditLogs;

    @Autowired
    JdbcTemplate jdbc;

    TenantFixture tenant;
    UUID cashier;
    SalesFixtures.Register register;
    Browser till;
    UUID category;
    UUID panino;
    UUID caffe;

    @BeforeEach
    void openRegister() throws Exception {
        tenant = fixtures.tenant("Sales");
        cashier = fixtures.user(tenant, "Anna", null, null, "1357", tenant.venueId(), Role.CASHIER);
        register = sales.register(tenant, tenant.venueId());
        sales.printer(tenant, tenant.venueId(), register);
        till = login(register, cashier, "1357");
        category = sales.category(tenant, tenant.venueId(), "Cucina");
        panino = sales.product(tenant, tenant.venueId(), category, "Panino", 450, true, 3);
        caffe = sales.product(tenant, tenant.venueId(), category, "Caffè", 120, false, null);
        sales.receive(tenant, tenant.venueId(), panino, 10, 150L);
        till.postJson("/api/v1/register/shift/open", "{\"openingFloatCents\":5000}").andExpect(status().isCreated());
    }

    // Sale, receipt, stock

    @Test
    void cashSaleGivesChangeMovesStockAndQueuesTheReceipt() throws Exception {
        String body = sell(till, UUID.randomUUID(), "[" + line(panino, 2, "senza cipolla") + "," + line(caffe, 1, null)
                + "]", "CASH", 2000L, true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.dailyNumber").value(1))
                .andExpect(jsonPath("$.totalCents").value(1020))
                .andExpect(jsonPath("$.payment.changeCents").value(980))
                .andExpect(jsonPath("$.receipt").value("QUEUED"))
                .andExpect(jsonPath("$.lines[0].name").value("Panino"))
                .andExpect(jsonPath("$.lines[0].note").value("senza cipolla"))
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(JsonPath.read(body, "$.id"));

        assertThat(onHand(panino)).isEqualTo(8);
        List<PrintJobEntity> printed = inTenant(() -> jobs.findAllByOrderIdInOrderByCreatedAt(List.of(orderId)));
        assertThat(printed).singleElement().satisfies(job -> {
            assertThat(job.getKind()).isEqualTo(PrintJob.Kind.RECEIPT);
            assertThat(job.getDocument()).contains("Scontrino di cortesia", "\"drawer\"", "N. 1");
        });

        // Below the low threshold the register shows what is left.
        sell(till, UUID.randomUUID(), "[" + line(panino, 5, null) + "]", "CASH", null, false)
                .andExpect(status().isCreated());
        till.get("/api/v1/register/catalogue")
                .andExpect(jsonPath("$.categories[0].products[?(@.id == '" + panino + "')].onHand").value(3))
                .andExpect(jsonPath("$.categories[0].products[?(@.id == '" + panino + "')].low").value(true))
                .andExpect(jsonPath("$.categories[0].products[?(@.id == '" + caffe + "')].onHand").value(
                        org.hamcrest.Matchers.contains((Object) null)));
    }

    @Test
    void manualCardIsFlaggedForReconciliation() throws Exception {
        sell(till, UUID.randomUUID(), "[" + line(caffe, 1, null) + "]", "MANUAL_CARD", null, true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.method").value("MANUAL_CARD"))
                .andExpect(jsonPath("$.payment.needsReconciliation").value(true))
                .andExpect(jsonPath("$.payment.changeCents").doesNotExist());
    }

    /** Acceptance criterion 3. */
    @Test
    void retryWithTheSameClientUuidReturnsTheSameOrder() throws Exception {
        UUID clientId = UUID.randomUUID();
        String first = sell(till, clientId, "[" + line(panino, 2, null) + "]", "CASH", null, true)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = sell(till, clientId, "[" + line(panino, 2, null) + "]", "CASH", null, true)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String id = JsonPath.read(first, "$.id");
        assertThat((String) JsonPath.read(second, "$.id")).isEqualTo(id);
        assertThat(onHand(panino)).isEqualTo(8);
        UUID orderId = UUID.fromString(id);
        assertThat(inTenant(() -> movements.findAllByReferenceIdAndType(orderId, MovementType.SALE))).hasSize(1);
        assertThat(inTenant(() -> jobs.findAllByOrderIdInOrderByCreatedAt(List.of(orderId)))).hasSize(1);
        assertThat(inTenant(() -> orderRepository.findAll())).hasSize(1);
        assertThat(inTenant(() -> jdbc.queryForObject("select sum(sales) from usage_counter", Long.class)))
                .isEqualTo(1);
    }

    /** Acceptance criterion 2: two registers sell the last unit at the same moment; exactly one succeeds. */
    @Test
    void twoRegistersRaceForTheLastUnit() throws Exception {
        UUID lasagna = sales.product(tenant, tenant.venueId(), category, "Lasagna", 600, true, null);
        UUID other = fixtures.user(tenant, "Bruno", null, null, "2468", tenant.venueId(), Role.CASHIER);
        SalesFixtures.Register second = sales.register(tenant, tenant.venueId());
        sales.openShift(tenant, second, other);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 1; round <= 5; round++) {
                sales.receive(tenant, tenant.venueId(), lasagna, 1, 300L);
                CyclicBarrier start = new CyclicBarrier(2);
                List<Future<String>> outcomes = new ArrayList<>();
                for (RegisterSession session : List.of(register.session(cashier), second.session(other))) {
                    outcomes.add(pool.submit(() -> {
                        start.await();
                        try {
                            TenantContext.callAs(tenant.tenantId(), () -> orders.create(session,
                                    new OrderService.NewOrder(UUID.randomUUID(),
                                            List.of(new OrderService.NewLine(lasagna, 1, null)), Payment.Method.CASH,
                                            null, false)));
                            return "sold";
                        } catch (ApiException e) {
                            return e.code();
                        }
                    }));
                }
                List<String> results = new ArrayList<>();
                for (Future<String> outcome : outcomes) {
                    results.add(outcome.get());
                }
                assertThat(results).as("round %d", round).containsExactlyInAnyOrder("sold", "sales.insufficient_stock");
                assertThat(onHand(lasagna)).isZero();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(inTenant(() -> movements.findAllByProductIdOrderByCreatedAtDesc(lasagna,
                        org.springframework.data.domain.PageRequest.of(0, 100))))
                .filteredOn(m -> m.getType() == MovementType.SALE)
                .hasSize(5);
    }

    @Test
    void stockAndAvailabilityAreChecked() throws Exception {
        sell(till, UUID.randomUUID(), "[" + line(panino, 11, null) + "]", "CASH", null, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("sales.insufficient_stock"))
                .andExpect(jsonPath("$.products[0].available").value(10));
        assertThat(onHand(panino)).isEqualTo(10);

        Browser owner = owner();
        owner.perform(put("/api/v1/venues/" + tenant.venueId() + "/products/" + caffe + "/sold-out")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"soldOut\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.soldOut").value(true));
        sell(till, UUID.randomUUID(), "[" + line(caffe, 1, null) + "]", "CASH", null, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("sales.product_unavailable"));

        sell(till, UUID.randomUUID(), "[" + line(panino, 1, null) + "]", "CASH", 100L, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("sales.insufficient_tender"));
    }

    @Test
    void withoutReceiptTheDrawerStillOpensAndTheReceiptCanBePrintedLater() throws Exception {
        String body = sell(till, UUID.randomUUID(), "[" + line(panino, 1, null) + "]", "CASH", 500L, false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receipt").value("NOT_REQUESTED"))
                .andReturn().getResponse().getContentAsString();
        UUID orderId = UUID.fromString(JsonPath.read(body, "$.id"));
        assertThat(inTenant(() -> jobs.findAllByOrderIdInOrderByCreatedAt(List.of(orderId))))
                .extracting(PrintJobEntity::getKind).containsExactly(PrintJob.Kind.DRAWER);
        assertThat(onHand(panino)).isEqualTo(9);

        till.postJson("/api/v1/register/orders/" + orderId + "/receipt", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt").value("QUEUED"));
        till.get("/api/v1/register/orders")
                .andExpect(jsonPath("$[0].id").value(orderId.toString()))
                .andExpect(jsonPath("$[0].receipt").value("QUEUED"));
    }

    @Test
    void sellingNeedsAnOpenShiftAndClosingCountsTheCash() throws Exception {
        sell(till, UUID.randomUUID(), "[" + line(panino, 2, null) + "]", "CASH", 1000L, false)
                .andExpect(status().isCreated());
        till.postJson("/api/v1/register/shift/close", "{\"countedCashCents\":5850}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashSalesCents").value(900))
                .andExpect(jsonPath("$.expectedCashCents").value(5900))
                .andExpect(jsonPath("$.differenceCents").value(-50));
        sell(till, UUID.randomUUID(), "[" + line(panino, 1, null) + "]", "CASH", null, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("shift.not_open"));
    }

    /** Acceptance criterion 8: a non-midnight day change, with a fixed clock. */
    @Test
    void dailyNumberRestartsAtTheVenueDayChange() throws Exception {
        owner().perform(put("/api/v1/venues/" + tenant.venueId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sales bar\",\"kind\":\"PERMANENT\",\"dayChangeTime\":\"05:30\"}"))
                .andExpect(status().isOk());

        clock.set(Instant.parse("2026-07-10T03:00:00Z")); // 05:00 in Rome: still 9 July
        number(1, "2026-07-09");
        clock.set(Instant.parse("2026-07-10T03:29:00Z")); // 05:29
        number(2, "2026-07-09");
        clock.set(Instant.parse("2026-07-10T03:30:00Z")); // 05:30: a new business day
        number(1, "2026-07-10");
        number(2, "2026-07-10");
        till.get("/api/v1/register/orders").andExpect(jsonPath("$.length()").value(2));
        till.get("/api/v1/register/orders?date=2026-07-09").andExpect(jsonPath("$.length()").value(2));
    }

    /** Acceptance criterion 7: the warning at 80%, the grace period after 100% and the block after the day change. */
    @Test
    void usageCapWarnsThenBlocksAfterTheDayChangeUntilNextMonth() throws Exception {
        setCap(10);
        Browser owner = owner();
        clock.set(Instant.parse("2026-10-20T18:00:00Z")); // 20:00 in Rome; next day change 21 Oct 04:00 = 02:00Z
        for (int i = 1; i <= 7; i++) {
            coffee().andExpect(status().isCreated());
        }
        owner.get("/api/v1/usage").andExpect(jsonPath("$.sales").value(7)).andExpect(jsonPath("$.warning").value(false));
        coffee().andExpect(status().isCreated());
        owner.get("/api/v1/usage").andExpect(jsonPath("$.warning").value(true))
                .andExpect(jsonPath("$.capReached").value(false));
        coffee().andExpect(status().isCreated());
        coffee().andExpect(status().isCreated());
        owner.get("/api/v1/usage")
                .andExpect(jsonPath("$.sales").value(10))
                .andExpect(jsonPath("$.capReached").value(true))
                .andExpect(jsonPath("$.blockedFrom").value("2026-10-21T02:00:00Z"))
                .andExpect(jsonPath("$.blocked").value(false));
        till.get("/api/v1/register/status").andExpect(jsonPath("$.usage.capReached").value(true))
                .andExpect(jsonPath("$.usage.blocked").value(false));

        // Selling continues until the venue's day change...
        coffee().andExpect(status().isCreated());
        clock.set(Instant.parse("2026-10-21T01:59:59Z"));
        coffee().andExpect(status().isCreated());

        // ...then new sales are refused, and the refused sale is not counted.
        clock.set(Instant.parse("2026-10-21T02:00:00Z"));
        coffee().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("usage.cap_reached"));
        owner.get("/api/v1/usage").andExpect(jsonPath("$.sales").value(12)).andExpect(jsonPath("$.blocked").value(true));
        till.get("/api/v1/register/status").andExpect(jsonPath("$.usage.blocked").value(true));

        // A new month starts from zero.
        clock.set(Instant.parse("2026-11-01T09:00:00Z"));
        coffee().andExpect(status().isCreated());
        owner.get("/api/v1/usage").andExpect(jsonPath("$.sales").value(1)).andExpect(jsonPath("$.capReached").value(false));
    }

    @Test
    void platformAdminRaisesTheCapAndUnblocks() throws Exception {
        setCap(2);
        clock.set(Instant.parse("2026-10-20T18:00:00Z"));
        coffee().andExpect(status().isCreated());
        coffee().andExpect(status().isCreated());
        clock.set(Instant.parse("2026-10-21T10:00:00Z"));
        coffee().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("usage.cap_reached"));

        TenantFixture platform = fixtures.tenant("Platform");
        fixtures.inTenant(platform.tenantId(),
                () -> jdbc.update("update app_user set platform_admin = true where id = ?", platform.ownerId()));
        Browser admin = login(platform.ownerEmail());
        String url = "/api/v1/admin/tenants/" + tenant.tenantId() + "/sales-cap";
        owner().perform(put(url).contentType(MediaType.APPLICATION_JSON).content("{\"salesCapOverride\":20}"))
                .andExpect(status().isForbidden());
        admin.perform(put(url).contentType(MediaType.APPLICATION_JSON).content("{\"salesCapOverride\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cap").value(20))
                .andExpect(jsonPath("$.capReached").value(false));
        coffee().andExpect(status().isCreated());
        assertThat(inTenant(() -> auditLogs.findAllByEntityIdOrderByCreatedAt(tenant.tenantId())))
                .extracting(AuditLog::getAction).contains("tenant.cap_changed");
    }

    // Void

    @Test
    void voidNeedsAManagerPinAndSendsEachLineToStockOrWaste() throws Exception {
        UUID manager = fixtures.user(tenant, "Marco", null, null, "9753", tenant.venueId(), Role.MANAGER);
        String body = sell(till, UUID.randomUUID(), "[" + line(panino, 2, null) + "," + line(panino, 1, "bruciato")
                        + "," + line(caffe, 1, null) + "]", "CASH", null, true)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(body, "$.id");
        List<String> lineIds = JsonPath.read(body, "$.lines[*].id");
        assertThat(onHand(panino)).isEqualTo(7);

        till.get("/api/v1/register/approvers")
                .andExpect(jsonPath("$[*].displayName").value(org.hamcrest.Matchers.hasItem("Marco")))
                .andExpect(jsonPath("$[*].displayName").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("Anna"))));
        String voidUrl = "/api/v1/register/orders/" + orderId + "/void";
        String lines = "[{\"lineId\":\"" + lineIds.get(0) + "\",\"disposition\":\"RETURN_TO_STOCK\"},"
                + "{\"lineId\":\"" + lineIds.get(1) + "\",\"disposition\":\"WASTE\"},"
                + "{\"lineId\":\"" + lineIds.get(2) + "\",\"disposition\":\"WASTE\"}]";
        till.postJson(voidUrl, voidJson(cashier, "1357", lines))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("auth.not_approver"));
        till.postJson(voidUrl, voidJson(manager, "0000", lines))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("auth.invalid_pin"));

        till.postJson(voidUrl, voidJson(manager, "9753", lines))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"))
                .andExpect(jsonPath("$.payment.status").value("REFUNDED"))
                .andExpect(jsonPath("$.lines[1].voidDisposition").value("WASTE"));
        // The cashier is still logged in on the register.
        till.get("/api/v1/register/shift").andExpect(status().isOk()).andExpect(jsonPath("$.voidedOrders").value(1))
                .andExpect(jsonPath("$.cashSalesCents").value(0));

        assertThat(onHand(panino)).isEqualTo(9); // two back on the shelf, one in the bin
        UUID order = UUID.fromString(orderId);
        List<StockMovement> ledger = inTenant(() -> movements.findAllByProductIdOrderByCreatedAtDesc(panino,
                org.springframework.data.domain.PageRequest.of(0, 20)));
        assertThat(ledger).filteredOn(m -> order.equals(m.getReferenceId()))
                .extracting(StockMovement::getType, StockMovement::getQuantity)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(MovementType.SALE, -3),
                        org.assertj.core.groups.Tuple.tuple(MovementType.RETURN_TO_STOCK, 2),
                        org.assertj.core.groups.Tuple.tuple(MovementType.SALE_REVERSAL, 1),
                        org.assertj.core.groups.Tuple.tuple(MovementType.WASTE, -1));
        assertThat(inTenant(() -> auditLogs.findAllByEntityIdOrderByCreatedAt(order)))
                .singleElement().satisfies(a -> {
                    assertThat(a.getAction()).isEqualTo("order.voided");
                    assertThat(a.getAfterData()).contains(manager.toString(), "WASTE", "RETURN_TO_STOCK");
                });
        assertThat(inTenant(() -> jobs.findAllByOrderIdInOrderByCreatedAt(List.of(order))))
                .extracting(PrintJobEntity::getKind).containsExactly(PrintJob.Kind.RECEIPT, PrintJob.Kind.DRAWER);
        till.postJson(voidUrl, voidJson(manager, "9753", lines))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("sales.not_voidable"));
    }

    // Products in use (acceptance criterion 9)

    @Test
    void aReferencedProductCannotBeDeletedButCanBeDeactivated() throws Exception {
        sell(till, UUID.randomUUID(), "[" + line(caffe, 1, null) + "]", "CASH", null, false)
                .andExpect(status().isCreated());
        Browser owner = owner();
        String products = "/api/v1/venues/" + tenant.venueId() + "/products/";
        owner.get(products.substring(0, products.length() - 1)).andExpect(jsonPath("$[?(@.id == '" + caffe + "')].inUse").value(
                org.hamcrest.Matchers.contains(true)));
        owner.perform(delete(products + caffe))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("catalog.product_in_use"));
        owner.postJson(products + caffe + "/deactivate", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        till.get("/api/v1/register/catalogue")
                .andExpect(jsonPath("$.categories[0].products[?(@.id == '" + caffe + "')]").isEmpty());

        UUID unused = sales.product(tenant, tenant.venueId(), category, "Mai venduto", 100, false, null);
        owner.perform(delete(products + unused)).andExpect(status().isNoContent());
    }

    // Helpers

    private void number(int expected, String businessDate) throws Exception {
        sell(till, UUID.randomUUID(), "[" + line(caffe, 1, null) + "]", "CASH", null, false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dailyNumber").value(expected))
                .andExpect(jsonPath("$.businessDate").value(businessDate));
    }

    private ResultActions coffee() throws Exception {
        return sell(till, UUID.randomUUID(), "[" + line(caffe, 1, null) + "]", "CASH", null, false);
    }

    private void setCap(int cap) {
        fixtures.inTenant(tenant.tenantId(),
                () -> jdbc.update("update tenant set sales_cap_override = ? where id = ?", cap, tenant.tenantId()));
    }

    private int onHand(UUID productId) {
        return inTenant(() -> levels.findByProductId(productId).orElseThrow().getOnHand());
    }

    private <T> T inTenant(java.util.function.Supplier<T> action) {
        return fixtures.inTenant(tenant.tenantId(), action);
    }

    private ResultActions sell(Browser browser, UUID clientId, String lines, String method, Long tendered,
            boolean print) throws Exception {
        return browser.postJson("/api/v1/orders", "{\"clientOrderId\":\"" + clientId + "\",\"lines\":" + lines
                + ",\"paymentMethod\":\"" + method + "\",\"tenderedCents\":" + tendered + ",\"printReceipt\":" + print
                + "}");
    }

    private static String line(UUID productId, int quantity, String note) {
        return "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity
                + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
    }

    private static String voidJson(UUID managerId, String pin, String lines) {
        return "{\"managerId\":\"" + managerId + "\",\"pin\":\"" + pin + "\",\"reason\":\"Errore di battitura\","
                + "\"lines\":" + lines + "}";
    }

    private Browser login(SalesFixtures.Register r, UUID userId, String pin) throws Exception {
        Browser browser = browser().withCookie(DeviceTokens.COOKIE, r.token());
        browser.postJson("/api/v1/auth/pin", "{\"userId\":\"" + userId + "\",\"pin\":\"" + pin + "\"}")
                .andExpect(status().isOk());
        return browser;
    }

    private Browser owner() throws Exception {
        return login(tenant.ownerEmail());
    }

    private Browser login(String email) throws Exception {
        Browser browser = browser();
        browser.postJson("/api/v1/auth/login",
                        "{\"email\":\"" + email + "\",\"password\":\"" + Fixtures.OWNER_PASSWORD + "\"}")
                .andExpect(status().isOk());
        return browser;
    }
}
