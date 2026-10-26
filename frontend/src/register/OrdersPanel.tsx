import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import type { Order } from '../api/types';
import { Dialog } from '../components/Dialog';
import { ErrorBanner } from '../components/ErrorBanner';
import { formatCents } from '../money';
import { VoidDialog } from './VoidDialog';

/** Today's orders of the venue: receipt state, "Ristampa" (or a late receipt) and voids with a manager PIN. */
export function OrdersPanel({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const [orders, setOrders] = useState<Order[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [voiding, setVoiding] = useState<Order | null>(null);

  useEffect(() => {
    let active = true;
    api.get<Order[]>('/register/orders').then(
      (list) => {
        if (active) setOrders(list);
      },
      (e: unknown) => {
        if (active) setError(e);
      },
    );
    return () => {
      active = false;
    };
  }, []);

  function replace(order: Order) {
    setOrders((list) => list.map((o) => (o.id === order.id ? order : o)));
  }

  async function print(order: Order) {
    try {
      replace(await api.post<Order>(`/register/orders/${order.id}/receipt`));
      setError(null);
    } catch (e) {
      setError(e);
    }
  }

  const time = (iso: string) => new Date(iso).toLocaleTimeString('it-IT', { timeStyle: 'short' });

  return (
    <Dialog title={t('orders.title')} onClose={onClose}>
      <ErrorBanner error={error} />
      {orders.length === 0 && <p>{t('orders.empty')}</p>}
      <ul className="order-list">
        {orders.map((o) => (
          <li key={o.id} className={o.status === 'VOIDED' ? 'muted' : undefined}>
            <span className="order-number">{t('orders.number', { number: o.dailyNumber })}</span>
            <span>{time(o.createdAt)}</span>
            <span>{formatCents(o.totalCents)}</span>
            <span>{o.payment ? t(`payment.methods.${o.payment.method}`) : ''}</span>
            <span>{o.status === 'VOIDED' ? t('orders.voided') : t(`orders.receipt.${o.receipt}`)}</span>
            <span className="row-actions">
              {o.status === 'PAID' && (
                <>
                  <button className="btn btn-small" type="button" onClick={() => void print(o)}>
                    {o.receipt === 'NOT_REQUESTED' ? t('orders.printReceipt') : t('orders.reprint')}
                  </button>
                  <button className="btn btn-small btn-danger" type="button" onClick={() => setVoiding(o)}>
                    {t('orders.void')}
                  </button>
                </>
              )}
            </span>
          </li>
        ))}
      </ul>
      {voiding && (
        <VoidDialog
          order={voiding}
          onClose={() => setVoiding(null)}
          onVoided={(order) => {
            replace(order);
            setVoiding(null);
          }}
        />
      )}
    </Dialog>
  );
}
