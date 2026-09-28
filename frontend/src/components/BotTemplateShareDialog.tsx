import { useEffect, useState } from 'react';
import Modal from './Modal';
import Alert from './Alert';
import Spinner from './Spinner';
import UserSearchInput from './UserSearchInput';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import { fetchGroups } from '../store/groupsSlice';
import { api, errorMessage } from '../api/client';
import { useI18n } from '../i18n';
import type { BotTemplateShareView, BotTemplateView, UserView } from '../types';

interface BotTemplateShareDialogProps {
  template: BotTemplateView;
  onClose: () => void;
}

/**
 * Bot-Vorlage an Nutzer oder Gruppen freigeben. Empfänger sehen die Vorlage
 * samt Zeitplan, starten daraus Bots und steuern deren laufende Bots; neue
 * Aufnahmen werden automatisch an sie freigegeben.
 */
export default function BotTemplateShareDialog({ template, onClose }: BotTemplateShareDialogProps) {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const groups = useAppSelector((s) => s.groups.items);
  const [shares, setShares] = useState<BotTemplateShareView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [selectedGroupId, setSelectedGroupId] = useState('');

  const base = `/api/bot-templates/${template.id}/shares`;

  useEffect(() => {
    void dispatch(fetchGroups());
    api<BotTemplateShareView[]>(base)
      .then(setShares)
      .catch((e) => setError(errorMessage(e)))
      .finally(() => setLoading(false));
  }, [dispatch, base]);

  const add = async (body: { userId?: string; groupId?: string }) => {
    setError(null);
    setBusy(true);
    try {
      const share = await api<BotTemplateShareView>(base, { method: 'POST', body });
      setShares((current) => [...current, share]);
      return true;
    } catch (e) {
      setError(errorMessage(e));
      return false;
    } finally {
      setBusy(false);
    }
  };

  const remove = async (shareId: string) => {
    setError(null);
    try {
      await api<void>(`${base}/${shareId}`, { method: 'DELETE' });
      setShares((current) => current.filter((s) => s.id !== shareId));
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const sharedGroupIds = shares.filter((s) => s.group).map((s) => s.group!.id);
  const availableGroups = groups.filter((g) => !sharedGroupIds.includes(g.id));

  return (
    <Modal
      title={t('botTemplates.share.title', { name: template.name })}
      onClose={onClose}
      wide
      footer={
        <button type="button" className="btn" onClick={onClose}>
          {t('common.close')}
        </button>
      }
    >
      <p className="muted">{t('botTemplates.share.intro')}</p>
      {error && <Alert kind="error">{error}</Alert>}

      <div className="form-field">
        <label>{t('share.withUser')}</label>
        <UserSearchInput
          onSelect={(user: UserView) => void add({ userId: user.id })}
          placeholder={t('share.userPlaceholder')}
          excludeIds={shares.filter((s) => s.user).map((s) => s.user!.id)}
        />
      </div>

      <div className="form-field">
        <label>{t('share.withGroup')}</label>
        <div className="inline-form">
          <select
            value={selectedGroupId}
            onChange={(e) => setSelectedGroupId(e.target.value)}
            disabled={availableGroups.length === 0}
          >
            <option value="">
              {availableGroups.length === 0 ? t('share.noGroupAvailable') : t('share.chooseGroup')}
            </option>
            {availableGroups.map((g) => (
              <option key={g.id} value={g.id}>
                {g.name}
              </option>
            ))}
          </select>
          <button
            type="button"
            className="btn btn-primary"
            disabled={!selectedGroupId || busy}
            onClick={() =>
              void add({ groupId: selectedGroupId }).then((ok) => ok && setSelectedGroupId(''))
            }
          >
            {t('share.submit')}
          </button>
        </div>
      </div>

      <h4 className="share-list-title">{t('share.existing')}</h4>
      {loading && <Spinner label={t('share.loading')} />}
      {!loading && shares.length === 0 && (
        <p className="muted">{t('botTemplates.share.empty')}</p>
      )}
      <ul className="share-list">
        {shares.map((share) => (
          <li key={share.id}>
            <span>
              {share.user
                ? `${share.user.displayName} (${share.user.username})`
                : t('share.groupPrefix', { name: share.group?.name ?? '–' })}
            </span>
            <button
              type="button"
              className="btn btn-ghost btn-sm btn-danger-text"
              onClick={() => void remove(share.id)}
            >
              {t('share.remove')}
            </button>
          </li>
        ))}
      </ul>
    </Modal>
  );
}
