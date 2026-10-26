package it.projectsrl.standpos.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import it.projectsrl.standpos.audit.AuditLog;
import it.projectsrl.standpos.audit.AuditLogRepository;
import it.projectsrl.standpos.auth.Role;
import it.projectsrl.standpos.common.ApiException;
import it.projectsrl.standpos.sales.OrderLine;
import it.projectsrl.standpos.sales.OrderService;
import it.projectsrl.standpos.sales.Payment;
import it.projectsrl.standpos.support.Browser;
import it.projectsrl.standpos.support.Fixtures;
import it.projectsrl.standpos.support.Fixtures.TenantFixture;
import it.projectsrl.standpos.support.IntegrationTest;
import it.projectsrl.standpos.support.SalesFixtures;
import it.projectsrl.standpos.tenant.TenantContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** Goods receipts, counts, adjustments and waste, roles, and the ledger invariant (acceptance criterion 4). */
class StockIT extends IntegrationTest {

    @Autowired
    StockLevelRepository levels;

    @Autowired
    StockMovementRepository movements;

    @Autowired
    StockService stock;

    @Autowired
    GoodsReceiptService receipts;

    @Autowired
    OrderService orders;

    @Autowired
    AuditLogRepository auditLogs;

    @Autowired
    JdbcTemplate jdbc;

    TenantFixture tenant;
    String base;
    UUID category;
    UUID birra;

    @BeforeEach
    void venue() {
        tenant = fixtures.tenant("Stock");
        base = "/api/v1/venues/" + tenant.venueId();
        category = sales.category(tenant, tenant.venueId(), "Bibite");
        birra = sales.product(tenant, tenant.venueId(), category, "Birra", 400, true, 5);
    }

    @Test
    void postingAGoodsReceiptAddsStockAtCostAndFreezesIt() throws Exception {
        Browser owner = login(tenant.ownerEmail(), Fixtures.OWNER_PASSWORD);
        String supplier = JsonPath.read(owner.postJson("/api/v1/suppliers", "{\"name\":\"Birrificio\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String body = owner.postJson(base + "/goods-receipts", "{\"supplierId\":\"" + supplier + "\","
                        + "\"documentRef\":\"DDT 77\",\"receivedOn\":\"2026-10-24\",\"lines\":["
                        + "{\"productId\":\"" + birra + "\",\"quantity\":24,\"unitCostCents\":90}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.totalCostCents").value(2160))
                .andReturn().getResponse().getContentAsString();
        String receiptId = JsonPath.read(body, "$.id");

        owner.postJson(base + "/goods-receipts/" + receiptId + "/post", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"));
        owner.get(base + "/stock")
                .andExpect(jsonPath("$[0].onHand").value(24))
                .andExpect(jsonPath("$[0].valueCents").value(2160))
                .andExpect(jsonPath("$[0].averageCostCents").value(90));

        owner.perform(put(base + "/goods-receipts/" + receiptId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedOn\":\"2026-10-24\",\"lines\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stock.receipt_posted"));
        // The database refuses it too.
        assertThatThrownBy(() -> fixtures.inTenant(tenant.tenantId(), () -> jdbc.update(
                "update goods_receipt set document_ref = 'x' where id = ?::uuid", receiptId)))
                .rootCause().hasMessageContaining("is posted");
        owner.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v1/suppliers/" + supplier))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stock.supplier_in_use"));
        assertThat(fixtures.inTenant(tenant.tenantId(),
                () -> auditLogs.findAllByEntityIdOrderByCreatedAt(UUID.fromString(receiptId))))
                .extracting(AuditLog::getAction).containsExactly("goods_receipt.posted");
    }

