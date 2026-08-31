import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import {
  cleanupCorrupt,
  fetchCleanupCandidates,
  fetchRecordingOwners,
  fetchRecordings,
  fetchTagCounts,
} from '../store/recordingsSlice';
import type {
  OwnerFilter,
  RecordingFilter,
  RecordingSortKey,
  SortDirection,
} from '../store/recordingsSlice';
import StatusBadge from '../components/StatusBadge';
import Spinner from '../components/Spinner';
import Alert from '../components/Alert';
import ConfirmDialog from '../components/ConfirmDialog';
import UploadRecordingDialog from '../components/UploadRecordingDialog';
import ScreenRecordDialog from '../components/ScreenRecordDialog';
import { errorMessage } from '../api/client';
import { formatDateTime, formatDuration } from '../utils/format';
import { useI18n } from '../i18n';
import type { RecordingSource } from '../types';

/** Zuordnung Besitzerfilter → Übersetzungsschlüssel (Beschriftung kommt aus common). */
const OWNER_TABS: {
  key: 'all' | 'mine' | 'shared';
  labelKey: 'common.all' | 'common.mine' | 'common.sharedWithMe';
}[] = [
  { key: 'all', labelKey: 'common.all' },
  { key: 'mine', labelKey: 'common.mine' },
  { key: 'shared', labelKey: 'common.sharedWithMe' },
];

/** Auswählbare Quellen; '' = alle. */
const SOURCES: RecordingSource[] = ['BOT', 'UPLOAD', 'CAPTURE'];

/** Wartezeit nach dem letzten Tastendruck, bevor gesucht wird. */
const SEARCH_DEBOUNCE_MS = 300;

