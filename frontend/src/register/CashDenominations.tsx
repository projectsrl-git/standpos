import { useTranslation } from 'react-i18next';
import { CASH_DENOMINATIONS, type Denomination } from './cart';

/**
 * In-house stylised drawings of the euro coins and notes (colour and shape cues only, not reproductions of the real
 * designs): bigger coins for bigger values, bimetallic 1 € and 2 €, notes in their familiar colours and growing width.
 */
const COINS: Partial<
  Record<Denomination, { r: number; ring: string; centre: string; label: string; unit: string }>
> = {
  50: { r: 25, ring: '#c9962b', centre: '#e0b54a', label: '50', unit: 'c' },
  100: { r: 27, ring: '#d4a536', centre: '#c3c8ce', label: '1', unit: '€' },
  200: { r: 30, ring: '#c3c8ce', centre: '#d4a536', label: '2', unit: '€' },
};

const NOTES: Partial<Record<Denomination, { width: number; fill: string; band: string }>> = {
  500: { width: 84, fill: '#a7b0ad', band: '#7d8784' },
  1000: { width: 88, fill: '#e07a6b', band: '#b6493b' },
  2000: { width: 92, fill: '#6f9fd8', band: '#3d6fae' },
  5000: { width: 96, fill: '#f0a24a', band: '#c47318' },
};

function Coin({ cents }: { cents: Denomination }) {
  const coin = COINS[cents];
  if (!coin) return null;
  return (
    <svg
      className="denomination-art"
      viewBox="0 0 64 64"
      width="56"
      height="56"
      aria-hidden="true"
      focusable="false"
    >
      <circle cx="32" cy="32" r={coin.r} fill={coin.ring} stroke="#6b5417" strokeWidth="1.5" />
      <circle cx="32" cy="32" r={coin.r - 7} fill={coin.centre} stroke="#00000033" strokeWidth="1" />
      <text x="32" y="36" textAnchor="middle" fontSize="17" fontWeight="700" fill="#2b2410">
        {coin.label}
      </text>
      <text x="32" y="48" textAnchor="middle" fontSize="9" fontWeight="700" fill="#2b2410">
        {coin.unit}
      </text>
    </svg>
  );
}

function Note({ cents }: { cents: Denomination }) {
  const note = NOTES[cents];
  if (!note) return null;
  const euros = String(cents / 100);
  return (
    <svg
      className="denomination-art"
      viewBox={`0 0 ${note.width} 52`}
      width={note.width}
      height="52"
      aria-hidden="true"
      focusable="false"
    >
      <rect x="1" y="1" width={note.width - 2} height="50" rx="4" fill={note.fill} stroke={note.band} />
      <rect x="1" y="1" width="16" height="50" rx="4" fill={note.band} />
      <circle cx="34" cy="26" r="11" fill="none" stroke="#ffffff99" strokeWidth="3" />
      <text x={note.width - 8} y="34" textAnchor="end" fontSize="22" fontWeight="800" fill="#1b2430">
        {euros}
      </text>
      <text x={note.width - 8} y="46" textAnchor="end" fontSize="9" fontWeight="700" fill="#1b2430">
        €
      </text>
    </svg>
  );
}

/** One button per coin and note, coins on a row and notes on the next; each tap adds that value. */
export function CashDenominations({ onAdd }: { onAdd: (cents: Denomination) => void }) {
  const { t } = useTranslation();
  const button = (cents: Denomination) => (
    <button
      key={cents}
      type="button"
      className="denomination"
      aria-label={
        cents < 100 ? t('payment.coinCents', { amount: cents }) : t('payment.euro', { amount: cents / 100 })
      }
      onClick={() => onAdd(cents)}
    >
      {cents < 500 ? <Coin cents={cents} /> : <Note cents={cents} />}
    </button>
  );
  return (
    <div className="denominations" role="group" aria-label={t('payment.denominations')}>
      <div className="denomination-row">{CASH_DENOMINATIONS.filter((c) => c < 500).map(button)}</div>
      <div className="denomination-row">{CASH_DENOMINATIONS.filter((c) => c >= 500).map(button)}</div>
    </div>
  );
}
