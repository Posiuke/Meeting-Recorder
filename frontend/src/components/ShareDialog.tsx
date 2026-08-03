import { useEffect, useState } from 'react';
import Modal from './Modal';
import Alert from './Alert';
import Spinner from './Spinner';
import UserSearchInput from './UserSearchInput';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import { addShare, fetchShares, removeShare } from '../store/recordingsSlice';
import { fetchGroups } from '../store/groupsSlice';
import { errorMessage } from '../api/client';
import { useI18n } from '../i18n';
import type { UserView } from '../types';

interface ShareDialogProps {
  recordingId: string;
  onClose: () => void;
}

export default function ShareDialog({ recordingId, onClose }: ShareDialogProps) {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const { shares, sharesLoading, sharesError } = useAppSelector((s) => s.recordings);
  const groups = useAppSelector((s) => s.groups.items);
  const [selectedGroupId, setSelectedGroupId] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    dispatch(fetchShares(recordingId));
    dispatch(fetchGroups());
  }, [dispatch, recordingId]);

  const shareWithUser = async (user: UserView) => {
    setError(null);
    setBusy(true);
    try {
      await dispatch(addShare({ recordingId, userId: user.id })).unwrap();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const shareWithGroup = async () => {
    if (!selectedGroupId) return;
    setError(null);
    setBusy(true);
    try {
      await dispatch(addShare({ recordingId, groupId: selectedGroupId })).unwrap();
      setSelectedGroupId('');
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const handleRemove = async (shareId: string) => {
    setError(null);
    try {
      await dispatch(removeShare({ recordingId, shareId })).unwrap();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const sharedGroupIds = shares.filter((s) => s.group).map((s) => s.group!.id);
  const availableGroups = groups.filter((g) => !sharedGroupIds.includes(g.id));

  return (
    <Modal title={t('share.title')} onClose={onClose}>
      {error && <Alert kind="error">{error}</Alert>}

      <div className="form-field">
        <label>{t('share.withUser')}</label>
        <UserSearchInput
          onSelect={shareWithUser}
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
            onClick={shareWithGroup}
            disabled={!selectedGroupId || busy}
          >
            {t('share.submit')}
          </button>
        </div>
      </div>

      <h4 className="share-list-title">{t('share.existing')}</h4>
      {sharesLoading && <Spinner label={t('share.loading')} />}
      {sharesError && <Alert kind="error">{sharesError}</Alert>}
      {!sharesLoading && shares.length === 0 && (
        <p className="muted">{t('share.empty')}</p>
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
              onClick={() => handleRemove(share.id)}
            >
              {t('share.remove')}
            </button>
          </li>
        ))}
      </ul>
    </Modal>
  );
}
