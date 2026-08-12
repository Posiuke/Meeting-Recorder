import { useMemo } from 'react';
import { formatTimestamp } from '../utils/format';
import type { ParticipantView, TranscriptEntry } from '../types';

interface TranscriptListProps {
  entries: TranscriptEntry[];
  participants: ParticipantView[];
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
export default function TranscriptList({ entries, participants }: TranscriptListProps) {
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

  return (
    <div className="transcript">
      {entries.map((entry, i) => {
        const prevSpeaker = i > 0 ? entries[i - 1].speaker : undefined;
        const speaker = entry.speaker ? speakers.get(entry.speaker) : undefined;
        const showSpeaker = speaker && entry.speaker !== prevSpeaker;
        return (
          <div key={i} className="transcript-row">
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
