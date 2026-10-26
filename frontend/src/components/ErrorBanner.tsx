import { useTranslation } from 'react-i18next';
import { errorMessage } from '../i18n/errors';

export function ErrorBanner({ error }: { error: unknown }) {
  const { t } = useTranslation();
  if (!error) {
    return null;
  }
  return (
    <p className="error" role="alert">
      {errorMessage(t, error)}
    </p>
  );
}
