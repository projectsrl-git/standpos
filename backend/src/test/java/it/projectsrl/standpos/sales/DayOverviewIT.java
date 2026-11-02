package it.projectsrl.standpos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import it.projectsrl.standpos.auth.Role;
import it.projectsrl.standpos.device.DeviceTokens;
import it.projectsrl.standpos.printing.ReceiptBuilder;
import it.projectsrl.standpos.support.Browser;
import it.projectsrl.standpos.support.Fixtures;
import it.projectsrl.standpos.support.Fixtures.TenantFixture;
import it.projectsrl.standpos.support.IntegrationTest;
import it.projectsrl.standpos.support.SalesFixtures;
import it.projectsrl.standpos.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The manager's day overview (orders, shifts, cashier totals) and the on-screen receipt preview. */
class DayOverviewIT extends IntegrationTest {

    @Autowired
    OrderService orders;

    @Autowired
    ShiftService shifts;

    TenantFixture tenant;
    UUID anna;
    UUID bruno;
    String managerEmail;
    SalesFixtures.Register cassa1;
    SalesFixtures.Register cassa2;
    UUID panino;
    UUID caffe;

    @BeforeEach
    void venueWithTwoRegisters() {
        tenant = fixtures.tenant("Overview");
        anna = fixtures.user(tenant, "Anna", null, null, "1357", tenant.venueId(), Role.CASHIER);
        bruno = fixtures.user(tenant, "Bruno", null, null, "2580", tenant.venueId(), Role.CASHIER);
        managerEmail = "mara-" + UUID.randomUUID() + "@standpos.test";
        fixtures.user(tenant, "Mara", managerEmail, Fixtures.OWNER_PASSWORD, "9753", tenant.venueId(), Role.MANAGER);
        cassa1 = sales.register(tenant, tenant.venueId());
        sales.printer(tenant, tenant.venueId(), cassa1);
        cassa2 = sales.register(tenant, tenant.venueId()); // no printer
        UUID category = sales.category(tenant, tenant.venueId(), "Cucina");
        panino = sales.product(tenant, tenant.venueId(), category, "Panino", 450, false, null);
        caffe = sales.product(tenant, tenant.venueId(), category, "Caffè", 120, false, null);
        clock.set(Instant.parse("2026-07-10T16:00:00Z")); // 18:00 in Rome: business day 10 July
    }

