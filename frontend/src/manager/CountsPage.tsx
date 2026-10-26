import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import type { StockCount, StockLevel } from '../api/types';
import { ErrorBanner } from '../components/ErrorBanner';
import { useManager } from './context';
import { useList } from './useList';

/**
 * Stock counts: a session lists every tracked product with the quantity expected; the counted quantities are entered
 * and posting writes a count movement for each difference. Products left blank are not changed.
 */
export function CountsPage() {
  const { t } = useTranslation();
  const { venueId } = useManager();
  const base = venueId ? `/venues/${venueId}/stock-counts` : null;
  const { items, error, setError, reload } = useList<StockCount>(base);
  const products = useList<StockLevel>(venueId ? `/venues/${venueId}/stock` : null);
  const [open, setOpen] = useState<StockCount | null>(null);
  const [counted, setCounted] = useState<Record<string, string>>({});

  const productName = (id: string) => products.items.find((p) => p.productId === id)?.productName ?? '';

  function show(count: StockCount) {
    setOpen(count);
    setCounted(
      Object.fromEntries(count.lines.map((l) => [l.productId, l.counted === null ? '' : String(l.counted)])),
    );
  }

  async function run(action: () => Promise<StockCount | void>) {
    try {
      const result = await action();
      if (result) show(result);
      setError(null);
      await reload();
    } catch (e) {
      setError(e);
    }
  }

  function quantities(count: StockCount) {
    return count.lines.map((l) => {
      const value = counted[l.productId] ?? '';
      return { productId: l.productId, counted: value.trim() === '' ? null : Number.parseInt(value, 10) };
    });
  }

  return (
    <section>
      <h1>{t('counts.title')}</h1>
      {!open && (
        <button
          className="btn btn-primary"
          type="button"
          onClick={() => void run(() => api.post<StockCount>(base ?? '', { notes: null }))}
        >
          {t('counts.new')}
        </button>
      )}
      <ErrorBanner error={error} />
      {open && (
        <div className="card">
          <h2>{t('counts.session', { date: new Date(open.createdAt).toLocaleString('it-IT') })}</h2>
          <table className="table">
            <thead>
              <tr>
                <th>{t('stock.product')}</th>
                <th>{t('counts.expected')}</th>
                <th>{t('counts.counted')}</th>
                <th>{t('counts.difference')}</th>
              </tr>
            </thead>
            <tbody>
              {open.lines.map((l) => (
                <tr key={l.productId}>
                  <td>{productName(l.productId)}</td>
                  <td>{l.expected}</td>
                  <td>
                    {open.status === 'DRAFT' ? (
                      <input
                        aria-label={t('counts.countedOf', { name: productName(l.productId) })}
                        type="number"
                        min={0}
                        value={counted[l.productId] ?? ''}
                        onChange={(e) => setCounted({ ...counted, [l.productId]: e.target.value })}
                      />
                    ) : (
                      (l.counted ?? '')
                    )}
                  </td>
                  <td>{l.difference ?? ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="row-actions">
            {open.status === 'DRAFT' && (
              <>
                <button
                  className="btn"
                  type="button"
                  onClick={() =>
                    void run(() => api.put<StockCount>(`${base}/${open.id}`, { lines: quantities(open) }))
                  }
                >
                  {t('counts.save')}
                </button>
                <button
                  className="btn btn-primary"
                  type="button"
                  onClick={() => {
                    if (!window.confirm(t('counts.confirmPost'))) return;
                    void run(async () => {
                      await api.put<StockCount>(`${base}/${open.id}`, { lines: quantities(open) });
                      return api.post<StockCount>(`${base}/${open.id}/post`);
                    });
                  }}
                >
                  {t('counts.post')}
                </button>
                <button
                  className="btn btn-danger"
                  type="button"
                  onClick={() => {
                    if (!window.confirm(t('counts.confirmDelete'))) return;
                    void run(async () => {
                      await api.delete(`${base}/${open.id}`);
                      setOpen(null);
                    });
                  }}
                >
                  {t('common.delete')}
                </button>
              </>
            )}
            <button className="btn" type="button" onClick={() => setOpen(null)}>
              {t('common.back')}
            </button>
          </div>
        </div>
      )}
      {!open && (
        <table className="table">
          <thead>
            <tr>
              <th>{t('counts.created')}</th>
              <th>{t('receipts.status')}</th>
              <th>{t('common.actions')}</th>
            </tr>
          </thead>
          <tbody>
            {items.map((c) => (
              <tr key={c.id}>
                <td>
                  {new Date(c.createdAt).toLocaleString('it-IT', { dateStyle: 'short', timeStyle: 'short' })}
                </td>
                <td>{t(`receipts.statuses.${c.status}`)}</td>
                <td>
                  <button
                    className="btn btn-small"
                    type="button"
                    onClick={() => void run(() => api.get<StockCount>(`${base}/${c.id}`))}
                  >
                    {c.status === 'DRAFT' ? t('counts.continue') : t('counts.view')}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
