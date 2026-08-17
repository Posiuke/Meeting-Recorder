import { useEffect, useMemo, useRef } from 'react';
import { formatTimestamp } from '../utils/format';
import { useI18n } from '../i18n';
import type { ParticipantView, TranscriptEntry } from '../types';

interface TranscriptListProps {
  entries: TranscriptEntry[];
  participants: ParticipantView[];
  /**
   * Sprung an die Stelle der angeklickten Zeile (Sekunden ab Aufnahmebeginn).
   * Fehlt der Rückruf, bleibt das Transkript reiner Text – so nutzt die
   * öffentliche Freigabe-Ansicht dieselbe Anzeige ohne Player.
   */
  onSeek?: (seconds: number) => void;
  /** Index der gerade laufenden Zeile; -1 = keine. */
  activeIndex?: number;
}

/**
 * Strukturierte Transkript-Anzeige: Zeitmarke, optionales Sprecher-Label
 * (WhisperX-Diarisierung) und Text. Die Labels werden über die Teilnehmerliste
 * auf die gepflegten Namen abgebildet und pro Sprecher eingefärbt; Labels ohne
 * Teilnehmer-Eintrag fallen auf "Sprecher N" zurück.
 *
 * Wird von der Detailansicht und von der öffentlichen Freigabe-Ansicht genutzt –
 * beide zeigen dasselbe Transkript, nur mit verschiedenen Datenquellen.
 */
export default function TranscriptList({
  entries,
  participants,
  onSeek,
  activeIndex = -1,
}: TranscriptListProps) {
  const { t } = useI18n();
  const activeRef = useRef<HTMLDivElement | null>(null);

  const speakers = useMemo(() => {
    const map = new Map<string, { label: string; colorIdx: number }>();
    for (const p of participants) {
      if (p.speakerLabel && !map.has(p.speakerLabel)) {
        map.set(p.speakerLabel, { label: p.displayName, colorIdx: map.size % 6 });
      }
    }
    for (const e of entries) {
      if (e.speaker && !map.has(e.speaker)) {
        const m = /^SPEAKER_(\d+)$/.exec(e.speaker);
        map.set(e.speaker, {
          label: m ? `Sprecher ${Number(m[1]) + 1}` : e.speaker,
          colorIdx: map.size % 6,
        });
      }
    }
    return map;
  }, [entries, participants]);

  // Läuft die Wiedergabe weiter, wandert die Hervorhebung mit – die Zeile wird
  // nur dann herangeholt, wenn sie gerade nicht zu sehen ist.
  useEffect(() => {
    const row = activeRef.current;
    if (!row) return;
    const box = row.getBoundingClientRect();
    const visible = box.top >= 0 && box.bottom <= window.innerHeight;
    if (!visible) {
      row.scrollIntoView({ block: 'center', behavior: 'smooth' });
    }
  }, [activeIndex]);

  return (
    <div className="transcript">
      {entries.map((entry, i) => {
        const prevSpeaker = i > 0 ? entries[i - 1].speaker : undefined;
        const speaker = entry.speaker ? speakers.get(entry.speaker) : undefined;
        const showSpeaker = speaker && entry.speaker !== prevSpeaker;
        const active = i === activeIndex;
        const seek = onSeek ? () => onSeek(entry.startSeconds) : undefined;
        return (
          <div
            key={i}
            ref={active ? activeRef : undefined}
            className={`transcript-row${seek ? ' seekable' : ''}${active ? ' active' : ''}`}
            // Ohne Player bleibt die Zeile ein reiner Textabsatz – keine
            // Tastaturfalle, kein irreführendes Klick-Versprechen.
            role={seek ? 'button' : undefined}
            tabIndex={seek ? 0 : undefined}
            title={seek ? t('recordingDetail.seekHint') : undefined}
            aria-current={active ? 'true' : undefined}
            onClick={seek}
            onKeyDown={
              seek
                ? (e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      seek();
                    }
                  }
                : undefined
            }
          >
            <span className="transcript-time">{formatTimestamp(entry.startSeconds)}</span>
            <div className="transcript-body">
              {showSpeaker && (
                <span className={`transcript-speaker speaker-c${speaker.colorIdx}`}>
                  {speaker.label}
                </span>
              )}
              <p className="transcript-text">{entry.text}</p>
            </div>
          </div>
        );
      })}
    </div>
  );
}
