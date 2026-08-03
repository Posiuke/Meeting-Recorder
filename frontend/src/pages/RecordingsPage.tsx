import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAppDispatch, useAppSelector } from '../store/hooks';
import { cleanupCorrupt, fetchRecordings, fetchTagCounts } from '../store/recordingsSlice';
import StatusBadge from '../components/StatusBadge';
import Spinner from '../components/Spinner';
import Alert from '../components/Alert';
import ConfirmDialog from '../components/ConfirmDialog';
import UploadRecordingDialog from '../components/UploadRecordingDialog';
import ScreenRecordDialog from '../components/ScreenRecordDialog';
import { errorMessage } from '../api/client';
import { formatDateTime, formatDuration } from '../utils/format';
import { useI18n } from '../i18n';

type Filter = 'all' | 'mine' | 'shared';

/** Zuordnung Filter → Übersetzungsschlüssel (Beschriftung kommt aus common). */
const FILTERS: { key: Filter; labelKey: 'common.all' | 'common.mine' | 'common.sharedWithMe' }[] = [
  { key: 'all', labelKey: 'common.all' },
  { key: 'mine', labelKey: 'common.mine' },
  { key: 'shared', labelKey: 'common.sharedWithMe' },
];

/** Wartezeit nach dem letzten Tastendruck, bevor gesucht wird. */
const SEARCH_DEBOUNCE_MS = 300;

export default function RecordingsPage() {
  const { t } = useI18n();
  const dispatch = useAppDispatch();
  const { items, loading, error, tags } = useAppSelector((s) => s.recordings);
  const [filter, setFilter] = useState<Filter>('all');
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

  useEffect(() => {
    const timer = window.setTimeout(() => setDebouncedSearch(search), SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [search]);

  const reload = useCallback(
    () =>
      dispatch(
        fetchRecordings({
          q: debouncedSearch,
          tag: tagFilter ?? undefined,
          content: searchContent,
        }),
      ),
    [dispatch, debouncedSearch, tagFilter, searchContent],
  );

  useEffect(() => {
    void reload();
  }, [reload]);

  useEffect(() => {
    void dispatch(fetchTagCounts());
  }, [dispatch]);

  const searchActive = debouncedSearch.trim().length > 0 || tagFilter !== null;
  const initialLoading = loading && items.length === 0;

  const filtered = items.filter((r) => {
    if (filter === 'mine') return r.mine;
    if (filter === 'shared') return !r.mine;
    return true;
  });

  // Eine laufende Bildschirmaufnahme steht ebenfalls auf RECORDING – sie ist
  // aber nicht korrupt, sondern läuft gerade in einem anderen Tab.
  const hasCorrupt = items.some(
    (r) =>
      r.mine &&
      r.source !== 'CAPTURE' &&
      (r.status === 'RECORDING' || r.status === 'FINALIZING'),
  );

  const handleCleanup = async () => {
    setCleaning(true);
    setCleanupError(null);
    setCleanupResult(null);
    try {
      const deleted = await dispatch(cleanupCorrupt()).unwrap();
      await reload();
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
        {hasCorrupt && (
          <button
            type="button"
            className="btn btn-danger btn-sm"
            disabled={cleaning}
            onClick={() => setConfirmCleanup(true)}
          >
            {cleaning ? t('recordings.cleaning') : t('recordings.cleanup')}
          </button>
        )}
      </div>

      <div className="filter-tabs">
        {FILTERS.map((f) => (
          <button
            key={f.key}
            type="button"
            className={`filter-tab${filter === f.key ? ' active' : ''}`}
            onClick={() => setFilter(f.key)}
          >
            {t(f.labelKey)}
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
      </div>

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
      {!loading && filtered.length === 0 && (
        <p className="muted">
          {searchActive ? t('recordings.noMatch') : t('recordings.empty')}
          {searchActive && !searchContent && <> {t('recordings.contentHint')}</>}
        </p>
      )}

      {!initialLoading && filtered.length > 0 && (
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
                {filtered.map((rec) => (
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
