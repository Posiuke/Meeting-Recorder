import { useEffect, useState } from 'react';
import Modal from './Modal';
import Alert from './Alert';
import ConfirmDialog from './ConfirmDialog';
import CopyButton from './CopyButton';
import Spinner from './Spinner';
import UserSearchInput from './UserSearchInput';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import {
  addShare,
  createShareLink,
  fetchShareLinks,
  fetchShares,
  removeShare,
  removeShareLink,
} from '../store/recordingsSlice';
import { fetchGroups } from '../store/groupsSlice';
import { errorMessage, shareLinkUrl } from '../api/client';
import { formatDateTime } from '../utils/format';
import { useI18n } from '../i18n';
import type { UserView } from '../types';

/** Wählbare Laufzeiten eines Freigabe-Links (leere Auswahl = bis zum Widerruf). */
const LINK_EXPIRY_DAYS = [7, 30, 90] as const;

interface ShareDialogProps {
  recordingId: string;
  onClose: () => void;
}

export default function ShareDialog({ recordingId, onClose }: ShareDialogProps) {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const { shares, sharesLoading, sharesError, shareLinks, shareLinksLoading, shareLinksError } =
    useAppSelector((s) => s.recordings);
  const groups = useAppSelector((s) => s.groups.items);
  const [selectedGroupId, setSelectedGroupId] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [expiresInDays, setExpiresInDays] = useState('');
  const [linkBusy, setLinkBusy] = useState(false);
  const [confirmRevoke, setConfirmRevoke] = useState<string | null>(null);

  useEffect(() => {
    dispatch(fetchShares(recordingId));
    dispatch(fetchShareLinks(recordingId));
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

  const handleCreateLink = async () => {
    setError(null);
    setLinkBusy(true);
    try {
      await dispatch(
        createShareLink({
          recordingId,
          expiresInDays: expiresInDays === '' ? null : Number(expiresInDays),
        }),
      ).unwrap();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setLinkBusy(false);
    }
  };

  const handleRevokeLink = async (linkId: string) => {
    setError(null);
    try {
      await dispatch(removeShareLink({ recordingId, linkId })).unwrap();
      setConfirmRevoke(null);
    } catch (e) {
      setError(errorMessage(e));
      setConfirmRevoke(null);
    }
  };

  const sharedGroupIds = shares.filter((s) => s.group).map((s) => s.group!.id);
  const availableGroups = groups.filter((g) => !sharedGroupIds.includes(g.id));

  return (
    <Modal title={t('share.title')} onClose={onClose} wide>
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

      {/* Öffentlicher Link: Zugriff ohne Anmeldung, allein über die Adresse. */}
      <h4 className="share-list-title">{t('share.linkHeading')}</h4>
      <p className="muted">{t('share.linkIntro')}</p>

      <div className="form-field">
        <label htmlFor="share-link-expiry">{t('share.linkExpiryLabel')}</label>
        <div className="inline-form">
          <select
            id="share-link-expiry"
            value={expiresInDays}
            disabled={linkBusy}
            onChange={(e) => setExpiresInDays(e.target.value)}
          >
            <option value="">{t('share.linkExpiryNever')}</option>
            {LINK_EXPIRY_DAYS.map((days) => (
              <option key={days} value={days}>
                {t('share.linkExpiryDays', { days })}
              </option>
            ))}
          </select>
          <button
            type="button"
            className="btn btn-primary"
            onClick={handleCreateLink}
            disabled={linkBusy}
          >
            {linkBusy ? t('share.linkCreating') : t('share.linkCreate')}
          </button>
        </div>
      </div>

      {shareLinksLoading && <Spinner label={t('share.linkLoading')} />}
      {shareLinksError && <Alert kind="error">{shareLinksError}</Alert>}
      {!shareLinksLoading && shareLinks.length === 0 && (
        <p className="muted">{t('share.linkEmpty')}</p>
      )}

      {shareLinks.map((link) => (
        <div key={link.id} className="share-link-item">
          <div className="token-reveal">
            <code>{shareLinkUrl(link.token)}</code>
            <CopyButton value={shareLinkUrl(link.token)} className="btn btn-primary btn-sm" />
          </div>
          <div className="share-link-meta">
            <span className="muted">
              {t('share.linkCreated', { date: formatDateTime(link.createdAt) })}
              {' · '}
              {link.expiresAt === null
                ? t('share.linkValidForever')
                : link.expired
                  ? t('share.linkExpired', { date: formatDateTime(link.expiresAt) })
                  : t('share.linkValidUntil', { date: formatDateTime(link.expiresAt) })}
              {' · '}
              {link.views === 0
                ? t('share.linkNeverOpened')
                : t('share.linkViews', {
                    count: link.views,
                    date: formatDateTime(link.lastViewedAt),
                  })}
            </span>
            <button
              type="button"
              className="btn btn-ghost btn-sm btn-danger-text"
              onClick={() => setConfirmRevoke(link.id)}
            >
              {t('share.linkRevoke')}
            </button>
          </div>
        </div>
      ))}

      {confirmRevoke && (
        <ConfirmDialog
          title={t('share.confirmRevokeTitle')}
          message={t('share.confirmRevokeMessage')}
          confirmLabel={t('share.linkRevoke')}
          danger
          onConfirm={() => handleRevokeLink(confirmRevoke)}
          onCancel={() => setConfirmRevoke(null)}
        />
      )}
    </Modal>
  );
}
