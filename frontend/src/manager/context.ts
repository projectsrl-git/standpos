import { useOutletContext } from 'react-router';
import type { Venue } from '../api/types';

export interface ManagerContext {
  venueId: string | null;
  venues: Venue[];
  /** False until the first list of venues has arrived. */
  venuesLoaded: boolean;
  reloadVenues: () => Promise<void>;
}

export function useManager(): ManagerContext {
  return useOutletContext<ManagerContext>();
}
