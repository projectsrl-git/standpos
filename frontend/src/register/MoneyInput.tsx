import { centsToInput, parseEuroToCents } from '../money';

/** A euro amount typed as "4,50"; reports integer cents, or null while the text is not a valid amount. */
export function MoneyInput({
  label,
  cents,
  text,
  onChange,
  autoFocus,
}: {
  label: string;
  cents: number | null;
  text: string;
  onChange: (text: string, cents: number | null) => void;
  autoFocus?: boolean;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      <input
        inputMode="decimal"
        autoFocus={autoFocus}
        aria-invalid={text !== '' && cents === null}
        value={text}
        placeholder={centsToInput(0)}
        onChange={(e) => onChange(e.target.value, parseEuroToCents(e.target.value))}
      />
    </label>
  );
}
