import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import type { Shift } from '../api/types';
import { Dialog } from '../components/Dialog';
import { ErrorBanner } from '../components/ErrorBanner';
import { formatCents } from '../money';
import { MoneyInput } from './MoneyInput';

/** Opening a shift: the cash float in the drawer. */
export function OpenShiftPanel({ onOpened }: { onOpened: (shift: Shift) => void }) {
  const { t } = useTranslation();
  const [text, setText] = useState('');
  const [cents, setCents] = useState<number | null>(0);
  const [error, setError] = useState<unknown>(null);

  async function open() {
    try {
      onOpened(await api.post<Shift>('/register/shift/open', { openingFloatCents: cents ?? 0 }));
    } catch (e) {
      setError(e);
    }
  }

  return (
    <section className="card narrow shift-panel">
      <h1>{t('shift.openTitle')}</h1>
      <p>{t('shift.openIntro')}</p>
      <MoneyInput
        label={t('shift.float')}
        text={text}
        cents={cents}
        autoFocus
        onChange={(value, parsed) => {
          setText(value);
          setCents(value === '' ? 0 : parsed);
        }}
      />
      <ErrorBanner error={error} />
      <button className="btn btn-primary" type="button" disabled={cents === null} onClick={() => void open()}>
        {t('shift.open')}
      </button>
    </section>
  );
}

/** Closing the shift: the cashier counts the drawer; the summary shows the expected cash and the difference. */
export function CloseShiftDialog({
  shift,
  onClose,
  onClosed,
}: {
  shift: Shift;
  onClose: () => void;
  onClosed: () => void;
}) {
  const { t } = useTranslation();
  const [text, setText] = useState('');
  const [cents, setCents] = useState<number | null>(null);
  const [closed, setClosed] = useState<Shift | null>(null);
  const [error, setError] = useState<unknown>(null);

  async function close() {
    if (cents === null) return;
    try {
      setClosed(await api.post<Shift>('/register/shift/close', { countedCashCents: cents }));
    } catch (e) {
      setError(e);
    }
  }

  const summary = closed ?? shift;
  return (
    <Dialog title={t('shift.closeTitle')} onClose={closed ? onClosed : onClose}>
      <dl className="summary">
        <dt>{t('shift.float')}</dt>
        <dd>{formatCents(summary.openingFloatCents)}</dd>
        <dt>{t('shift.cashSales')}</dt>
        <dd>{formatCents(summary.cashSalesCents)}</dd>
        <dt>{t('shift.manualCard')}</dt>
        <dd>{formatCents(summary.manualCardCents)}</dd>
        <dt>{t('shift.orders')}</dt>
        <dd>{t('shift.ordersCount', { paid: summary.paidOrders, voided: summary.voidedOrders })}</dd>
        <dt>{t('shift.expected')}</dt>
        <dd>
          <strong>{formatCents(summary.expectedCashCents)}</strong>
        </dd>
        {closed && closed.countedCashCents !== null && closed.differenceCents !== null && (
          <>
            <dt>{t('shift.counted')}</dt>
            <dd>{formatCents(closed.countedCashCents)}</dd>
            <dt>{t('shift.difference')}</dt>
            <dd className={closed.differenceCents === 0 ? undefined : 'warning-text'}>
              {formatCents(closed.differenceCents)}
            </dd>
          </>
        )}
      </dl>
      {closed ? (
        <button className="btn btn-primary" type="button" onClick={onClosed}>
          {t('shift.done')}
        </button>
      ) : (
        <>
          <MoneyInput
            label={t('shift.counted')}
            text={text}
            cents={cents}
            autoFocus
            onChange={(value, parsed) => {
              setText(value);
              setCents(parsed);
            }}
          />
          <ErrorBanner error={error} />
          <button
            className="btn btn-primary"
            type="button"
            disabled={cents === null}
            onClick={() => void close()}
          >
            {t('shift.close')}
          </button>
        </>
      )}
    </Dialog>
  );
}
