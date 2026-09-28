import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import {
  addGroupMember,
  fetchGroupMembers,
  fetchGroups,
  removeGroupMember,
} from '../store/groupsSlice';
import Spinner from '../components/Spinner';
import Alert from '../components/Alert';
import Modal from '../components/Modal';
import ConfirmDialog from '../components/ConfirmDialog';
import UserSearchInput from '../components/UserSearchInput';
import { api, errorMessage } from '../api/client';
import { formatDateTime } from '../utils/format';
import { useI18n } from '../i18n';
import type { AdminGroupView, UserView } from '../types';

const ownerLabel = (group: AdminGroupView, unknown: string) =>
  group.ownerUsername ? `${group.ownerDisplayName} (${group.ownerUsername})` : unknown;

/**
 * Admin-Tab „Gruppen“: alle Gruppen der Anwendung – auch die, in denen der
 * Admin weder Besitzer noch Mitglied ist. Umbenennen, Besitzer wechseln,
 * Mitglieder pflegen und löschen.
 */
export default function AdminGroupsTab() {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const [groups, setGroups] = useState<AdminGroupView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState('');
  const [managing, setManaging] = useState<AdminGroupView | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<AdminGroupView | null>(null);
  const [busy, setBusy] = useState(false);

  const load = async () => {
    try {
      const list = await api<AdminGroupView[]>('/api/admin/groups');
      setGroups(list);
      setManaging((current) => (current ? list.find((g) => g.id === current.id) ?? null : null));
      setError(null);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  // Die eigene Gruppenliste (Seite „Gruppen“) kann sich mitändern.
  const reload = async () => {
    await load();
    void dispatch(fetchGroups());
  };

  const handleDelete = async () => {
    if (!confirmDelete) return;
    setBusy(true);
    try {
      await api<void>(`/api/admin/groups/${confirmDelete.id}`, { method: 'DELETE' });
      setConfirmDelete(null);
      await reload();
    } catch (e) {
      setError(errorMessage(e));
      setConfirmDelete(null);
    } finally {
      setBusy(false);
    }
  };

  if (loading) return <Spinner label={t('adminGroups.loading')} />;

  const q = filter.trim().toLowerCase();
  const visible = q
    ? groups.filter((g) =>
        [g.name, g.ownerUsername ?? '', g.ownerDisplayName ?? ''].some((v) =>
          v.toLowerCase().includes(q),
        ),
      )
    : groups;

  return (
    <div className="card">
      <p className="muted">{t('adminGroups.intro')}</p>
      {error && <Alert kind="error">{error}</Alert>}
      <div className="admin-users-head">
        <input
          type="search"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          placeholder={t('adminGroups.filterPlaceholder')}
        />
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => void load()}>
          {t('admin.usersRefresh')}
        </button>
      </div>
      {groups.length === 0 ? (
        <p className="muted">{t('adminGroups.empty')}</p>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>{t('adminGroups.name')}</th>
                <th>{t('adminGroups.owner')}</th>
                <th>{t('adminGroups.members')}</th>
                <th>{t('adminGroups.shares')}</th>
                <th>{t('adminGroups.created')}</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {visible.map((group) => (
                <tr key={group.id}>
                  <td>{group.name}</td>
                  <td>{ownerLabel(group, t('adminGroups.ownerUnknown'))}</td>
                  <td>{group.memberCount}</td>
                  <td>{group.shareCount}</td>
                  <td>{formatDateTime(group.createdAt)}</td>
                  <td className="cell-actions">
                    <button
                      type="button"
                      className="btn btn-sm"
                      onClick={() => setManaging(group)}
                    >
                      {t('adminGroups.manage')}
                    </button>
                    <button
                      type="button"
                      className="btn btn-danger btn-sm"
                      onClick={() => setConfirmDelete(group)}
                    >
                      {t('common.delete')}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {managing && (
        <ManageGroupDialog
          group={managing}
          onChanged={reload}
          onClose={() => setManaging(null)}
        />
      )}
      {confirmDelete && (
        <ConfirmDialog
          title={t('groups.confirmDeleteTitle')}
          message={t('adminGroups.confirmDeleteMessage', {
            name: confirmDelete.name,
            owner: ownerLabel(confirmDelete, t('adminGroups.ownerUnknown')),
            members: confirmDelete.memberCount,
            shares: confirmDelete.shareCount,
          })}
          confirmLabel={t('common.delete')}
          danger
          busy={busy}
          onConfirm={() => void handleDelete()}
          onCancel={() => setConfirmDelete(null)}
        />
      )}
    </div>
  );
}

function ManageGroupDialog({
  group,
  onChanged,
  onClose,
}: {
  group: AdminGroupView;
  onChanged: () => Promise<void>;
  onClose: () => void;
}) {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const members = useAppSelector((s) => s.groups.membersByGroup[group.id]);
  const membersLoading = useAppSelector((s) => s.groups.membersLoading[group.id] ?? false);
  const [name, setName] = useState(group.name);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [newOwner, setNewOwner] = useState<UserView | null>(null);

  useEffect(() => {
    void dispatch(fetchGroupMembers(group.id));
  }, [dispatch, group.id]);

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
      await dispatch(fetchGroupMembers(group.id));
      await onChanged();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const update = (body: { name?: string; ownerId?: string }) =>
    api<AdminGroupView>(`/api/admin/groups/${group.id}`, { method: 'PUT', body });

  const handleRename = (e: FormEvent) => {
    e.preventDefault();
    if (!name.trim() || name.trim() === group.name) return;
    void run(() => update({ name: name.trim() }));
  };

  return (
    <Modal
      title={t('adminGroups.manageTitle', { name: group.name })}
      onClose={onClose}
      wide
      footer={
        <button type="button" className="btn" onClick={onClose}>
          {t('common.close')}
        </button>
      }
    >
      {error && <Alert kind="error">{error}</Alert>}

      <h3>{t('adminGroups.renameHeading')}</h3>
      <form onSubmit={handleRename} className="inline-form">
        <input type="text" value={name} onChange={(e) => setName(e.target.value)} required />
        <button
          type="submit"
          className="btn btn-primary"
          disabled={busy || !name.trim() || name.trim() === group.name}
        >
          {t('adminGroups.rename')}
        </button>
      </form>

      <h3>{t('adminGroups.ownerHeading')}</h3>
      <p>
        {t('adminGroups.currentOwner', { owner: ownerLabel(group, t('adminGroups.ownerUnknown')) })}
      </p>
      {newOwner ? (
        <div className="inline-form">
          <span>
            {t('adminGroups.newOwner', { owner: `${newOwner.displayName} (${newOwner.username})` })}
          </span>
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy}
            onClick={() =>
              void run(async () => {
                await update({ ownerId: newOwner.id });
                setNewOwner(null);
              })
            }
          >
            {t('adminGroups.changeOwner')}
          </button>
          <button type="button" className="btn" disabled={busy} onClick={() => setNewOwner(null)}>
            {t('common.cancel')}
          </button>
        </div>
      ) : (
        <UserSearchInput
          onSelect={setNewOwner}
          placeholder={t('groups.memberPlaceholder')}
          excludeIds={[group.ownerId]}
        />
      )}
      <p className="muted">{t('adminGroups.ownerHint')}</p>

      <h3>{t('groups.members')}</h3>
      <div className="form-field">
        <label>{t('groups.addMember')}</label>
        <UserSearchInput
          onSelect={(user) =>
            void run(() =>
              dispatch(addGroupMember({ groupId: group.id, userId: user.id })).unwrap(),
            )
          }
          placeholder={t('groups.memberPlaceholder')}
          excludeIds={[group.ownerId, ...(members ?? []).map((m) => m.userId)]}
        />
      </div>
      {membersLoading && !members && <Spinner label={t('groups.membersLoading')} />}
      {members && members.length === 0 && <p className="muted">{t('groups.membersEmpty')}</p>}
      <ul className="share-list">
        {(members ?? []).map((member) => (
          <li key={member.userId}>
            <span>
              {member.displayName} ({member.username})
              <span className="muted">
                {t('groups.memberSince', { date: formatDateTime(member.addedAt) })}
              </span>
            </span>
            <button
              type="button"
              className="btn btn-ghost btn-sm btn-danger-text"
              disabled={busy}
              onClick={() =>
                void run(() =>
                  dispatch(
                    removeGroupMember({ groupId: group.id, userId: member.userId }),
                  ).unwrap(),
                )
              }
            >
              {t('groups.removeMember')}
            </button>
          </li>
        ))}
      </ul>
    </Modal>
  );
}
