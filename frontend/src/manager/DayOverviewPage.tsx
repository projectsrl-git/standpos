import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import type { DayOverview, DayOverviewOrder } from '../api/types';
import { ErrorBanner } from '../components/ErrorBanner';
import { ReceiptPreviewDialog } from '../components/ReceiptPreviewDialog';
import { formatCents } from '../money';
import { useManager } from './context';

/**
 * "Ordini e turni": a business day of the selected venue, read-only. The orders with cashier, register, payment and
 * receipt; the shifts open during the day with their cash figures; the shifts' totals per cashier. Times are shown in
 * the venue's time zone. The printed shift report and the exports are Phase 3.
 */
export function DayOverviewPage() {
  const { t } = useTranslation();
  const { venueId } = useManager();
  // null: the venue's current business day, as the server computes it (time zone and day change).
  const [date, setDate] = useState<string | null>(null);
  const [overview, setOverview] = useState<DayOverview | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [showing, setShowing] = useState<DayOverviewOrder | null>(null);

  useEffect(() => {
    if (!venueId) return undefined;
    let active = true;
    const query = date ? `?date=${encodeURIComponent(date)}` : '';
    api.get<DayOverview>(`/venues/${venueId}/day-overview${query}`).then(
      (data) => {
        if (!active) return;
        setOverview(data);
        setError(null);
      },
      (e: unknown) => {
        if (active) setError(e);
      },
    );
    return () => {
      active = false;
    };
  }, [venueId, date]);

  const zone = overview?.timeZone;
  const time = (iso: string) =>
    new Date(iso).toLocaleTimeString('it-IT', { timeStyle: 'short', timeZone: zone });
  const dateTime = (iso: string) =>
    new Date(iso).toLocaleString('it-IT', { dateStyle: 'short', timeStyle: 'short', timeZone: zone });
  const name = (value: string | null) => value ?? t('dayOverview.unknown');
  const money = (cents: number | null) => (cents === null ? t('dayOverview.unknown') : formatCents(cents));

  return (
    <section>
      <h1>{t('dayOverview.title')}</h1>
      <p>{t('dayOverview.intro')}</p>
      <div className="day-picker">
        <label className="field">
          <span>{t('dayOverview.date')}</span>
          <input
            type="date"
            value={date ?? overview?.businessDate ?? ''}
            onChange={(e) => setDate(e.target.value === '' ? null : e.target.value)}
          />
        </label>
        <button type="button" className="btn" disabled={date === null} onClick={() => setDate(null)}>
          {t('dayOverview.today')}
        </button>
      </div>
      <ErrorBanner error={error} />

      {overview && (
        <>
          <h2 className="section-title">{t('dayOverview.totals')}</h2>
          <dl className="summary day-totals">
            <dt>{t('dayOverview.paidOrders')}</dt>
            <dd>{overview.totals.paidOrders}</dd>
            <dt>{t('dayOverview.voidedOrders')}</dt>
            <dd>{overview.totals.voidedOrders}</dd>
            <dt>{t('dayOverview.cash')}</dt>
            <dd>{formatCents(overview.totals.cashCents)}</dd>
            <dt>{t('dayOverview.card')}</dt>
            <dd>{formatCents(overview.totals.cardCents)}</dd>
            <dt>{t('dayOverview.total')}</dt>
            <dd>
              <strong>{formatCents(overview.totals.totalCents)}</strong>
            </dd>
          </dl>

          <h2 className="section-title">{t('dayOverview.orders')}</h2>
          {overview.orders.length === 0 ? (
            <p>{t('dayOverview.noOrders')}</p>
          ) : (
            <div className="table-scroll">
              <table className="table" aria-label={t('dayOverview.orders')}>
                <thead>
                  <tr>
                    <th>{t('dayOverview.number')}</th>
                    <th>{t('dayOverview.time')}</th>
                    <th>{t('dayOverview.cashier')}</th>
                    <th>{t('dayOverview.register')}</th>
                    <th>{t('dayOverview.payment')}</th>
                    <th className="num">{t('dayOverview.total')}</th>
                    <th>{t('dayOverview.state')}</th>
                    <th>{t('dayOverview.receipt')}</th>
                    <th>{t('common.actions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {overview.orders.map((o) => (
                    <tr key={o.id} className={o.status === 'VOIDED' ? 'muted' : undefined}>
                      <td>
                        <strong>{o.dailyNumber}</strong>
                      </td>
                      <td>{time(o.paidAt ?? o.createdAt)}</td>
                      <td>{name(o.cashierName)}</td>
                      <td>{name(o.registerName)}</td>
                      <td>{o.paymentMethod ? t(`payment.methods.${o.paymentMethod}`) : ''}</td>
                      <td className="num">{formatCents(o.totalCents)}</td>
                      <td>
                        <span className={o.status === 'VOIDED' ? 'badge badge-danger' : 'badge'}>
                          {t(`dayOverview.status.${o.status}`)}
                        </span>
                        {o.voidReason && <div className="muted">{o.voidReason}</div>}
                      </td>
                      <td>
                        {o.receiptPrinted ? t('orders.receipt.PRINTED') : t(`orders.receipt.${o.receipt}`)}
                      </td>
                      <td>
                        {o.paymentMethod && (
                          <button type="button" className="btn btn-small" onClick={() => setShowing(o)}>
                            {t('orders.showReceipt')}
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <h2 className="section-title">{t('dayOverview.shifts')}</h2>
          {overview.shifts.length === 0 ? (
            <p>{t('dayOverview.noShifts')}</p>
          ) : (
            <>
              <div className="table-scroll">
                <table className="table" aria-label={t('dayOverview.shifts')}>
                  <thead>
                    <tr>
                      <th>{t('dayOverview.register')}</th>
                      <th>{t('dayOverview.cashier')}</th>
                      <th>{t('dayOverview.openedAt')}</th>
                      <th>{t('dayOverview.closedAt')}</th>
                      <th className="num">{t('dayOverview.float')}</th>
                      <th className="num">{t('dayOverview.cashSales')}</th>
                      <th className="num">{t('dayOverview.manualCard')}</th>
                      <th className="num">{t('dayOverview.expected')}</th>
                      <th className="num">{t('dayOverview.counted')}</th>
                      <th className="num">{t('dayOverview.difference')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {overview.shifts.map((s) => (
                      <tr key={s.id}>
                        <td>{name(s.registerName)}</td>
                        <td>{name(s.cashierName)}</td>
                        <td>{dateTime(s.openedAt)}</td>
                        <td>
                          {s.closedAt ? (
                            dateTime(s.closedAt)
                          ) : (
                            <span className="badge badge-warning">{t('dayOverview.stillOpen')}</span>
                          )}
                        </td>
                        <td className="num">{formatCents(s.openingFloatCents)}</td>
                        <td className="num">{formatCents(s.cashSalesCents)}</td>
                        <td className="num">{formatCents(s.manualCardCents)}</td>
                        <td className="num">{formatCents(s.expectedCashCents)}</td>
                        <td className="num">{money(s.countedCashCents)}</td>
                        <td className={s.differenceCents ? 'num warning-text' : 'num'}>
                          {money(s.differenceCents)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <p className="muted">{t('dayOverview.shiftsNote')}</p>

              <h2 className="section-title">{t('dayOverview.byCashier')}</h2>
              <div className="table-scroll">
                <table className="table" aria-label={t('dayOverview.byCashier')}>
                  <thead>
                    <tr>
                      <th>{t('dayOverview.cashier')}</th>
                      <th className="num">{t('dayOverview.shiftCount')}</th>
                      <th className="num">{t('dayOverview.paidOrders')}</th>
                      <th className="num">{t('dayOverview.voidedOrders')}</th>
                      <th className="num">{t('dayOverview.cashSales')}</th>
                      <th className="num">{t('dayOverview.manualCard')}</th>
                      <th className="num">{t('dayOverview.total')}</th>
                      <th className="num">{t('dayOverview.difference')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {overview.cashiers.map((c) => (
                      <tr key={c.cashierId}>
                        <td>{name(c.cashierName)}</td>
                        <td className="num">{c.shifts}</td>
                        <td className="num">{c.paidOrders}</td>
                        <td className="num">{c.voidedOrders}</td>
                        <td className="num">{formatCents(c.cashSalesCents)}</td>
                        <td className="num">{formatCents(c.manualCardCents)}</td>
                        <td className="num">
                          <strong>{formatCents(c.totalCents)}</strong>
                        </td>
                        <td className={c.differenceCents ? 'num warning-text' : 'num'}>
                          {money(c.differenceCents)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </>
      )}

      {showing && venueId && (
        <ReceiptPreviewDialog
          path={`/venues/${venueId}/orders/${showing.id}/receipt-preview`}
          number={showing.dailyNumber}
          voided={showing.status === 'VOIDED'}
          onClose={() => setShowing(null)}
        />
      )}
    </section>
  );
}