    @Test
    void managerSeesTheDaysOrdersShiftsAndTotalsPerCashier() throws Exception {
        sales.openShift(tenant, cassa1, anna);
        sales.openShift(tenant, cassa2, bruno);
        UUID first = sales.sell(tenant, cassa1, anna, panino, 2, true).view().order().getId();
        sell(cassa2, bruno, caffe, Payment.Method.MANUAL_CARD);
        UUID third = sales.sell(tenant, cassa1, anna, caffe, 1, false).view().order().getId();
        TenantContext.runAs(tenant.tenantId(), () -> {
            OrderLine line = orders.orders(cassa1.session(anna), null).getFirst().lines().getFirst();
            orders.voidOrder(cassa1.session(anna), third, tenant.ownerId(), Fixtures.PIN, "Errore",
                    List.of(new OrderService.VoidLine(line.getId(), OrderLine.VoidDisposition.RETURN_TO_STOCK)));
            shifts.close(cassa1.session(anna), 5800); // expected 5000 float + 900 cash
        });

        String annaShift = "$.shifts[?(@.cashierName == 'Anna')]";
        String annaTotals = "$.cashiers[?(@.cashierName == 'Anna')]";
        login(managerEmail).get("/api/v1/venues/" + tenant.venueId() + "/day-overview?date=2026-07-10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value("2026-07-10"))
                .andExpect(jsonPath("$.today").value("2026-07-10"))
                .andExpect(jsonPath("$.timeZone").value("Europe/Rome"))
                .andExpect(jsonPath("$.orders.length()").value(3))
                .andExpect(jsonPath("$.orders[0].dailyNumber").value(3))
                .andExpect(jsonPath("$.orders[0].status").value("VOIDED"))
                .andExpect(jsonPath("$.orders[0].voidReason").value("Errore"))
                .andExpect(jsonPath("$.orders[1].paymentMethod").value("MANUAL_CARD"))
                .andExpect(jsonPath("$.orders[1].cashierName").value("Bruno"))
                .andExpect(jsonPath("$.orders[1].receipt").value("NOT_REQUESTED"))
                .andExpect(jsonPath("$.orders[2].id").value(first.toString()))
                .andExpect(jsonPath("$.orders[2].cashierName").value("Anna"))
                .andExpect(jsonPath("$.orders[2].registerName").value("REGISTER test"))
                .andExpect(jsonPath("$.orders[2].paymentMethod").value("CASH"))
                .andExpect(jsonPath("$.orders[2].totalCents").value(900))
                .andExpect(jsonPath("$.orders[2].receipt").value("QUEUED"))
                .andExpect(jsonPath("$.orders[2].receiptPrinted").value(false))
                .andExpect(jsonPath("$.shifts.length()").value(2))
                .andExpect(jsonPath(annaShift + ".openingFloatCents").value(5000))
                .andExpect(jsonPath(annaShift + ".paidOrders").value(1))
                .andExpect(jsonPath(annaShift + ".voidedOrders").value(1))
                .andExpect(jsonPath(annaShift + ".cashSalesCents").value(900))
                .andExpect(jsonPath(annaShift + ".expectedCashCents").value(5900))
                .andExpect(jsonPath(annaShift + ".countedCashCents").value(5800))
                .andExpect(jsonPath(annaShift + ".differenceCents").value(-100))
                .andExpect(jsonPath("$.shifts[?(@.cashierName == 'Bruno')].closedAt").value(
                        org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath(annaTotals + ".shifts").value(1))
                .andExpect(jsonPath(annaTotals + ".totalCents").value(900))
                .andExpect(jsonPath(annaTotals + ".differenceCents").value(-100))
                .andExpect(jsonPath("$.cashiers[?(@.cashierName == 'Bruno')].manualCardCents").value(120))
                .andExpect(jsonPath("$.cashiers[?(@.cashierName == 'Bruno')].differenceCents").value(
                        org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath("$.totals.paidOrders").value(2))
                .andExpect(jsonPath("$.totals.voidedOrders").value(1))
                .andExpect(jsonPath("$.totals.cashCents").value(900))
                .andExpect(jsonPath("$.totals.cardCents").value(120))
                .andExpect(jsonPath("$.totals.totalCents").value(1020));
    }

    @Test
    void withoutADateItShowsTodayByTheVenueDayChange() throws Exception {
        sales.openShift(tenant, cassa1, anna);
        sales.sell(tenant, cassa1, anna, caffe, 1, false);
        Browser manager = login(managerEmail);
        String url = "/api/v1/venues/" + tenant.venueId() + "/day-overview";

        clock.set(Instant.parse("2026-07-11T01:59:00Z")); // 03:59 in Rome, before the 04:00 day change
        manager.get(url)
                .andExpect(jsonPath("$.businessDate").value("2026-07-10"))
                .andExpect(jsonPath("$.orders.length()").value(1));

        clock.set(Instant.parse("2026-07-11T02:00:00Z")); // 04:00: a new business day
        manager.get(url)
                .andExpect(jsonPath("$.businessDate").value("2026-07-11"))
                .andExpect(jsonPath("$.orders.length()").value(0))
                // The shift is still open, so it belongs to the new day too, with its whole figures.
                .andExpect(jsonPath("$.shifts.length()").value(1))
                .andExpect(jsonPath("$.shifts[0].cashSalesCents").value(120));
        manager.get(url + "?date=2026-07-09")
                .andExpect(jsonPath("$.orders.length()").value(0))
                .andExpect(jsonPath("$.shifts.length()").value(0));
    }

    @Test
    void onlyOwnersAndManagersOfTheVenueSeeIt() throws Exception {
        String clerkEmail = "clerk-" + UUID.randomUUID() + "@standpos.test";
        fixtures.user(tenant, "Carlo", clerkEmail, Fixtures.OWNER_PASSWORD, null, tenant.venueId(),
                Role.STOCK_CLERK);
        UUID otherVenue = fixtures.venue(tenant, "Sagra");
        String otherManager = "other-" + UUID.randomUUID() + "@standpos.test";
        fixtures.user(tenant, "Olga", otherManager, Fixtures.OWNER_PASSWORD, null, otherVenue, Role.MANAGER);
        TenantFixture otherTenant = fixtures.tenant("Elsewhere");
        String url = "/api/v1/venues/" + tenant.venueId() + "/day-overview";

        login(tenant.ownerEmail()).get(url).andExpect(status().isOk());
        login(managerEmail).get(url).andExpect(status().isOk());
        login(clerkEmail).get(url).andExpect(status().isForbidden());
        login(otherManager).get(url).andExpect(status().isForbidden());
        // Another tenant's owner passes the role check but RLS hides the venue.
        login(otherTenant.ownerEmail()).get(url).andExpect(status().isNotFound());
        sales.openShift(tenant, cassa1, anna);
        till(cassa1, anna, "1357").get(url).andExpect(status().isForbidden());
    }

    @Test
    void receiptPreviewShowsTheReceiptAsItWouldPrint() throws Exception {
        clock.reset(); // enrolment codes expire by the real time
        sales.openShift(tenant, cassa2, bruno);
        UUID orderId = sales.sell(tenant, cassa2, bruno, panino, 2, true).view().order().getId();
        Browser till = till(cassa2, bruno, "2580");

        String body = till.get("/api/v1/register/orders/" + orderId + "/receipt-preview")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns").value(48))
                .andExpect(jsonPath("$.lines[?(@.text =~ /.*N\\. 1/)].size").value(
                        org.hamcrest.Matchers.contains("DOUBLE")))
                .andExpect(jsonPath("$.lines[?(@.text =~ /.*N\\. 1/)].bold").value(
                        org.hamcrest.Matchers.contains(true)))
                .andReturn().getResponse().getContentAsString();
        List<String> text = JsonPath.read(body, "$.lines[*].text");
        assertThat(text).anySatisfy(line -> assertThat(line).contains(ReceiptBuilder.NON_FISCAL_NOTICE));
        assertThat(text).anySatisfy(line -> assertThat(line).startsWith("Panino").endsWith("9,00"));
        assertThat(text).noneMatch(line -> line.contains("[cut]") || line.contains("[drawer]"));
        assertThat(text).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(48));

        // The same receipt from the manager area; not from another venue's manager or register.
        String managerUrl = "/api/v1/venues/" + tenant.venueId() + "/orders/" + orderId + "/receipt-preview";
        login(managerEmail).get(managerUrl)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(text.size()));
        UUID otherVenue = fixtures.venue(tenant, "Sagra");
        SalesFixtures.Register sagra = sales.register(tenant, otherVenue);
        UUID dario = fixtures.user(tenant, "Dario", null, null, "4826", otherVenue, Role.CASHIER);
        till(sagra, dario, "4826").get("/api/v1/register/orders/" + orderId + "/receipt-preview")
                .andExpect(status().isNotFound());
        login(managerEmail).get("/api/v1/venues/" + otherVenue + "/orders/" + orderId + "/receipt-preview")
                .andExpect(status().isForbidden());
        login(tenant.ownerEmail()).get("/api/v1/venues/" + otherVenue + "/orders/" + orderId + "/receipt-preview")
                .andExpect(status().isNotFound());
    }

    private void sell(SalesFixtures.Register register, UUID cashierId, UUID productId, Payment.Method method) {
        TenantContext.runAs(tenant.tenantId(), () -> orders.create(register.session(cashierId),
                new OrderService.NewOrder(UUID.randomUUID(), List.of(new OrderService.NewLine(productId, 1, null)),
                        method, null, false)));
    }

    private Browser till(SalesFixtures.Register register, UUID userId, String pin) throws Exception {
        Browser browser = browser().withCookie(DeviceTokens.COOKIE, register.token());
        browser.postJson("/api/v1/auth/pin", "{\"userId\":\"" + userId + "\",\"pin\":\"" + pin + "\"}")
                .andExpect(status().isOk());
        return browser;
    }

    private Browser login(String email) throws Exception {
        Browser browser = browser();
        browser.postJson("/api/v1/auth/login",
                        "{\"email\":\"" + email + "\",\"password\":\"" + Fixtures.OWNER_PASSWORD + "\"}")
                .andExpect(status().isOk());
        return browser;
    }
}
