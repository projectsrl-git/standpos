/** Money is integer euro cents end to end; these helpers never go through floating point. */

const formatter = new Intl.NumberFormat('it-IT', { style: 'currency', currency: 'EUR' });

function decimal(cents: number): `${number}` {
  const sign = cents < 0 ? '-' : '';
  const abs = Math.abs(cents);
  return `${sign}${Math.trunc(abs / 100)}.${String(abs % 100).padStart(2, '0')}` as `${number}`;
}

export function formatCents(cents: number): string {
  // Intl formats the exact decimal string; no binary floating point is involved.
  return formatter.format(decimal(cents));
}

/** Parses "4,50", "4.5" or "4" into cents; returns null for anything else. */
export function parseEuroToCents(input: string): number | null {
  const match = /^\s*(\d{1,6})(?:[.,](\d{1,2}))?\s*$/.exec(input);
  if (!match) {
    return null;
  }
  const euros = Number.parseInt(match[1] ?? '0', 10);
  const cents = Number.parseInt((match[2] ?? '0').padEnd(2, '0'), 10);
  return euros * 100 + cents;
}

/** Cents as an editable string: 450 → "4,50". */
export function centsToInput(cents: number): string {
  return decimal(cents).replace('.', ',');
}
