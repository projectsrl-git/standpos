import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import type { GoodsReceipt, StockLevel, Supplier } from '../api/types';
import { ErrorBanner } from '../components/ErrorBanner';
import { centsToInput, formatCents, parseEuroToCents } from '../money';
import { useManager } from './context';
import { useList } from './useList';

interface LineDraft {
  productId: string;
  quantity: string;
  unitCost: string;
}

interface Draft {
  supplierId: string;
  documentRef: string;
  receivedOn: string;
  notes: string;
  lines: LineDraft[];
}

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * Goods receipts: a draft lists products, quantities and (optional) unit costs; "Registra carico" posts it, adding
 * the stock at that cost, and freezes it.
 */
export function GoodsReceiptsPage() {
  const { t } = useTranslation();
  const { venueId } = useManager();
  const base = venueId ? `/venues/${venueId}/goods-receipts` : null;
  const { items, error, setError, reload } = useList<GoodsReceipt>(base);
  const products = useList<StockLevel>(venueId ? `/venues/${venueId}/stock` : null);
  const suppliers = useList<Supplier>('/suppliers');
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [lineError, setLineError] = useState(false);

  const productName = (id: string) => products.items.find((p) => p.productId === id)?.productName ?? '';
  const supplierName = (id: string | null) => suppliers.items.find((s) => s.id === id)?.name ?? '';

  function newLine(): LineDraft {
    return { productId: products.items[0]?.productId ?? '', quantity: '1', unitCost: '' };
  }

  function edit(receipt: GoodsReceipt | null) {
    setEditingId(receipt?.id ?? null);
    setLineError(false);
    setDraft(
      receipt
        ? {
            supplierId: receipt.supplierId ?? '',
            documentRef: receipt.documentRef ?? '',
            receivedOn: receipt.receivedOn,
            notes: receipt.notes ?? '',
            lines: receipt.lines.map((l) => ({
              productId: l.productId,
              quantity: String(l.quantity),
              unitCost: l.unitCostCents === null ? '' : centsToInput(l.unitCostCents),
            })),
          }
        : { supplierId: '', documentRef: '', receivedOn: today(), notes: '', lines: [newLine()] },
    );
  }

  function body(d: Draft) {
    const lines = d.lines.map((l) => ({
      productId: l.productId,
      quantity: Number.parseInt(l.quantity, 10),
      unitCostCents: l.unitCost.trim() === '' ? null : parseEuroToCents(l.unitCost),
    }));
    const invalid = lines.some(
      (l, i) =>
        !l.productId || !(l.quantity > 0) || (d.lines[i]?.unitCost.trim() !== '' && l.unitCostCents === null),
    );
    return invalid
      ? null
      : {
          supplierId: d.supplierId || null,
          documentRef: d.documentRef || null,
          receivedOn: d.receivedOn,
          notes: d.notes || null,
          lines,
        };
  }

  async function save(post: boolean) {
    if (!base || !draft) return;
    const payload = body(draft);
    setLineError(payload === null);
    if (!payload) return;
    if (post && !window.confirm(t('receipts.confirmPost'))) return;
    try {
      const saved = editingId
        ? await api.put<GoodsReceipt>(`${base}/${editingId}`, payload)
        : await api.post<GoodsReceipt>(base, payload);
      if (post) {
        await api.post(`${base}/${saved.id}/post`);
      }
      setDraft(null);
      setEditingId(null);
      setError(null);
      await reload();
    } catch (e) {
      setError(e);
    }
  }

  async function remove(receipt: GoodsReceipt) {
    if (!base || !window.confirm(t('receipts.confirmDelete'))) return;
    try {
      await api.delete(`${base}/${receipt.id}`);
      await reload();
    } catch (e) {
      setError(e);
    }
  }

  function setLine(index: number, patch: Partial<LineDraft>) {
    if (!draft) return;
    setDraft({ ...draft, lines: draft.lines.map((l, i) => (i === index ? { ...l, ...patch } : l)) });
  }

  if (products.items.length === 0) {
    return (
      <section>
        <h1>{t('receipts.title')}</h1>
        <p>{t('receipts.needTracked')}</p>
      </section>
    );
  }

  return (
    <section>
      <h1>{t('receipts.title')}</h1>
      {!draft && (
        <button className="btn btn-primary" type="button" onClick={() => edit(null)}>
          {t('receipts.new')}
        </button>
      )}
      {draft && (
        <div className="card">
          <h2>{editingId ? t('receipts.editDraft') : t('receipts.new')}</h2>
          <div className="inline-form">
            <label className="field">
              <span>{t('receipts.supplier')}</span>
              <select
                value={draft.supplierId}
                onChange={(e) => setDraft({ ...draft, supplierId: e.target.value })}
              >
                <option value="">{t('common.none')}</option>
                {suppliers.items.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              <span>{t('receipts.documentRef')}</span>
              <input
                maxLength={60}
                value={draft.documentRef}
                onChange={(e) => setDraft({ ...draft, documentRef: e.target.value })}
              />
            </label>
            <label className="field">
              <span>{t('receipts.receivedOn')}</span>
              <input
                type="date"
                required
                value={draft.receivedOn}
                onChange={(e) => setDraft({ ...draft, receivedOn: e.target.value })}
              />
            </label>
          </div>
          <table className="table">
            <thead>
              <tr>
                <th>{t('stock.product')}</th>
                <th>{t('stock.quantity')}</th>
                <th>{t('receipts.unitCost')}</th>
                <th>{t('common.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {draft.lines.map((line, i) => (
                <tr key={i}>
                  <td>
                    <select
                      aria-label={t('stock.product')}
                      value={line.productId}
                      onChange={(e) => setLine(i, { productId: e.target.value })}
                    >
                      {products.items.map((p) => (
                        <option key={p.productId} value={p.productId}>
                          {p.productName}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td>
                    <input
                      aria-label={t('stock.quantity')}
                      type="number"
                      min={1}
                      value={line.quantity}
                      onChange={(e) => setLine(i, { quantity: e.target.value })}
                    />
                  </td>
                  <td>
                    <input
                      aria-label={t('receipts.unitCost')}
                      inputMode="decimal"
                      placeholder={t('products.pricePlaceholder')}
                      value={line.unitCost}
                      onChange={(e) => setLine(i, { unitCost: e.target.value })}
                    />
                  </td>
                  <td>
                    <button
                      className="btn btn-small btn-danger"
                      type="button"
                      onClick={() => setDraft({ ...draft, lines: draft.lines.filter((_, j) => j !== i) })}
                    >
                      {t('common.delete')}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {lineError && <p className="error">{t('receipts.invalidLines')}</p>}
          <div className="row-actions">
            <button
              className="btn"
              type="button"
              onClick={() => setDraft({ ...draft, lines: [...draft.lines, newLine()] })}
            >
              {t('receipts.addLine')}
            </button>
            <button className="btn" type="button" onClick={() => void save(false)}>
              {t('receipts.saveDraft')}
            </button>
            <button
              className="btn btn-primary"
              type="button"
              disabled={draft.lines.length === 0}
              onClick={() => void save(true)}
            >
              {t('receipts.post')}
            </button>
            <button className="btn" type="button" onClick={() => setDraft(null)}>
              {t('common.cancel')}
            </button>
          </div>
        </div>
      )}
      <ErrorBanner error={error} />
      <table className="table">
        <thead>
          <tr>
            <th>{t('receipts.receivedOn')}</th>
            <th>{t('receipts.supplier')}</th>
            <th>{t('receipts.documentRef')}</th>
            <th>{t('receipts.lines')}</th>
            <th>{t('receipts.totalCost')}</th>
            <th>{t('receipts.status')}</th>
            <th>{t('common.actions')}</th>
          </tr>
        </thead>
        <tbody>
          {items.map((r) => (
            <tr key={r.id}>
              <td>{r.receivedOn}</td>
              <td>{supplierName(r.supplierId)}</td>
              <td>{r.documentRef}</td>
              <td>
                {r.lines
                  .map((l) =>
                    t('receipts.lineSummary', { quantity: l.quantity, name: productName(l.productId) }),
                  )
                  .join(', ')}
              </td>
              <td>{formatCents(r.totalCostCents)}</td>
              <td>{t(`receipts.statuses.${r.status}`)}</td>
              <td className="row-actions">
                {r.status === 'DRAFT' && (
                  <>
                    <button className="btn btn-small" type="button" onClick={() => edit(r)}>
                      {t('common.edit')}
                    </button>
                    <button className="btn btn-small btn-danger" type="button" onClick={() => void remove(r)}>
                      {t('common.delete')}
                    </button>
                  </>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {items.length === 0 && <p>{t('common.empty')}</p>}
    </section>
  );
}
