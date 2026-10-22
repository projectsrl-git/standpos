import { useOutletContext } from 'react-router';
import type { Venue } from '../api/types';

export interface ManagerContext {
  venueId: string | null;
  venues: Venue[];
  reloadVenues: () => Promise<void>;
}

export function useManager(): ManagerContext {
  return useOutletContext<ManagerContext>();
}
