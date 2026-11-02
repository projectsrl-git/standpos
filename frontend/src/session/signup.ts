import { useEffect, useState } from 'react';
import { api } from '../api/client';

export interface SignupAvailability {
  enabled: boolean;
  declarationsVersion: string;
}

/**
 * Whether this server accepts self-service sign-ups: null while asking, then true or false. The endpoint exists only
 * when sign-up is enabled, so any error means "not available".
 */
export function useSignupAvailable(): boolean | null {
  const [available, setAvailable] = useState<boolean | null>(null);
  useEffect(() => {
    let active = true;
    api.get<SignupAvailability>('/auth/signup').then(
      (a) => {
        if (active) setAvailable(a.enabled);
      },
      () => {
        if (active) setAvailable(false);
      },
    );
    return () => {
      active = false;
    };
  }, []);
  return available;
}
