import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import {
  addGroupMember,
  createGroup,
  deleteGroup,
  fetchGroupMembers,
  fetchGroups,
  removeGroupMember,
} from '../store/groupsSlice';
import Spinner from '../components/Spinner';
import Alert from '../components/Alert';
import ConfirmDialog from '../components/ConfirmDialog';
import UserSearchInput from '../components/UserSearchInput';
import { errorMessage } from '../api/client';
import { formatDateTime } from '../utils/format';
import { useI18n } from '../i18n';
import type { GroupView, UserView } from '../types';

export default function GroupsPage() {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const { items, loading, loaded, error } = useAppSelector((s) => s.groups);
  const [name, setName] = useState('');
  const [createError, setCreateError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    dispatch(fetchGroups());
  }, [dispatch]);

  const handleCreate = async (e: FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return;
    setCreateError(null);
    setCreating(true);
    try {
      await dispatch(createGroup(name.trim())).unwrap();
      setName('');
    } catch (err) {
      setCreateError(errorMessage(err));
    } finally {
      setCreating(false);
    }
  };

  return (
    <div className="page">
      <h1>{t('groups.heading')}</h1>

      <section className="card">
        <h2>{t('groups.createSection')}</h2>
        {createError && <Alert kind="error">{createError}</Alert>}
        <form onSubmit={handleCreate} className="inline-form">
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={t('groups.namePlaceholder')}
            required
          />
          <button type="submit" className="btn btn-primary" disabled={creating || !name.trim()}>
            {creating ? t('groups.creating') : t('groups.create')}
          </button>
        </form>
      </section>

      {error && <Alert kind="error">{error}</Alert>}
      {loading && !loaded && <Spinner label={t('groups.loading')} />}
      {loaded && items.length === 0 && (
        <p className="muted">{t('groups.empty')}</p>
      )}

      {items.map((group) => (
        <GroupCard key={group.id} group={group} />
      ))}
    </div>
  );
}

function GroupCard({ group }: { group: GroupView }) {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const me = useAppSelector((s) => s.auth.user);
  const members = useAppSelector((s) => s.groups.membersByGroup[group.id]);
  const membersLoading = useAppSelector((s) => s.groups.membersLoading[group.id] ?? false);
  const [open, setOpen] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<'delete' | 'leave' | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (open) {
      dispatch(fetchGroupMembers(group.id));
    }
  }, [open, dispatch, group.id]);

  const handleAddMember = async (user: UserView) => {
    setActionError(null);
    try {
      await dispatch(addGroupMember({ groupId: group.id, userId: user.id })).unwrap();
      await dispatch(fetchGroupMembers(group.id));
    } catch (e) {
      setActionError(errorMessage(e));
    }
  };

  const handleRemoveMember = async (userId: string) => {
    setActionError(null);
    try {
      await dispatch(removeGroupMember({ groupId: group.id, userId })).unwrap();
    } catch (e) {
      setActionError(errorMessage(e));
    }
  };

  const handleDelete = async () => {
    setActionError(null);
    setBusy(true);
    try {
      await dispatch(deleteGroup(group.id)).unwrap();
    } catch (e) {
      setActionError(errorMessage(e));
    } finally {
      setBusy(false);
      setConfirm(null);
    }
  };

  const handleLeave = async () => {
    if (!me) return;
    setActionError(null);
    setBusy(true);
    try {
      await dispatch(removeGroupMember({ groupId: group.id, userId: me.id })).unwrap();
      await dispatch(fetchGroups());
    } catch (e) {
      setActionError(errorMessage(e));
    } finally {
      setBusy(false);
      setConfirm(null);
    }
  };

  return (
    <div className="card group-card">
      <div className="group-card-head">
        <button
          type="button"
          className="collapse-toggle"
          onClick={() => setOpen((v) => !v)}
          aria-expanded={open}
        >
          <span className={`chevron${open ? ' open' : ''}`}>▸</span> {group.name}
        </button>
        <div className="group-card-info">
          {group.mine ? (
            <span className="tag">{t('groups.mine')}</span>
          ) : (
            <span className="tag tag-muted">{t('groups.member')}</span>
          )}
          <span className="muted">{t('groups.createdAt', { date: formatDateTime(group.createdAt) })}</span>
        </div>
        <div className="group-card-actions">
          {group.mine ? (
            <button
              type="button"
              className="btn btn-danger btn-sm"
              onClick={() => setConfirm('delete')}
            >
              {t('groups.delete')}
            </button>
          ) : (
            <button type="button" className="btn btn-sm" onClick={() => setConfirm('leave')}>
              {t('groups.leave')}
            </button>
          )}
        </div>
      </div>

      {actionError && <Alert kind="error">{actionError}</Alert>}

      {open && (
        <div className="group-members">
          <h3>{t('groups.members')}</h3>
          {group.mine && (
            <div className="form-field">
              <label>{t('groups.addMember')}</label>
              <UserSearchInput
                onSelect={handleAddMember}
                placeholder={t('groups.memberPlaceholder')}
                excludeIds={(members ?? []).map((m) => m.userId)}
              />
            </div>
          )}
          {membersLoading && <Spinner label={t('groups.membersLoading')} />}
          {!membersLoading && (members ?? []).length === 0 && (
            <p className="muted">{t('groups.membersEmpty')}</p>
          )}
          <ul className="share-list">
            {(members ?? []).map((member) => (
              <li key={member.userId}>
                <span>
                  {member.displayName} ({member.username})
                  <span className="muted">{t('groups.memberSince', { date: formatDateTime(member.addedAt) })}</span>
                </span>
                {group.mine && member.userId !== me?.id && (
                  <button
                    type="button"
                    className="btn btn-ghost btn-sm btn-danger-text"
                    onClick={() => handleRemoveMember(member.userId)}
                  >
                    {t('groups.removeMember')}
                  </button>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}

      {confirm === 'delete' && (
        <ConfirmDialog
          title={t('groups.confirmDeleteTitle')}
          message={t('groups.confirmDeleteMessage', { name: group.name })}
          confirmLabel={t('common.delete')}
          danger
          busy={busy}
          onConfirm={handleDelete}
          onCancel={() => setConfirm(null)}
        />
      )}
      {confirm === 'leave' && (
        <ConfirmDialog
          title={t('groups.confirmLeaveTitle')}
          message={t('groups.confirmLeaveMessage', { name: group.name })}
          confirmLabel={t('groups.confirmLeave')}
          busy={busy}
          onConfirm={handleLeave}
          onCancel={() => setConfirm(null)}
        />
      )}
    </div>
  );
}
