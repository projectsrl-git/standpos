package it.projectsrl.standpos.sales;

import it.projectsrl.standpos.auth.MembershipRepository;
import it.projectsrl.standpos.auth.Role;
import it.projectsrl.standpos.common.ApiException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cash drawer shifts: one open shift per register, opened by a cashier with a float and closed with the counted cash.
 * The expected cash is the float plus the cash of the shift's paid orders (voided orders gave their cash back).
 */
@Service
public class ShiftService {

    public record Summary(Shift shift, int paidOrders, int voidedOrders, long cashSalesCents, long manualCardCents,
            long expectedCashCents, Long countedCashCents, Long differenceCents) {}

    private final ShiftRepository shifts;
    private final CustomerOrderRepository orders;
    private final PaymentRepository payments;
    private final MembershipRepository memberships;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ShiftService(ShiftRepository shifts, CustomerOrderRepository orders, PaymentRepository payments,
            MembershipRepository memberships, TransactionTemplate tx, Clock clock) {
        this.shifts = shifts;
        this.orders = orders;
        this.payments = payments;
        this.memberships = memberships;
        this.tx = tx;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<Summary> current(RegisterSession session) {
        return shifts.findByDeviceIdAndClosedAtIsNull(session.deviceId()).map(this::summary);
    }

    public Summary open(RegisterSession session, long openingFloatCents) {
        if (openingFloatCents < 0) {
            throw ApiException.badRequest("shift.invalid_float", "The opening float cannot be negative");
        }
        try {
            return tx.execute(status -> {
                if (shifts.findByDeviceIdAndClosedAtIsNull(session.deviceId()).isPresent()) {
                    throw ApiException.conflict("shift.already_open", "A shift is already open on this register");
                }
                Shift shift = shifts.saveAndFlush(new Shift(session.venueId(), session.deviceId(),
                        session.cashierId(), clock.instant(), openingFloatCents));
                return summary(shift);
            });
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("shift.already_open", "A shift is already open on this register");
        }
    }

    /** Closes the register's shift: by the cashier who opened it, or by an owner or manager of the venue. */
    @Transactional
    public Summary close(RegisterSession session, long countedCashCents) {
        if (countedCashCents < 0) {
            throw ApiException.badRequest("shift.invalid_cash", "The counted cash cannot be negative");
        }
        Shift shift = shifts.findByDeviceIdAndClosedAtIsNull(session.deviceId())
                .orElseThrow(() -> ApiException.conflict("shift.not_open", "No shift is open on this register"));
        boolean manager = memberships.findAllByUserId(session.cashierId()).stream()
                .anyMatch(m -> m.grants(session.venueId(), Role.MANAGER));
        if (!shift.getCashierId().equals(session.cashierId()) && !manager) {
            throw ApiException.conflict("shift.other_cashier", "The open shift belongs to another cashier");
        }
        Summary open = summary(shift);
        shift.close(clock.instant(), session.cashierId(), open.expectedCashCents(), countedCashCents);
        return summary(shift);
    }

    private Summary summary(Shift shift) {
        List<CustomerOrder> list = orders.findAllByShiftId(shift.getId());
        List<UUID> paidIds = list.stream()
                .filter(o -> o.getStatus() == CustomerOrder.Status.PAID)
                .map(CustomerOrder::getId)
                .toList();
        int voided = (int) list.stream().filter(o -> o.getStatus() == CustomerOrder.Status.VOIDED).count();
        List<Payment> paid = paidIds.isEmpty() ? List.of() : payments.findAllByOrderIdIn(paidIds);
        long cash = paid.stream().filter(p -> p.getMethod() == Payment.Method.CASH)
                .mapToLong(Payment::getAmountCents).sum();
        long manualCard = paid.stream().filter(p -> p.getMethod() == Payment.Method.MANUAL_CARD)
                .mapToLong(Payment::getAmountCents).sum();
        long expected = shift.isOpen() ? shift.getOpeningFloatCents() + cash : shift.getExpectedCashCents();
        Long counted = shift.getCountedCashCents();
        return new Summary(shift, paidIds.size(), voided, cash, manualCard, expected, counted,
                counted == null ? null : counted - expected);
    }
}
