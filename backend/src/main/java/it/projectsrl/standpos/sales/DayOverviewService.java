package it.projectsrl.standpos.sales;

import it.projectsrl.standpos.auth.AppUser;
import it.projectsrl.standpos.auth.AppUserRepository;
import it.projectsrl.standpos.common.ApiException;
import it.projectsrl.standpos.device.Device;
import it.projectsrl.standpos.device.DeviceRepository;
import it.projectsrl.standpos.venue.BusinessDay;
import it.projectsrl.standpos.venue.Venue;
import it.projectsrl.standpos.venue.VenueRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The manager's read-only view of a business day at a venue (docs/adr/0014-day-overview.md): its orders, the shifts
 * open at some point during the day with their cash figures, and the shifts' totals per cashier. The printed shift
 * report and the exports are Phase 3.
 */
@Service
public class DayOverviewService {

    public record OrderRow(UUID id, int dailyNumber, Instant createdAt, Instant paidAt, UUID cashierId,
            String cashierName, UUID registerId, String registerName, Payment.Method paymentMethod, long totalCents,
            CustomerOrder.Status status, OrderService.ReceiptState receipt, boolean receiptPrinted, Instant voidedAt,
            String voidReason) {}

    public record ShiftRow(UUID id, UUID registerId, String registerName, UUID cashierId, String cashierName,
            Instant openedAt, Instant closedAt, long openingFloatCents, int paidOrders, int voidedOrders,
            long cashSalesCents, long manualCardCents, long expectedCashCents, Long countedCashCents,
            Long differenceCents) {}

    /** The sums of a cashier's shifts; the difference only over closed shifts, null while none is closed. */
    public record CashierTotals(UUID cashierId, String cashierName, int shifts, int paidOrders, int voidedOrders,
            long cashSalesCents, long manualCardCents, long totalCents, Long differenceCents) {}

    /** The day's paid orders by payment method (voided orders gave their money back). */
    public record DayTotals(int paidOrders, int voidedOrders, long cashCents, long cardCents, long totalCents) {}

    public record Overview(LocalDate businessDate, LocalDate today, String timeZone, List<OrderRow> orders,
            List<ShiftRow> shifts, List<CashierTotals> cashiers, DayTotals totals) {}

    private final VenueRepository venues;
    private final OrderService orders;
    private final ShiftService shifts;
    private final AppUserRepository users;
    private final DeviceRepository devices;
    private final Clock clock;

    public DayOverviewService(VenueRepository venues, OrderService orders, ShiftService shifts,
            AppUserRepository users, DeviceRepository devices, Clock clock) {
        this.venues = venues;
        this.orders = orders;
        this.shifts = shifts;
        this.users = users;
        this.devices = devices;
        this.clock = clock;
    }

    /** The overview of {@code date}, or of the venue's current business day when null. */
    @Transactional(readOnly = true)
    public Overview overview(UUID venueId, LocalDate date) {
        Venue venue = venues.findById(venueId).orElseThrow(() -> ApiException.notFound("Venue"));
        BusinessDay businessDay = venue.businessDay();
        LocalDate today = businessDay.dateOf(clock.instant());
        LocalDate day = date != null ? date : today;

        List<OrderService.OrderView> views = orders.orders(venue, day);
        List<ShiftService.Summary> summaries = shifts.ofVenue(venueId, businessDay.startOf(day),
                businessDay.startOf(day.plusDays(1)));

        Set<UUID> userIds = new HashSet<>();
        Set<UUID> deviceIds = new HashSet<>();
        views.forEach(v -> {
            userIds.add(v.order().getCashierId());
            deviceIds.add(v.order().getDeviceId());
        });
        summaries.forEach(s -> {
            userIds.add(s.shift().getCashierId());
            deviceIds.add(s.shift().getDeviceId());
        });
        // A user no longer in the tenant is hidden by RLS: the name is then null and the UI shows a dash.
        Map<UUID, String> userNames = users.findAllById(userIds).stream()
                .collect(Collectors.toMap(AppUser::getId, AppUser::getDisplayName));
        Map<UUID, String> deviceNames = devices.findAllById(deviceIds).stream()
                .collect(Collectors.toMap(Device::getId, Device::getName));

        List<OrderRow> orderRows = views.stream().map(v -> {
            CustomerOrder o = v.order();
            return new OrderRow(o.getId(), o.getDailyNumber(), o.getCreatedAt(), o.getPaidAt(), o.getCashierId(),
                    userNames.get(o.getCashierId()), o.getDeviceId(), deviceNames.get(o.getDeviceId()),
                    v.payment() == null ? null : v.payment().getMethod(), o.getTotalCents(), o.getStatus(),
                    v.receipt(), o.isReceiptPrinted(), o.getVoidedAt(), o.getVoidReason());
        }).toList();

        List<ShiftRow> shiftRows = summaries.stream().map(s -> {
            Shift shift = s.shift();
            return new ShiftRow(shift.getId(), shift.getDeviceId(), deviceNames.get(shift.getDeviceId()),
                    shift.getCashierId(), userNames.get(shift.getCashierId()), shift.getOpenedAt(),
                    shift.getClosedAt(), shift.getOpeningFloatCents(), s.paidOrders(), s.voidedOrders(),
                    s.cashSalesCents(), s.manualCardCents(), s.expectedCashCents(), s.countedCashCents(),
                    s.differenceCents());
        }).toList();

        return new Overview(day, today, venue.getTimeZone(), orderRows, shiftRows, cashierTotals(shiftRows),
                dayTotals(views));
    }

    private static List<CashierTotals> cashierTotals(List<ShiftRow> shiftRows) {
        Map<UUID, List<ShiftRow>> byCashier = shiftRows.stream()
                .collect(Collectors.groupingBy(ShiftRow::cashierId, LinkedHashMap::new, Collectors.toList()));
        List<CashierTotals> result = new ArrayList<>();
        byCashier.forEach((cashierId, rows) -> {
            long cash = rows.stream().mapToLong(ShiftRow::cashSalesCents).sum();
            long card = rows.stream().mapToLong(ShiftRow::manualCardCents).sum();
            List<Long> differences = rows.stream()
                    .map(ShiftRow::differenceCents)
                    .filter(Objects::nonNull)
                    .toList();
            result.add(new CashierTotals(cashierId, rows.getFirst().cashierName(), rows.size(),
                    rows.stream().mapToInt(ShiftRow::paidOrders).sum(),
                    rows.stream().mapToInt(ShiftRow::voidedOrders).sum(), cash, card, Math.addExact(cash, card),
                    differences.isEmpty() ? null : differences.stream().mapToLong(Long::longValue).sum()));
        });
        return result;
    }

    private static DayTotals dayTotals(List<OrderService.OrderView> views) {
        int paid = 0;
        int voided = 0;
        long cash = 0;
        long card = 0;
        for (OrderService.OrderView v : views) {
            switch (v.order().getStatus()) {
                case PAID -> {
                    paid++;
                    if (v.payment() != null && v.payment().getMethod() == Payment.Method.CASH) {
                        cash = Math.addExact(cash, v.order().getTotalCents());
                    } else {
                        card = Math.addExact(card, v.order().getTotalCents());
                    }
                }
                case VOIDED -> voided++;
                default -> {
                    // Awaiting payment or cancelled: no money in the day's totals.
                }
            }
        }
        return new DayTotals(paid, voided, cash, card, Math.addExact(cash, card));
    }
}
