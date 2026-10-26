import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import type { Cashier, Order, VoidDisposition } from '../api/types';
import { Dialog } from '../components/Dialog';
import { ErrorBanner } from '../components/ErrorBanner';
import { formatCents } from '../money';

const DIGITS = ['1', '2', '3', '4', '5', '6', '7', '8', '9'];

/**
 * Void of a paid order: a manager (or the owner) picks their name and types their PIN on the register; the cashier
 * stays logged in. Each line goes back to stock or to waste.
 */
export function VoidDialog({
  order,
  onClose,
  onVoided,
}: {
  order: Order;
  onClose: () => void;
  onVoided: (order: Order) => void;
}) {
  const { t } = useTranslation();
  const [approvers, setApprovers] = useState<Cashier[]>([]);
  const [managerId, setManagerId] = useState<string>('');
  const [pin, setPin] = useState('');
  const [reason, setReason] = useState('');
  const [dispositions, setDispositions] = useState<Record<string, VoidDisposition>>(() =>
    Object.fromEntries(order.lines.map((l) => [l.id, 'RETURN_TO_STOCK' as VoidDisposition])),
  );
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.get<Cashier[]>('/register/approvers').then(setApprovers, setError);
  }, []);

  async function confirm() {
    setBusy(true);
    try {
      onVoided(
        await api.post<Order>(`/register/orders/${order.id}/void`, {
          managerId,
          pin,
          reason,
          lines: order.lines.map((l) => ({ lineId: l.id, disposition: dispositions[l.id] })),
        }),
      );
    } catch (e) {
      setError(e);
      setPin('');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog title={t('void.title', { number: order.dailyNumber })} onClose={onClose}>
      <ul className="void-lines">
        {order.lines.map((l) => (
          <li key={l.id}>
            <span>
              {t('void.line', { quantity: l.quantity, name: l.name, amount: formatCents(l.lineTotalCents) })}
            </span>
            <span className="segmented" role="group" aria-label={l.name}>
              {(['RETURN_TO_STOCK', 'WASTE'] as VoidDisposition[]).map((d) => (
                <button
                  key={d}
                  type="button"
                  className={dispositions[l.id] === d ? 'btn btn-small selected' : 'btn btn-small'}
                  aria-pressed={dispositions[l.id] === d}
                  onClick={() => setDispositions({ ...dispositions, [l.id]: d })}
                >
                  {t(`void.dispositions.${d}`)}
                </button>
              ))}
            </span>
          </li>
        ))}
      </ul>
      <label className="field">
        <span>{t('void.reason')}</span>
        <input value={reason} maxLength={200} onChange={(e) => setReason(e.target.value)} />
      </label>
      <label className="field">
        <span>{t('void.manager')}</span>
        <select value={managerId} onChange={(e) => setManagerId(e.target.value)}>
          <option value="">{t('void.chooseManager')}</option>
          {approvers.map((a) => (
            <option key={a.userId} value={a.userId}>
              {a.displayName}
            </option>
          ))}
        </select>
      </label>
      <output className="pin-dots" aria-label={t('void.pin')}>
        {'●'.repeat(pin.length) || ' '}
      </output>
      <div className="keypad compact">
        {DIGITS.map((d) => (
          <button
            key={d}
            type="button"
            className="key"
            onClick={() => setPin((p) => (p.length < 6 ? p + d : p))}
          >
            {d}
          </button>
        ))}
        <button type="button" className="key" onClick={() => setPin((p) => p.slice(0, -1))}>
          {t('pin.delete')}
        </button>
        <button type="button" className="key" onClick={() => setPin((p) => (p.length < 6 ? `${p}0` : p))}>
          0
        </button>
        <button
          type="button"
          className="key key-ok"
          disabled={busy || !managerId || pin.length < 4 || reason.trim() === ''}
          onClick={() => void confirm()}
        >
          {t('void.confirm')}
        </button>
      </div>
      <ErrorBanner error={error} />
    </Dialog>
  );
}
