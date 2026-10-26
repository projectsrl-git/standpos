import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { ApiError, api } from '../api/client';
import type { GridProduct, Order, PaymentMethod, RegisterGrid, RegisterStatus } from '../api/types';
import { ErrorBanner } from '../components/ErrorBanner';
import { formatCents } from '../money';
import {
  addProduct,
  cartTotal,
  lineTotal,
  removeLine,
  setNote,
  setQuantity,
  toOrderLines,
  type CartLine,
} from '../register/cart';
import { OrdersPanel } from '../register/OrdersPanel';
import { PaymentDialog, type Checkout } from '../register/PaymentDialog';
import { CloseShiftDialog, OpenShiftPanel } from '../register/ShiftPanel';
import { useVenueEvents } from '../register/useVenueEvents';
import { useSession } from '../session/SessionContext';

function patchProduct(grid: RegisterGrid, productId: string, patch: Partial<GridProduct>): RegisterGrid {
  return {
    ...grid,
    categories: grid.categories.map((c) => ({
      ...c,
      products: c.products.map((p) => (p.id === productId ? { ...p, ...patch } : p)),
    })),
  };
}

/**
 * The register: product grid by category with live availability, the cart, cash or manual card payment with or
 * without receipt, today's orders (reprint, void), the cash drawer shift and the status banners.
 */
