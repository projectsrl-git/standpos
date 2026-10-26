import type { Me, Role } from '../api/types';

export function isOwner(me: Me | null): boolean {
  return me?.kind === 'USER' && me.memberships.some((m) => m.role === 'OWNER');
}

function holds(me: Me | null, venueId: string | null, roles: Role[]): boolean {
  return (
    me?.kind === 'USER' &&
    me.memberships.some((m) => m.role === 'OWNER' || (m.venueId === venueId && roles.includes(m.role)))
  );
}

/** Owner, or manager of the venue: catalogue, devices, printers, sold-out flags, PIN unlock. */
export function canManageVenue(me: Me | null, venueId: string | null): boolean {
  return holds(me, venueId, ['MANAGER']);
}

/** Owner, manager or stock clerk of the venue: goods receipts, counts, adjustments. */
export function canManageStock(me: Me | null, venueId: string | null): boolean {
  return holds(me, venueId, ['MANAGER', 'STOCK_CLERK']);
}
