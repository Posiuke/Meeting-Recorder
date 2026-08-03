import { useEffect, useRef, useState } from 'react';
import { api } from '../api/client';
import { useI18n } from '../i18n';
import type { UserView } from '../types';

interface UserSearchInputProps {
  onSelect: (user: UserView) => void;
  placeholder?: string;
  excludeIds?: string[];
}

/** Autocomplete-Nutzersuche über /api/users/search (min. 2 Zeichen, debounced). */
export default function UserSearchInput({
  onSelect,
  placeholder,
  excludeIds = [],
}: UserSearchInputProps) {
  const { t } = useI18n();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<UserView[]>([]);
  const [open, setOpen] = useState(false);
  const [searching, setSearching] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const q = query.trim();
    if (q.length < 2) {
      setResults([]);
      setOpen(false);
      return;
    }
    let cancelled = false;
    setSearching(true);
    const timer = setTimeout(async () => {
      try {
        const users = await api<UserView[]>(`/api/users/search?q=${encodeURIComponent(q)}`);
        if (!cancelled) {
          setResults(users);
          setOpen(true);
        }
      } catch {
        if (!cancelled) {
          setResults([]);
        }
      } finally {
        if (!cancelled) {
          setSearching(false);
        }
      }
    }, 300);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [query]);

  useEffect(() => {
    const onDocClick = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onDocClick);
    return () => document.removeEventListener('mousedown', onDocClick);
  }, []);

  const visible = results.filter((u) => !excludeIds.includes(u.id));

  return (
    <div className="user-search" ref={containerRef}>
      <input
        type="text"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onFocus={() => {
          if (visible.length > 0) setOpen(true);
        }}
        placeholder={placeholder ?? t('userSearch.placeholder')}
      />
      {open && (
        <ul className="user-search-results">
          {searching && <li className="user-search-empty">{t('userSearch.searching')}</li>}
          {!searching && visible.length === 0 && (
            <li className="user-search-empty">{t('userSearch.empty')}</li>
          )}
          {!searching &&
            visible.map((user) => (
              <li key={user.id}>
                <button
                  type="button"
                  onClick={() => {
                    onSelect(user);
                    setQuery('');
                    setResults([]);
                    setOpen(false);
                  }}
                >
                  <span className="user-search-name">{user.displayName}</span>
                  <span className="user-search-username">{user.username}</span>
                </button>
              </li>
            ))}
        </ul>
      )}
    </div>
  );
}
