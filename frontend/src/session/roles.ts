import type { Me } from '../api/types';

export function isOwner(me: Me | null): boolean {
  return me?.kind === 'USER' && me.memberships.some((m) => m.role === 'OWNER');
}