export function RegisterPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { me, logout } = useSession();
  const [grid, setGrid] = useState<RegisterGrid | null>(null);
  const [status, setStatus] = useState<RegisterStatus | null>(null);
  const [categoryId, setCategoryId] = useState<string | null>(null);
  const [cart, setCart] = useState<CartLine[]>([]);
  // Generated once per cart: a retry after a network error sends the same id, so the server never sells twice.
  const [clientOrderId, setClientOrderId] = useState<string | null>(null);
  const [paying, setPaying] = useState<PaymentMethod | null>(null);
  const [busy, setBusy] = useState(false);
  const [payError, setPayError] = useState<unknown>(null);
  const [lastOrder, setLastOrder] = useState<Order | null>(null);
  const [printFailure, setPrintFailure] = useState<{ orderId: string; error: string | null } | null>(null);
  const [showOrders, setShowOrders] = useState(false);
  const [closingShift, setClosingShift] = useState(false);
  const [noteFor, setNoteFor] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);

  const loadStatus = useCallback(async () => {
    try {
      setStatus(await api.get<RegisterStatus>('/register/status'));
    } catch (e) {
      setError(e);
    }
  }, []);

  useEffect(() => {
    let active = true;
    api.get<RegisterGrid>('/register/catalogue').then(
      (data) => {
        if (!active) return;
        setGrid(data);
        setCategoryId(data.categories[0]?.id ?? null);
      },
      (e: unknown) => {
        if (active) setError(e);
      },
    );
    api.get<RegisterStatus>('/register/status').then(
      (data) => {
        if (active) setStatus(data);
      },
      (e: unknown) => {
        if (active) setError(e);
      },
    );
    return () => {
      active = false;
    };
  }, []);

  const connection = useVenueEvents(me?.device?.venueId ?? grid?.venueId, {
    onStock: (event) =>
      setGrid((g) =>
        g
          ? patchProduct(g, event.productId, { soldOut: event.soldOut, onHand: event.onHand, low: event.low })
          : g,
      ),
    onPrinter: (event) =>
      setStatus((s) =>
        s?.printer && s.printer.agentId === event.agentId
          ? { ...s, printer: { ...s.printer, online: event.online } }
          : s,
      ),
    onPrintJob: (event) => {
      if (event.kind === 'RECEIPT' && event.orderId) {
        setPrintFailure(event.status === 'FAILED' ? { orderId: event.orderId, error: event.error } : null);
      }
    },
    // Events sent while the connection was down are lost: reload the grid and the banners.
    onReconnect: () => {
      api.get<RegisterGrid>('/register/catalogue').then(setGrid, setError);
      void loadStatus();
    },
  });

  function updateCart(next: CartLine[]) {
    setCart(next);
    setClientOrderId(null);
    setLastOrder(null);
  }

  async function checkout({ method, tenderedCents, printReceipt }: Checkout) {
    const id = clientOrderId ?? crypto.randomUUID();
    setClientOrderId(id);
    setBusy(true);
    setPayError(null);
    try {
      const order = await api.post<Order>('/orders', {
        clientOrderId: id,
        lines: toOrderLines(cart),
        paymentMethod: method,
        tenderedCents,
        printReceipt,
      });
      setLastOrder(order);
      setCart([]);
      setClientOrderId(null);
      setPaying(null);
      void loadStatus(); // the usage cap banner may change with this sale
    } catch (e) {
      setPayError(e);
      if (e instanceof ApiError && e.code === 'usage.cap_reached') {
        void loadStatus();
      }
    } finally {
      setBusy(false);
    }
  }

  async function retryReceipt(orderId: string) {
    try {
      await api.post<Order>(`/register/orders/${orderId}/receipt`);
      setPrintFailure(null);
    } catch (e) {
      setError(e);
    }
  }

  const category = grid?.categories.find((c) => c.id === categoryId);
  const total = cartTotal(cart);
  const shift = status?.shift ?? null;
  const ownShift = shift !== null && shift.cashierId === me?.user?.id;
  const offline = connection === 'offline' || (typeof navigator !== 'undefined' && !navigator.onLine);

  return (
    <div className="register">
      <header className="topbar">
        <strong>{grid?.venueName ?? t('register.title')}</strong>
        <span>{t('register.cashier', { name: me?.user?.displayName ?? '' })}</span>
        <button type="button" className="btn" onClick={() => setShowOrders(true)}>
          {t('register.orders')}
        </button>
        {shift && (
          <button type="button" className="btn" onClick={() => setClosingShift(true)}>
            {t('shift.closeTitle')}
          </button>
        )}
        <button
          type="button"
          className="btn"
          onClick={() => {
            void logout().then(() => navigate('/pin'));
          }}
        >
          {t('common.logout')}
        </button>
      </header>

      {offline && (
        <p className="error banner" role="alert">
          {t('register.offline')}
        </p>
      )}
      {status?.usage.blocked && (
        <p className="error banner" role="alert">
          {t('register.capBlocked')}
        </p>
      )}
      {status && status.usage.capReached && !status.usage.blocked && (
        <p className="warning banner" role="status">
          {t('register.capReached', {
            time: status.usage.blockedFrom
              ? new Date(status.usage.blockedFrom).toLocaleString('it-IT', {
                  dateStyle: 'short',
                  timeStyle: 'short',
                })
              : '',
          })}
        </p>
      )}
      {status?.printer && !status.printer.online && (
        <p className="warning banner" role="status">
          {t('register.printerOffline', { name: status.printer.name })}
        </p>
      )}
      {status && !status.printer && <p className="notice">{t('register.noPrinter')}</p>}
      {printFailure && (
        <p className="error banner" role="alert">
          {t('register.printFailed', { error: printFailure.error ?? '' })}{' '}
          <button
            type="button"
            className="btn btn-small"
            onClick={() => void retryReceipt(printFailure.orderId)}
          >
            {t('orders.reprint')}
          </button>
        </p>
      )}
      <ErrorBanner error={error} />

      {status && !shift && (
        <div className="centered-panel">
          <OpenShiftPanel onOpened={() => void loadStatus()} />
        </div>
      )}
      {shift && !ownShift && <p className="notice">{t('shift.otherCashier')}</p>}

      {shift && ownShift && (
        <div className="register-body">
          <div className="register-products">
            {grid && grid.categories.length === 0 && <p>{t('register.emptyCatalogue')}</p>}
            <nav className="category-tabs" aria-label={t('categories.title')}>
              {grid?.categories.map((c) => (
                <button
                  key={c.id}
                  type="button"
                  className={c.id === categoryId ? 'category-tab selected' : 'category-tab'}
                  style={{ borderColor: c.colour, background: c.id === categoryId ? c.colour : undefined }}
                  aria-pressed={c.id === categoryId}
                  onClick={() => setCategoryId(c.id)}
                >
                  {c.name}
                </button>
              ))}
            </nav>
            <section className="product-grid">
              {category?.products.map((p) => (
                <button
                  key={p.id}
                  type="button"
                  className={p.soldOut ? 'product sold-out' : 'product'}
                  style={{ borderLeftColor: category.colour }}
                  disabled={p.soldOut}
                  onClick={() => updateCart(addProduct(cart, p))}
                >
                  <span className="product-name">{p.name}</span>
                  <span className="product-price">
                    {p.soldOut ? t('register.soldOut') : formatCents(p.priceCents)}
                  </span>
                  {!p.soldOut && p.low && p.onHand !== null && (
                    <span className="product-left">{t('register.left', { count: p.onHand })}</span>
                  )}
                </button>
              ))}
            </section>
          </div>

          <aside className="cart" aria-label={t('register.cart')}>
            <h2>{t('register.cart')}</h2>
            {lastOrder && (
              <div className="order-done" role="status">
                <strong>{t('register.orderDone', { number: lastOrder.dailyNumber })}</strong>
                {lastOrder.payment?.changeCents ? (
                  <span className="change-big">
                    {t('payment.change', { amount: formatCents(lastOrder.payment.changeCents) })}
                  </span>
                ) : null}
              </div>
            )}
            {cart.length === 0 && !lastOrder && <p className="muted">{t('register.emptyCart')}</p>}
            <ul className="cart-lines">
              {cart.map((line) => (
                <li key={line.key}>
                  <div className="cart-line-main">
                    <span className="cart-line-name">{line.name}</span>
                    <span>{formatCents(lineTotal(line))}</span>
                  </div>
                  {line.note && <div className="cart-note">{line.note}</div>}
                  <div className="cart-line-actions">
                    <button
                      type="button"
                      className="btn btn-small"
                      aria-label={t('register.less', { name: line.name })}
                      onClick={() => updateCart(setQuantity(cart, line.key, line.quantity - 1))}
                    >
                      −
                    </button>
                    <span className="cart-qty">{line.quantity}</span>
                    <button
                      type="button"
                      className="btn btn-small"
                      aria-label={t('register.more', { name: line.name })}
                      onClick={() => updateCart(setQuantity(cart, line.key, line.quantity + 1))}
                    >
                      +
                    </button>
                    <button type="button" className="btn btn-small" onClick={() => setNoteFor(line.key)}>
                      {t('register.note')}
                    </button>
                    <button
                      type="button"
                      className="btn btn-small btn-danger"
                      onClick={() => updateCart(removeLine(cart, line.key))}
                    >
                      {t('common.delete')}
                    </button>
                  </div>
                  {noteFor === line.key && (
                    <input
                      className="note-input"
                      autoFocus
                      aria-label={t('register.note')}
                      placeholder={t('register.notePlaceholder')}
                      value={line.note}
                      maxLength={120}
                      onChange={(e) => updateCart(setNote(cart, line.key, e.target.value))}
                      onBlur={() => setNoteFor(null)}
                    />
                  )}
                </li>
              ))}
            </ul>
            <p className="cart-total">
              {t('payment.total')} <strong>{formatCents(total)}</strong>
            </p>
            <div className="pay-buttons">
              <button
                type="button"
                className="btn btn-primary btn-large"
                disabled={cart.length === 0 || status?.usage.blocked}
                onClick={() => {
                  setPayError(null);
                  setPaying('CASH');
                }}
              >
                {t('payment.cash')}
              </button>
              <button
                type="button"
                className="btn btn-large"
                disabled={cart.length === 0 || status?.usage.blocked}
                onClick={() => {
                  setPayError(null);
                  setPaying('MANUAL_CARD');
                }}
              >
                {t('payment.manualCard')}
              </button>
            </div>
          </aside>
        </div>
      )}

      {paying && (
        <PaymentDialog
          method={paying}
          totalCents={total}
          busy={busy}
          error={payError}
          onConfirm={(c) => void checkout(c)}
          onClose={() => setPaying(null)}
        />
      )}
      {showOrders && <OrdersPanel onClose={() => setShowOrders(false)} />}
      {closingShift && shift && (
        <CloseShiftDialog
          shift={shift}
          onClose={() => setClosingShift(false)}
          onClosed={() => {
            setClosingShift(false);
            void loadStatus();
          }}
        />
      )}
    </div>
  );
}
