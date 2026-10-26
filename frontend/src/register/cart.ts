/**
 * The register's cart, kept in the browser until the order is confirmed (ADR 0001). Pure functions over an
 * immutable list, all amounts in integer cents.
 */

export interface CartLine {
  key: string;
  productId: string;
  name: string;
  unitPriceCents: number;
  quantity: number;
  note: string;
}

export const MAX_QUANTITY = 999;

let counter = 0;

function nextKey(): string {
  counter += 1;
  return `line-${counter}`;
}

/** A tap on a product: one more on its line without a note, or a new line. */
export function addProduct(
  cart: CartLine[],
  product: { id: string; name: string; priceCents: number },
): CartLine[] {
  const index = cart.findIndex((l) => l.productId === product.id && l.note === '');
  if (index >= 0) {
    return cart.map((l, i) => (i === index ? { ...l, quantity: Math.min(MAX_QUANTITY, l.quantity + 1) } : l));
  }
  return [
    ...cart,
    {
      key: nextKey(),
      productId: product.id,
      name: product.name,
      unitPriceCents: product.priceCents,
      quantity: 1,
      note: '',
    },
  ];
}

/** Sets a line's quantity; zero or less removes the line. */
export function setQuantity(cart: CartLine[], key: string, quantity: number): CartLine[] {
  if (quantity <= 0) {
    return removeLine(cart, key);
  }
  return cart.map((l) => (l.key === key ? { ...l, quantity: Math.min(MAX_QUANTITY, quantity) } : l));
}

export function setNote(cart: CartLine[], key: string, note: string): CartLine[] {
  return cart.map((l) => (l.key === key ? { ...l, note: note.slice(0, 120) } : l));
}

export function removeLine(cart: CartLine[], key: string): CartLine[] {
  return cart.filter((l) => l.key !== key);
}

export function lineTotal(line: CartLine): number {
  return line.unitPriceCents * line.quantity;
}

export function cartTotal(cart: CartLine[]): number {
  return cart.reduce((sum, l) => sum + lineTotal(l), 0);
}

/** Change to give back, or null when the amount tendered does not cover the total. */
export function changeFor(totalCents: number, tenderedCents: number): number | null {
  return tenderedCents >= totalCents ? tenderedCents - totalCents : null;
}

/** Quick amounts for cash: exact, then the next round notes above the total. */
export function quickTenders(totalCents: number): number[] {
  const notes = [500, 1000, 2000, 5000, 10000];
  const above = notes.filter((n) => n > totalCents).slice(0, 3);
  return [totalCents, ...above];
}

/** The order lines as the API expects them. */
export function toOrderLines(cart: CartLine[]) {
  return cart.map((l) => ({
    productId: l.productId,
    quantity: l.quantity,
    note: l.note.trim() === '' ? null : l.note.trim(),
  }));
}