    @Test
    void untrackedProductsHaveNoStock() throws Exception {
        UUID caffe = sales.product(tenant, tenant.venueId(), category, "Caffe", 120, false, null);
        login(tenant.ownerEmail(), Fixtures.OWNER_PASSWORD).postJson(base + "/goods-receipts",
                        "{\"receivedOn\":\"2026-10-24\",\"lines\":[{\"productId\":\"" + caffe + "\",\"quantity\":1}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("stock.product_not_tracked"));
    }

    @Test
    void aCountWritesTheDifferencesAndAdjustmentsNeedAReason() throws Exception {
        sales.receive(tenant, tenant.venueId(), birra, 20, 100L);
        String clerkEmail = "clerk-" + UUID.randomUUID() + "@standpos.test";
        fixtures.user(tenant, "Magazzino", clerkEmail, "clerk-password-1", null, tenant.venueId(), Role.STOCK_CLERK);
        Browser clerk = login(clerkEmail, "clerk-password-1");

        String count = clerk.postJson(base + "/stock-counts", "{\"notes\":\"Fine serata\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].expected").value(20))
                .andReturn().getResponse().getContentAsString();
        String countId = JsonPath.read(count, "$.id");
        clerk.perform(put(base + "/stock-counts/" + countId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"productId\":\"" + birra + "\",\"counted\":17}]}"))
                .andExpect(status().isOk());
        clerk.postJson(base + "/stock-counts/" + countId + "/post", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.lines[0].difference").value(-3));

        clerk.postJson(base + "/stock/adjustments",
                        "{\"productId\":\"" + birra + "\",\"type\":\"WASTE\",\"quantity\":2,\"reason\":\"\"}")
                .andExpect(status().isBadRequest());
        clerk.postJson(base + "/stock/adjustments",
                        "{\"productId\":\"" + birra + "\",\"type\":\"WASTE\",\"quantity\":2,\"reason\":\"Bottiglie rotte\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.onHand").value(15));
        clerk.postJson(base + "/stock/adjustments",
                        "{\"productId\":\"" + birra + "\",\"type\":\"ADJUSTMENT\",\"quantity\":-20,\"reason\":\"Regalo\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("stock.insufficient"));
        clerk.get(base + "/stock/movements?productId=" + birra)
                .andExpect(jsonPath("$[0].type").value("WASTE"))
                .andExpect(jsonPath("$[0].note").value("Bottiglie rotte"))
                .andExpect(jsonPath("$[1].type").value("COUNT"))
                .andExpect(jsonPath("$[1].quantity").value(-3));

        // The stock clerk handles stock but not the catalogue or sold-out flags.
        clerk.get(base + "/products").andExpect(status().isForbidden());
        clerk.perform(put(base + "/products/" + birra + "/sold-out").contentType(MediaType.APPLICATION_JSON)
                .content("{\"soldOut\":true}")).andExpect(status().isForbidden());
        assertThat(fixtures.inTenant(tenant.tenantId(), () -> auditLogs.findAllByEntityIdOrderByCreatedAt(birra)))
                .extracting(AuditLog::getAction).contains("stock.adjusted");
    }

    @Test
    void lowStockListShowsWhatRunsOut() throws Exception {
        UUID acqua = sales.product(tenant, tenant.venueId(), category, "Acqua", 100, true, 5);
        sales.receive(tenant, tenant.venueId(), birra, 4, null);
        sales.receive(tenant, tenant.venueId(), acqua, 30, null);
        login(tenant.ownerEmail(), Fixtures.OWNER_PASSWORD).get(base + "/stock/low")
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].productName").value("Birra"))
                .andExpect(jsonPath("$[0].low").value(true))
                .andExpect(jsonPath("$[0].soldOut").value(false));
    }

