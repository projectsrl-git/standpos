import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { PaymentMethod } from '../api/types';
import { Dialog } from '../components/Dialog';
import { ErrorBanner } from '../components/ErrorBanner';
import { centsToInput, formatCents } from '../money';
import { changeFor, quickTenders } from './cart';
import { MoneyInput } from './MoneyInput';

export interface Checkout {
  method: PaymentMethod;
  tenderedCents: number | null;
  printReceipt: boolean;
}

/**
 * Payment: cash with the amount tendered and the change, or manual card (a terminal StandPOS does not drive,
 * recorded for reconciliation). "Conferma" prints the courtesy receipt; "Senza scontrino" prints nothing.
 */
export function PaymentDialog({
  method,
  totalCents,
  busy,
  error,
  onConfirm,
  onClose,
}: {
  method: PaymentMethod;
  totalCents: number;
  busy: boolean;
  error: unknown;
  onConfirm: (checkout: Checkout) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const [text, setText] = useState('');
  const [tendered, setTendered] = useState<number | null>(null);
  const cash = method === 'CASH';
  const effective = tendered ?? totalCents;
  const change = changeFor(totalCents, effective);
  const canConfirm = !busy && (!cash || change !== null);

  function confirm(printReceipt: boolean) {
    onConfirm({ method, tenderedCents: cash ? effective : null, printReceipt });
  }

  return (
    <Dialog title={cash ? t('payment.cashTitle') : t('payment.cardTitle')} onClose={onClose}>
      <p className="total-line">
        {t('payment.total')} <strong>{formatCents(totalCents)}</strong>
      </p>
      {cash ? (
        <>
          <div className="quick-tenders">
            {quickTenders(totalCents).map((amount, i) => (
              <button
                key={amount}
                type="button"
                className="btn"
                onClick={() => {
                  setTendered(amount);
                  setText(centsToInput(amount));
                }}
              >
                {i === 0 ? t('payment.exact') : formatCents(amount)}
              </button>
            ))}
          </div>
          <MoneyInput
            label={t('payment.tendered')}
            text={text}
            cents={tendered}
            onChange={(value, parsed) => {
              setText(value);
              setTendered(value === '' ? null : parsed);
            }}
          />
          <p className="change-line" role="status">
            {change === null ? t('payment.notEnough') : t('payment.change', { amount: formatCents(change) })}
          </p>
        </>
      ) : (
        <p className="notice">{t('payment.manualCardHint')}</p>
      )}
      <ErrorBanner error={error} />
      <div className="dialog-actions">
        <button
          className="btn btn-primary btn-large"
          type="button"
          disabled={!canConfirm}
          onClick={() => confirm(true)}
        >
          {t('payment.confirm')}
        </button>
        <button className="btn btn-large" type="button" disabled={!canConfirm} onClick={() => confirm(false)}>
          {t('payment.noReceipt')}
        </button>
      </div>
    </Dialog>
  );
}
