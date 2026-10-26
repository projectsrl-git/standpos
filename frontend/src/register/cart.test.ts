import { describe, expect, it } from 'vitest';
import {
  addProduct,
  cartTotal,
  changeFor,
  quickTenders,
  setNote,
  setQuantity,
  toOrderLines,
  type CartLine,
} from './cart';

const panino = { id: 'p1', name: 'Panino', priceCents: 450 };
const acqua = { id: 'p2', name: 'Acqua', priceCents: 100 };

describe('cart', () => {
  it('adds taps of the same product to one line and totals in cents', () => {
    let cart: CartLine[] = [];
    cart = addProduct(cart, panino);
    cart = addProduct(cart, panino);
    cart = addProduct(cart, acqua);
    expect(cart).toHaveLength(2);
    expect(cart[0]?.quantity).toBe(2);
    expect(cartTotal(cart)).toBe(1000);
  });

  it('keeps a line with a note apart from new taps', () => {
    let cart = addProduct([], panino);
    cart = setNote(cart, cart[0]!.key, 'senza cipolla');
    cart = addProduct(cart, panino);
    expect(cart).toHaveLength(2);
    expect(toOrderLines(cart)).toEqual([
      { productId: 'p1', quantity: 1, note: 'senza cipolla' },
      { productId: 'p1', quantity: 1, note: null },
    ]);
  });

  it('removes a line when its quantity goes to zero', () => {
    const cart = addProduct([], panino);
    expect(setQuantity(cart, cart[0]!.key, 0)).toEqual([]);
    expect(setQuantity(cart, cart[0]!.key, 5000)[0]?.quantity).toBe(999);
  });

  it('computes the change and the quick cash amounts', () => {
    expect(changeFor(1020, 2000)).toBe(980);
    expect(changeFor(1020, 1000)).toBeNull();
    expect(changeFor(450, 450)).toBe(0);
    expect(quickTenders(1020)).toEqual([1020, 2000, 5000, 10000]);
    expect(quickTenders(300)).toEqual([300, 500, 1000, 2000]);
  });
});
