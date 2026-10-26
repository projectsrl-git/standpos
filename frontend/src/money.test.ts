import { describe, expect, it } from 'vitest';
import { centsToInput, formatCents, parseEuroToCents } from './money';

describe('money', () => {
  it('formats cents as euros in Italian', () => {
    expect(formatCents(450)).toBe('4,50 €');
    expect(formatCents(1234567)).toMatch(/^12\.345,67\s€$/);
    expect(formatCents(-50)).toBe('-0,50 €');
  });

  it('parses typed prices into integer cents', () => {
    expect(parseEuroToCents('4,50')).toBe(450);
    expect(parseEuroToCents('4.5')).toBe(450);
    expect(parseEuroToCents('4')).toBe(400);
    expect(parseEuroToCents(' 0,05 ')).toBe(5);
    expect(parseEuroToCents('0.1')).toBe(10);
  });

  it('rejects anything that is not a price', () => {
    expect(parseEuroToCents('')).toBeNull();
    expect(parseEuroToCents('4,505')).toBeNull();
    expect(parseEuroToCents('-1')).toBeNull();
    expect(parseEuroToCents('abc')).toBeNull();
  });

  it('round-trips through the edit field', () => {
    expect(centsToInput(450)).toBe('4,50');
    expect(parseEuroToCents(centsToInput(1999))).toBe(1999);
  });
});
