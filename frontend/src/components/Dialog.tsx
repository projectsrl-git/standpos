import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

/** A modal panel over the register or the manager area. */
export function Dialog({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
}) {
  const { t } = useTranslation();
  return (
    <div className="modal-backdrop">
      <section className="modal card" role="dialog" aria-modal="true" aria-label={title}>
        <header className="modal-header">
          <h2>{title}</h2>
          <button type="button" className="btn btn-small" aria-label={t('common.close')} onClick={onClose}>
            {t('common.close')}
          </button>
        </header>
        {children}
      </section>
    </div>
  );
}
