import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import type { MovementType, StockLevel, StockMovement } from '../api/types';
import { ErrorBanner } from '../components/ErrorBanner';
import { formatCents } from '../money';
import { useSession } from '../session/SessionContext';
import { canManageVenue } from '../session/roles';
import { useManager } from './context';
import { useList } from './useList';

/**
 * Stock of the venue: quantities and value, the low-stock list, "Esaurito" by hand, adjustments and waste with a
 * mandatory reason, and each product's movements.
 */
export function StockPage() {
  const { t } = useTranslation();
  const { me } = useSession();
  const { venueId } = useManager();
  const base = venueId ? `/venues/${venueId}` : null;
  const [lowOnly, setLowOnly] = useState(false);
  const { items, error, setError, reload } = useList<StockLevel>(
    base ? `${base}/stock${lowOnly ? '/low' : ''}` : null,
  );
  const [productId, setProductId] = useState('');
  const [type, setType] = useState<Extract<MovementType, 'ADJUSTMENT' | 'WASTE'>>('WASTE');
  const [quantity, setQuantity] = useState('1');
  const [reason, setReason] = useState('');
  const [movementsOf, setMovementsOf] = useState<StockLevel | null>(null);
  const [movements, setMovements] = useState<StockMovement[]>([]);
  const manager = canManageVenue(me, venueId);

  async function adjust(event: FormEvent) {
    event.preventDefault();
    if (!base) return;
    try {
      await api.post(`${base}/stock/adjustments`, {
        productId: productId || items[0]?.productId,
        type,
        quantity: Number.parseInt(quantity, 10),
        reason,
      });
      setReason('');
      setError(null);
      await reload();
    } catch (e) {
      setError(e);
    }
  }

  async function toggleSoldOut(level: StockLevel) {
    if (!base) return;
    try {
      await api.put(`${base}/products/${level.productId}/sold-out`, { soldOut: !level.manualSoldOut });
      await reload();
    } catch (e) {
      setError(e);
    }
  }

  async function showMovements(level: StockLevel) {
    if (!base) return;
    try {
      setMovements(
        await api.get<StockMovement[]>(`${base}/stock/movements?productId=${level.productId}&limit=50`),
      );
      setMovementsOf(level);
    } catch (e) {
      setError(e);
    }
  }

  const time = (iso: string) =>
    new Date(iso).toLocaleString('it-IT', { dateStyle: 'short', timeStyle: 'short' });

  return (
    <section>
      <h1>{t('stock.title')}</h1>
      <form className="card inline-form" onSubmit={(e) => void adjust(e)}>
        <h2>{t('stock.adjustTitle')}</h2>
        <label className="field">
          <span>{t('stock.product')}</span>
          <select
            value={productId || items[0]?.productId || ''}
            onChange={(e) => setProductId(e.target.value)}
          >
            {items.map((l) => (
              <option key={l.productId} value={l.productId}>
                {l.productName}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>{t('stock.type')}</span>
          <select value={type} onChange={(e) => setType(e.target.value as 'ADJUSTMENT' | 'WASTE')}>
            <option value="WASTE">{t('stock.movementTypes.WASTE')}</option>
            <option value="ADJUSTMENT">{t('stock.movementTypes.ADJUSTMENT')}</option>
          </select>
        </label>
        <label className="field small">
          <span>{type === 'WASTE' ? t('stock.quantityWaste') : t('stock.quantitySigned')}</span>
          <input type="number" required value={quantity} onChange={(e) => setQuantity(e.target.value)} />
        </label>
        <label className="field">
          <span>{t('stock.reason')}</span>
          <input required maxLength={200} value={reason} onChange={(e) => setReason(e.target.value)} />
        </label>
        <button className="btn btn-primary" type="submit" disabled={items.length === 0}>
          {t('common.save')}
        </button>
      </form>
      <label className="check">
        <input type="checkbox" checked={lowOnly} onChange={(e) => setLowOnly(e.target.checked)} />
        <span>{t('stock.lowOnly')}</span>
      </label>
      <ErrorBanner error={error} />
      <table className="table">
        <thead>
          <tr>
            <th>{t('stock.product')}</th>
            <th>{t('stock.onHand')}</th>
            <th>{t('stock.lowThreshold')}</th>
            <th>{t('stock.value')}</th>
            <th>{t('stock.averageCost')}</th>
            <th>{t('stock.state')}</th>
            <th>{t('common.actions')}</th>
          </tr>
        </thead>
        <tbody>
          {items.map((l) => (
            <tr key={l.productId} className={l.active ? undefined : 'muted'}>
              <td>{l.productName}</td>
              <td>{l.onHand}</td>
              <td>{l.lowThreshold ?? ''}</td>
              <td>{formatCents(l.valueCents)}</td>
              <td>{formatCents(l.averageCostCents)}</td>
              <td>
                {l.soldOut ? (
                  <span className="badge badge-danger">
                    {l.manualSoldOut ? t('stock.soldOutManual') : t('stock.soldOut')}
                  </span>
                ) : l.low ? (
                  <span className="badge badge-warning">{t('stock.low')}</span>
                ) : (
                  <span className="badge">{t('stock.ok')}</span>
                )}
              </td>
              <td className="row-actions">
                {manager && (
                  <button className="btn btn-small" type="button" onClick={() => void toggleSoldOut(l)}>
                    {l.manualSoldOut ? t('stock.backOnSale') : t('stock.markSoldOut')}
                  </button>
                )}
                <button className="btn btn-small" type="button" onClick={() => void showMovements(l)}>
                  {t('stock.movements')}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {items.length === 0 && <p>{lowOnly ? t('stock.noneLow') : t('stock.noneTracked')}</p>}
      {movementsOf && (
        <section className="card">
          <h2>{t('stock.movementsOf', { name: movementsOf.productName })}</h2>
          <table className="table">
            <thead>
              <tr>
                <th>{t('stock.when')}</th>
                <th>{t('stock.type')}</th>
                <th>{t('stock.quantity')}</th>
                <th>{t('stock.value')}</th>
                <th>{t('stock.after')}</th>
                <th>{t('stock.note')}</th>
              </tr>
            </thead>
            <tbody>
              {movements.map((m) => (
                <tr key={m.id}>
                  <td>{time(m.createdAt)}</td>
                  <td>{t(`stock.movementTypes.${m.type}`)}</td>
                  <td>{m.quantity > 0 ? `+${m.quantity}` : m.quantity}</td>
                  <td>{formatCents(m.valueCents)}</td>
                  <td>{m.onHandAfter}</td>
                  <td>{m.note ?? ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </section>
  );
}