    /**
     * Acceptance criterion 4: after a random mix of receipts, sales, voids, adjustments, waste and counts, for every
     * product the sum of the movements equals the stock level, in quantity and in value, and no value is negative.
     */
    @Test
    void theLedgerAlwaysAddsUpToTheLevels() {
        UUID cashier = fixtures.user(tenant, "Cassa", null, null, "1357", tenant.venueId(), Role.CASHIER);
        UUID manager = fixtures.user(tenant, "Capo", null, null, "9753", tenant.venueId(), Role.MANAGER);
        SalesFixtures.Register register = sales.register(tenant, tenant.venueId());
        sales.openShift(tenant, register, cashier);
        List<UUID> products = new ArrayList<>(List.of(birra));
        for (String name : List.of("Acqua", "Panino", "Patatine")) {
            products.add(sales.product(tenant, tenant.venueId(), category, name, 250, true, 3));
        }
        Random random = new Random(20261026L);
        List<UUID> paid = new ArrayList<>();
        Map<String, Integer> outcomes = new HashMap<>();
        for (int step = 0; step < 250; step++) {
            UUID product = products.get(random.nextInt(products.size()));
            int op = random.nextInt(10);
            String outcome = TenantContext.callAs(tenant.tenantId(), () -> {
                try {
                    switch (op) {
                        case 0, 1 -> sales.receive(tenant, tenant.venueId(), product, 1 + random.nextInt(12),
                                random.nextBoolean() ? Long.valueOf(50 + random.nextInt(300)) : null);
                        case 2, 3, 4 -> paid.add(orders.create(register.session(cashier), new OrderService.NewOrder(
                                UUID.randomUUID(), List.of(new OrderService.NewLine(product, 1 + random.nextInt(3), null),
                                        new OrderService.NewLine(products.get(random.nextInt(products.size())), 1,
                                                null)), Payment.Method.CASH, null, false)).view().order().getId());
                        case 5 -> {
                            if (paid.isEmpty()) {
                                return "skip";
                            }
                            UUID orderId = paid.remove(random.nextInt(paid.size()));
                            var lines = orders.orders(register.session(cashier), null).stream()
                                    .filter(v -> v.order().getId().equals(orderId))
                                    .findFirst().orElseThrow().lines();
                            orders.voidOrder(register.session(cashier), orderId, manager, "9753", "Prova",
                                    lines.stream().map(l -> new OrderService.VoidLine(l.getId(), random.nextBoolean()
                                            ? OrderLine.VoidDisposition.RETURN_TO_STOCK
                                            : OrderLine.VoidDisposition.WASTE)).toList());
                        }
                        case 6 -> {
                            int delta = random.nextInt(9) - 4;
                            stock.adjust(tenant.venueId(), product, MovementType.ADJUSTMENT, delta == 0 ? 1 : delta,
                                    "Rettifica");
                        }
                        case 7 -> stock.adjust(tenant.venueId(), product, MovementType.WASTE, 1 + random.nextInt(2),
                                "Scarto");
                        default -> {
                            UUID countId = stock.openCount(tenant.venueId(), null).count().getId();
                            stock.enterCounts(tenant.venueId(), countId,
                                    List.of(new StockService.CountedQuantity(product, random.nextInt(15))));
                            stock.postCount(tenant.venueId(), countId);
                        }
                    }
                    return "ok";
                } catch (ApiException e) {
                    return e.code(); // refused operations (not enough stock) must leave no trace
                }
            });
            outcomes.merge(outcome, 1, Integer::sum);
        }
        assertThat(outcomes.get("ok")).isGreaterThan(150);

        fixtures.inTenant(tenant.tenantId(), () -> {
            Map<UUID, StockLevel> byProduct = new HashMap<>();
            levels.findAllByVenueId(tenant.venueId()).forEach(l -> byProduct.put(l.getProductId(), l));
            List<Object[]> totals = movements.totalsByProduct(tenant.venueId());
            assertThat(totals).hasSize(products.size());
            for (Object[] row : totals) {
                StockLevel level = byProduct.get((UUID) row[0]);
                assertThat(((Number) row[1]).intValue()).as("quantity of %s", row[0]).isEqualTo(level.getOnHand());
                assertThat(((Number) row[2]).longValue()).as("value of %s", row[0]).isEqualTo(level.getValueCents());
                assertThat(level.getValueCents()).isNotNegative();
                assertThat(level.getOnHand()).isNotNegative(); // negative stock is off for this venue
            }
            return null;
        });
    }

    private Browser login(String email, String password) throws Exception {
        Browser browser = browser();
        browser.postJson("/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .andExpect(status().isOk());
        return browser;
    }
}