export default function RecordingsPage() {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const {
    items,
    loading,
    loadingMore,
    error,
    tags,
    total,
    hasMore,
    page,
    owners,
    cleanupCandidates,
  } = useAppSelector((s) => s.recordings);

  const [confirmCleanup, setConfirmCleanup] = useState(false);
  const [cleaning, setCleaning] = useState(false);
  const [cleanupResult, setCleanupResult] = useState<string | null>(null);
  const [cleanupError, setCleanupError] = useState<string | null>(null);
  const [showUpload, setShowUpload] = useState(false);
  const [uploadResult, setUploadResult] = useState<string | null>(null);
  const [showCapture, setShowCapture] = useState(false);

  // Suche: Eingabe sofort im Feld, Abfrage erst nach kurzer Pause
  const [search, setSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [searchContent, setSearchContent] = useState(false);
  const [tagFilter, setTagFilter] = useState<string | null>(null);

  // Filterleiste
  const [ownerFilter, setOwnerFilter] = useState<OwnerFilter>('all');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [source, setSource] = useState<RecordingSource | ''>('');
  const [sort, setSort] = useState<RecordingSortKey>('date');
  const [dir, setDir] = useState<SortDirection>('desc');
  const [filtersOpen, setFiltersOpen] = useState(false);

  useEffect(() => {
    const timer = window.setTimeout(() => setDebouncedSearch(search), SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [search]);

  const filter = useMemo<RecordingFilter>(
    () => ({
      q: debouncedSearch,
      tag: tagFilter ?? undefined,
      content: searchContent,
      from: from || undefined,
      to: to || undefined,
      source: source || undefined,
      owner: ownerFilter,
      sort,
      dir,
    }),
    [debouncedSearch, tagFilter, searchContent, from, to, source, ownerFilter, sort, dir],
  );

  /** Erste Seite neu laden – bei jeder Änderung an Suche, Filter oder Sortierung. */
  const reload = useCallback(
    () => dispatch(fetchRecordings({ filter, page: 0 })),
    [dispatch, filter],
  );

  useEffect(() => {
    void reload();
  }, [reload]);

  useEffect(() => {
    void dispatch(fetchTagCounts());
    void dispatch(fetchRecordingOwners());
    void dispatch(fetchCleanupCandidates());
  }, [dispatch]);

  const loadMore = () => {
    void dispatch(fetchRecordings({ filter, page: page + 1, append: true }));
  };

  const dateFilterActive = from !== '' || to !== '';
  const extraFilterActive = dateFilterActive || source !== '' || ownerFilter !== 'all';
  const searchActive = debouncedSearch.trim().length > 0 || tagFilter !== null || extraFilterActive;
  const initialLoading = loading && items.length === 0;

  const resetFilters = () => {
    setFrom('');
    setTo('');
    setSource('');
    setOwnerFilter('all');
  };

  const handleCleanup = async () => {
    setCleaning(true);
    setCleanupError(null);
    setCleanupResult(null);
    try {
      const deleted = await dispatch(cleanupCorrupt()).unwrap();
      await reload();
      void dispatch(fetchCleanupCandidates());
      setCleanupResult(
        deleted === 0
          ? t('recordings.cleanupNone')
          : t('recordings.cleanupDone', { count: deleted }),
      );
    } catch (e) {
      setCleanupError(errorMessage(e));
    } finally {
      setCleaning(false);
      setConfirmCleanup(false);
    }
  };

  return (
    <div className="page">
      <div className="page-head">
        <h1>{t('recordings.heading')}</h1>
        <button
          type="button"
          className="btn btn-primary btn-sm"
          onClick={() => {
            setUploadResult(null);
            setShowCapture(true);
          }}
        >
          {t('recordings.captureButton')}
        </button>
        <button
          type="button"
          className="btn btn-primary btn-sm"
          onClick={() => {
            setUploadResult(null);
            setShowUpload(true);
          }}
        >
          {t('recordings.uploadButton')}
        </button>
        {cleanupCandidates > 0 && (
          <button
            type="button"
            className="btn btn-danger btn-sm"
            disabled={cleaning}
            title={t('recordings.cleanupCandidates', { count: cleanupCandidates })}
            onClick={() => setConfirmCleanup(true)}
          >
            {cleaning ? t('recordings.cleaning') : t('recordings.cleanup')}
          </button>
        )}
      </div>

      <div className="filter-tabs">
        {OWNER_TABS.map((tab) => (
          <button
            key={tab.key}
            type="button"
            className={`filter-tab${ownerFilter === tab.key ? ' active' : ''}`}
            onClick={() => setOwnerFilter(tab.key)}
          >
            {t(tab.labelKey)}
          </button>
        ))}
      </div>

      <div className="search-bar">
        <input
          type="search"
          className="search-input"
          placeholder={t('recordings.searchPlaceholder')}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <label className="checkbox-field">
          <input
            type="checkbox"
            checked={searchContent}
            onChange={(e) => setSearchContent(e.target.checked)}
          />
          {t('recordings.searchContent')}
        </label>
        <button
          type="button"
          className="collapse-toggle"
          aria-expanded={filtersOpen}
          onClick={() => setFiltersOpen((v) => !v)}
        >
          <span className={`chevron${filtersOpen ? ' open' : ''}`}>▸</span>{' '}
          {t('recordings.filters')}
          {extraFilterActive && <span className="badge badge-blue">{t('recordings.filtersOn')}</span>}
        </button>
      </div>

      {filtersOpen && (
        <div className="card recordings-filters">
          <div className="form-row">
            <div className="form-field">
              <label htmlFor="filter-from">{t('recordings.filterFrom')}</label>
              <input
                id="filter-from"
                type="date"
                value={from}
                max={to || undefined}
                onChange={(e) => setFrom(e.target.value)}
              />
            </div>
            <div className="form-field">
              <label htmlFor="filter-to">{t('recordings.filterTo')}</label>
              <input
                id="filter-to"
                type="date"
                value={to}
                min={from || undefined}
                onChange={(e) => setTo(e.target.value)}
              />
            </div>
            <div className="form-field">
              <label htmlFor="filter-source">{t('recordings.filterSource')}</label>
              <select
                id="filter-source"
                value={source}
                onChange={(e) => setSource(e.target.value as RecordingSource | '')}
              >
                <option value="">{t('common.all')}</option>
                {SOURCES.map((s) => (
                  <option key={s} value={s}>
                    {t(`recordings.source.${s}`)}
                  </option>
                ))}
              </select>
            </div>
            {owners.length > 0 && (
              <div className="form-field">
                <label htmlFor="filter-owner">{t('recordings.filterOwner')}</label>
                <select
                  id="filter-owner"
                  value={ownerFilter}
                  onChange={(e) => setOwnerFilter(e.target.value as OwnerFilter)}
                >
                  <option value="all">{t('common.all')}</option>
                  <option value="mine">{t('common.mine')}</option>
                  <option value="shared">{t('common.sharedWithMe')}</option>
                  {owners.map((o) => (
                    <option key={o.id} value={o.id}>
                      {o.displayName || o.username}
                    </option>
                  ))}
                </select>
              </div>
            )}
            <div className="form-field">
              <label htmlFor="filter-sort">{t('recordings.sortLabel')}</label>
              <select
                id="filter-sort"
                value={`${sort}:${dir}`}
                onChange={(e) => {
                  const [nextSort, nextDir] = e.target.value.split(':');
                  setSort(nextSort as RecordingSortKey);
                  setDir(nextDir as SortDirection);
                }}
              >
                <option value="date:desc">{t('recordings.sortDateDesc')}</option>
                <option value="date:asc">{t('recordings.sortDateAsc')}</option>
                <option value="title:asc">{t('recordings.sortTitleAsc')}</option>
                <option value="title:desc">{t('recordings.sortTitleDesc')}</option>
              </select>
            </div>
          </div>
          {extraFilterActive && (
            <button type="button" className="btn btn-ghost btn-sm" onClick={resetFilters}>
              {t('recordings.filtersReset')}
            </button>
          )}
        </div>
      )}

      {tags.length > 0 && (
        <div className="tag-filter">
          <button
            type="button"
            className={`tag-chip${tagFilter === null ? ' active' : ''}`}
            onClick={() => setTagFilter(null)}
          >
            {t('recordings.allTags')}
          </button>
          {tags.map((tag) => (
            <button
              key={tag.name}
              type="button"
              className={`tag-chip${tagFilter === tag.name ? ' active' : ''}`}
              onClick={() => setTagFilter(tagFilter === tag.name ? null : tag.name)}
            >
              {tag.name} <span className="tag-chip-count">{tag.count}</span>
            </button>
          ))}
        </div>
      )}

      {uploadResult && <Alert kind="success">{uploadResult}</Alert>}
      {cleanupResult && <Alert kind="success">{cleanupResult}</Alert>}
      {cleanupError && <Alert kind="error">{cleanupError}</Alert>}
      {error && <Alert kind="error">{error}</Alert>}
      {/* Spinner nur beim ersten Laden: Während des Tippens bleibt die Liste
          stehen, statt bei jedem Tastendruck weggeblendet zu werden. */}
      {initialLoading && <Spinner label={t('recordings.loading')} />}
      {!initialLoading && loading && <p className="muted">{t('recordings.searching')}</p>}
      {!loading && items.length === 0 && (
        <p className="muted">
          {searchActive ? t('recordings.noMatch') : t('recordings.empty')}
          {searchActive && !searchContent && <> {t('recordings.contentHint')}</>}
        </p>
      )}

      {!initialLoading && items.length > 0 && (
        <>
          <div className="card table-card">
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>{t('common.date')}</th>
                    <th>{t('recordings.columnTitle')}</th>
                    <th>{t('common.duration')}</th>
                    <th>{t('common.status')}</th>
                    <th>{t('common.owner')}</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((rec) => (
                    <tr key={rec.id}>
                      <td>{formatDateTime(rec.startedAt)}</td>
                      <td className="cell-url">
                        <Link to={`/recordings/${rec.id}`}>
                          {rec.title ?? rec.meetingUrl}
                        </Link>
                        {rec.source === 'UPLOAD' && (
                          <span className="badge badge-blue">{t('recordings.badgeUpload')}</span>
                        )}
                        {rec.source === 'CAPTURE' && (
                          <span className="badge badge-blue">{t('recordings.badgeCapture')}</span>
                        )}
                        {rec.tags.length > 0 && (
                          <span className="row-tags">
                            {rec.tags.map((tag) => (
                              <button
                                key={tag}
                                type="button"
                                className="tag-pill"
                                title={t('recordings.filterByTag', { tag })}
                                onClick={() => setTagFilter(tag)}
                              >
                                {tag}
                              </button>
                            ))}
                          </span>
                        )}
                      </td>
                      <td>{formatDuration(rec.durationMs)}</td>
                      <td>
                        <StatusBadge status={rec.status} />
                      </td>
                      <td>
                        {rec.mine
                          ? t('common.me')
                          : t('recordings.ownerShared', {
                              name: rec.owner?.displayName ?? t('common.unknown'),
                            })}
                      </td>
                      <td>
                        <Link className="btn btn-ghost btn-sm" to={`/recordings/${rec.id}`}>
                          {t('common.details')}
                        </Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <div className="list-footer">
            <span className="muted">
              {t('recordings.shownOf', { shown: items.length, total })}
            </span>
            {hasMore && (
              <button
                type="button"
                className="btn btn-sm"
                disabled={loadingMore}
                onClick={loadMore}
              >
                {loadingMore ? t('recordings.loadingMore') : t('recordings.loadMore')}
              </button>
            )}
          </div>
        </>
      )}

      {showCapture && (
        <ScreenRecordDialog
          onClose={() => setShowCapture(false)}
          onFinished={() => {
            setUploadResult(t('recordings.captureDone'));
            void reload();
          }}
        />
      )}

      {showUpload && (
        <UploadRecordingDialog
          onClose={() => setShowUpload(false)}
          onUploaded={() => {
            setShowUpload(false);
            setUploadResult(t('recordings.uploadDone'));
            void reload();
          }}
        />
      )}

      {confirmCleanup && (
        <ConfirmDialog
          title={t('recordings.cleanupTitle')}
          message={t('recordings.cleanupMessage')}
          confirmLabel={t('recordings.cleanupConfirm')}
          danger
          busy={cleaning}
          onConfirm={handleCleanup}
          onCancel={() => setConfirmCleanup(false)}
        />
      )}
    </div>
  );
}
