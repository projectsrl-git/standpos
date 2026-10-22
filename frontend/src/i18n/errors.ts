import type { TFunction } from 'i18next';
import { ApiError } from '../api/client';

/** Translates an API error code (e.g. "auth.invalid_pin") into Italian, falling back to a generic message. */
export function errorMessage(t: TFunction, error: unknown): string {
  const code = error instanceof ApiError ? error.code : 'generic';
  return t(`errors.${code}`, { defaultValue: t('errors.generic') });
}
