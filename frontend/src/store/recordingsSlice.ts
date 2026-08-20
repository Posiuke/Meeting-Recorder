import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { api, errorMessage } from '../api/client';
import { translate } from '../i18n';
import type {
  JobView,
  ParticipantView,
  RecordingDetail,
  RecordingView,
  ShareLinkView,
  ShareView,
  SummaryOptionsView,
  SummaryView,
  TagCountView,
  TranscriptView,
} from '../types';

/** Filter der Aufnahmenliste (leer = alles). */
export interface RecordingFilter {
  /** Suchbegriff für Titel/Raumname, Meeting-URL und Schlagworte. */
  q?: string;
  /** Nur Aufnahmen mit diesem Schlagwort. */
  tag?: string;
  /** Zusätzlich in Transkript und Zusammenfassung suchen. */
  content?: boolean;
}

interface RecordingsState {
  items: RecordingView[];
  loading: boolean;
  error: string | null;
  /**
   * Kennung der jüngsten Listenabfrage. Bei getippter Suche laufen mehrere
   * Abfragen gleichzeitig – ohne diesen Vergleich könnte eine langsamere,
   * ältere Antwort das neuere Ergebnis überschreiben.
   */
  listRequestId: string | null;
  tags: TagCountView[];
  detail: RecordingDetail | null;
  detailLoading: boolean;
  detailError: string | null;
  transcript: TranscriptView | null;
  transcriptLoading: boolean;
  transcriptError: string | null;
  shares: ShareView[];
  sharesLoading: boolean;
  sharesError: string | null;
  /** Öffentliche Freigabe-Links der Aufnahme (nur für den Besitzer sichtbar). */
  shareLinks: ShareLinkView[];
  shareLinksLoading: boolean;
  shareLinksError: string | null;
}

const initialState: RecordingsState = {
  items: [],
  loading: false,
  error: null,
  listRequestId: null,
  tags: [],
  detail: null,
  detailLoading: false,
  detailError: null,
  transcript: null,
  transcriptLoading: false,
  transcriptError: null,
  shares: [],
  sharesLoading: false,
  sharesError: null,
  shareLinks: [],
  shareLinksLoading: false,
  shareLinksError: null,
};

export const fetchRecordings = createAsyncThunk<
  RecordingView[],
  RecordingFilter | undefined,
  { rejectValue: string }
>('recordings/fetch', async (filter, { rejectWithValue }) => {
  try {
    const params = new URLSearchParams();
    if (filter?.q?.trim()) params.set('q', filter.q.trim());
    if (filter?.tag) params.set('tag', filter.tag);
    if (filter?.content) params.set('content', 'true');
    const query = params.toString();
    return await api<RecordingView[]>(`/api/recordings${query ? `?${query}` : ''}`);
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

/** Alle sichtbaren Schlagworte mit Häufigkeit – für Filterleiste und Vorschläge. */
export const fetchTagCounts = createAsyncThunk<TagCountView[], void, { rejectValue: string }>(
  'recordings/fetchTags',
  async (_, { rejectWithValue }) => {
    try {
      return await api<TagCountView[]>('/api/recordings/tags');
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

export const addRecordingTag = createAsyncThunk<
  { recordingId: string; tags: string[] },
  { recordingId: string; name: string },
  { rejectValue: string }
>('recordings/addTag', async ({ recordingId, name }, { rejectWithValue }) => {
  try {
    const tags = await api<string[]>(`/api/recordings/${recordingId}/tags`, {
      method: 'POST',
      body: { name },
    });
    return { recordingId, tags };
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const removeRecordingTag = createAsyncThunk<
  { recordingId: string; tags: string[] },
  { recordingId: string; name: string },
  { rejectValue: string }
>('recordings/removeTag', async ({ recordingId, name }, { rejectWithValue }) => {
  try {
    const tags = await api<string[]>(
      `/api/recordings/${recordingId}/tags?name=${encodeURIComponent(name)}`,
      { method: 'DELETE' },
    );
    return { recordingId, tags };
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const fetchRecordingDetail = createAsyncThunk<
  RecordingDetail,
  { id: string; silent?: boolean },
  { rejectValue: string }
>('recordings/fetchDetail', async ({ id }, { rejectWithValue }) => {
  try {
    return await api<RecordingDetail>(`/api/recordings/${id}`);
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const deleteRecording = createAsyncThunk<string, string, { rejectValue: string }>(
  'recordings/delete',
  async (id, { rejectWithValue }) => {
    try {
      await api<void>(`/api/recordings/${id}`, { method: 'DELETE' });
      return id;
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

export const cleanupCorrupt = createAsyncThunk<number, void, { rejectValue: string }>(
  'recordings/cleanupCorrupt',
  async (_, { rejectWithValue }) => {
    try {
      const result = await api<{ deleted: number }>('/api/recordings/cleanup-corrupt', {
        method: 'POST',
      });
      return result.deleted;
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

export const processRecording = createAsyncThunk<JobView, string, { rejectValue: string }>(
  'recordings/process',
  async (id, { rejectWithValue }) => {
    try {
      return await api<JobView>(`/api/recordings/${id}/process`, { method: 'POST' });
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

/** Schritt 1 der Zwei-Schritt-Auswertung: nur transkribieren, keine KI-Zusammenfassung. */
export const transcribeRecording = createAsyncThunk<JobView, string, { rejectValue: string }>(
  'recordings/transcribe',
  async (id, { rejectWithValue }) => {
    try {
      return await api<JobView>(`/api/recordings/${id}/transcribe`, { method: 'POST' });
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

/** Erneute Auswertung: die Zusammenfassung wird neu erstellt – als weitere Fassung neben den vorhandenen. */
export const reprocessRecording = createAsyncThunk<JobView, string, { rejectValue: string }>(
  'recordings/reprocess',
  async (id, { rejectWithValue }) => {
    try {
      return await api<JobView>(`/api/recordings/${id}/reprocess`, { method: 'POST' });
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

/**
 * Pro-Aufnahme-Einstellungen für Spracherkennung und Zusammenfassung speichern
 * (wirken bei der nächsten Auswertung bzw. Transkription).
 */
export const updateSummaryOptions = createAsyncThunk<
  SummaryOptionsView,
  {
    id: string;
    prompt: string | null;
    templateName: string | null;
    maxWords: number | null;
    language: string | null;
    sttLanguage: string | null;
  },
  { rejectValue: string }
>('recordings/updateSummaryOptions', async (
  { id, prompt, templateName, maxWords, language, sttLanguage },
  { rejectWithValue },
) => {
  try {
    return await api<SummaryOptionsView>(`/api/recordings/${id}/summary-options`, {
      method: 'POST',
      body: { prompt, templateName, maxWords, language, sttLanguage },
    });
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

/** Erneute Transkription: Spracherkennung läuft für alle Segmente neu, danach neue Zusammenfassung. */
export const retranscribeRecording = createAsyncThunk<JobView, string, { rejectValue: string }>(
  'recordings/retranscribe',
  async (id, { rejectWithValue }) => {
    try {
      return await api<JobView>(`/api/recordings/${id}/retranscribe`, { method: 'POST' });
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

/** Teilnehmer umbenennen – der Name ersetzt das Sprecher-Label im Transkript. */
export const updateParticipant = createAsyncThunk<
  ParticipantView,
  { recordingId: string; participantId: string; displayName: string },
  { rejectValue: string }
>('recordings/updateParticipant', async ({ recordingId, participantId, displayName }, { rejectWithValue }) => {
  try {
    return await api<ParticipantView>(
      `/api/recordings/${recordingId}/participants/${participantId}`,
      { method: 'PUT', body: { displayName } },
    );
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const fetchTranscript = createAsyncThunk<TranscriptView, string, { rejectValue: string }>(
  'recordings/fetchTranscript',
  async (id, { rejectWithValue }) => {
    try {
      return await api<TranscriptView>(`/api/recordings/${id}/transcript`);
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

/** Zusammenfassung händisch bearbeiten – der Markdown-Inhalt ersetzt den bisherigen. */
export const updateSummary = createAsyncThunk<
  SummaryView,
  { recordingId: string; summaryId: string; markdown: string },
  { rejectValue: string }
>('recordings/updateSummary', async ({ recordingId, summaryId, markdown }, { rejectWithValue }) => {
  try {
    return await api<SummaryView>(`/api/recordings/${recordingId}/summaries/${summaryId}`, {
      method: 'PUT',
      body: { markdown },
    });
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

/**
 * Fassung löschen. Die Antwort enthält alle verbliebenen Fassungen, weil beim
 * Löschen der aktuellen eine andere übernimmt – das lässt sich lokal nicht raten.
 */
export const deleteSummary = createAsyncThunk<
  SummaryView[],
  { recordingId: string; summaryId: string },
  { rejectValue: string }
>('recordings/deleteSummary', async ({ recordingId, summaryId }, { rejectWithValue }) => {
  try {
    return await api<SummaryView[]>(`/api/recordings/${recordingId}/summaries/${summaryId}`, {
      method: 'DELETE',
    });
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

/** Diese Fassung gilt ab jetzt als „die" Zusammenfassung (Download, API, Freigabe, summary.md). */
export const setCurrentSummary = createAsyncThunk<
  SummaryView[],
  { recordingId: string; summaryId: string },
  { rejectValue: string }
>('recordings/setCurrentSummary', async ({ recordingId, summaryId }, { rejectWithValue }) => {
  try {
    return await api<SummaryView[]>(
      `/api/recordings/${recordingId}/summaries/${summaryId}/current`,
      { method: 'POST' },
    );
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const fetchShares = createAsyncThunk<ShareView[], string, { rejectValue: string }>(
  'recordings/fetchShares',
  async (recordingId, { rejectWithValue }) => {
    try {
      return await api<ShareView[]>(`/api/recordings/${recordingId}/shares`);
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

export const addShare = createAsyncThunk<
  ShareView,
  { recordingId: string; userId?: string; groupId?: string },
  { rejectValue: string }
>('recordings/addShare', async ({ recordingId, userId, groupId }, { rejectWithValue }) => {
  try {
    return await api<ShareView>(`/api/recordings/${recordingId}/shares`, {
      method: 'POST',
      body: userId ? { userId } : { groupId },
    });
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const removeShare = createAsyncThunk<
  string,
  { recordingId: string; shareId: string },
  { rejectValue: string }
>('recordings/removeShare', async ({ recordingId, shareId }, { rejectWithValue }) => {
  try {
    await api<void>(`/api/recordings/${recordingId}/shares/${shareId}`, { method: 'DELETE' });
    return shareId;
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const fetchShareLinks = createAsyncThunk<ShareLinkView[], string, { rejectValue: string }>(
  'recordings/fetchShareLinks',
  async (recordingId, { rejectWithValue }) => {
    try {
      return await api<ShareLinkView[]>(`/api/recordings/${recordingId}/share-links`);
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

/**
 * Freigabe-Link erzeugen. `expiresInDays` null = bis zum Widerruf;
 * `requireLogin` true = Empfänger muss sich anmelden (Standard).
 */
export const createShareLink = createAsyncThunk<
  ShareLinkView,
  { recordingId: string; expiresInDays: number | null; requireLogin: boolean },
  { rejectValue: string }
>('recordings/createShareLink', async (
  { recordingId, expiresInDays, requireLogin },
  { rejectWithValue },
) => {
  try {
    return await api<ShareLinkView>(`/api/recordings/${recordingId}/share-links`, {
      method: 'POST',
      body: { expiresInDays, requireLogin },
    });
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const removeShareLink = createAsyncThunk<
  string,
  { recordingId: string; linkId: string },
  { rejectValue: string }
>('recordings/removeShareLink', async ({ recordingId, linkId }, { rejectWithValue }) => {
  try {
    await api<void>(`/api/recordings/${recordingId}/share-links/${linkId}`, { method: 'DELETE' });
    return linkId;
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

/**
 * Job in der Detail-Ansicht aktualisieren oder vorn einfügen. Beim Umstufen
 * eines wartenden Jobs (z.B. "Nur transkribieren" auf den nächtlichen Job)
 * liefert das Backend denselben Job erneut – der darf nicht doppelt erscheinen.
 */
const upsertJob = (state: RecordingsState, job: JobView) => {
  if (!state.detail) return;
  const idx = state.detail.jobs.findIndex((j) => j.id === job.id);
  if (idx >= 0) {
    state.detail.jobs[idx] = job;
  } else {
    state.detail.jobs.unshift(job);
  }
};

/** Geänderte Schlagworte in Liste und Detailansicht nachziehen. */
const applyTags = (state: RecordingsState, recordingId: string, tags: string[]) => {
  const item = state.items.find((r) => r.id === recordingId);
  if (item) item.tags = tags;
  if (state.detail?.recording.id === recordingId) {
    state.detail.recording.tags = tags;
  }
};

const recordingsSlice = createSlice({
  name: 'recordings',
  initialState,
  reducers: {
    clearDetail(state) {
      state.detail = null;
      state.detailError = null;
      state.transcript = null;
      state.transcriptError = null;
      state.shares = [];
      state.sharesError = null;
      state.shareLinks = [];
      state.shareLinksError = null;
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(fetchRecordings.pending, (state, action) => {
        state.loading = true;
        state.error = null;
        state.listRequestId = action.meta.requestId;
      })
      .addCase(fetchRecordings.fulfilled, (state, action) => {
        // Überholte Antwort einer älteren Sucheingabe verwerfen
        if (state.listRequestId !== action.meta.requestId) return;
        state.items = action.payload;
        state.loading = false;
      })
      .addCase(fetchRecordings.rejected, (state, action) => {
        if (state.listRequestId !== action.meta.requestId) return;
        state.loading = false;
        state.error = action.payload ?? translate('errors.recordingsLoad');
      })
      .addCase(fetchTagCounts.fulfilled, (state, action) => {
        state.tags = action.payload;
      })
      .addCase(addRecordingTag.fulfilled, (state, action) => {
        applyTags(state, action.payload.recordingId, action.payload.tags);
      })
      .addCase(removeRecordingTag.fulfilled, (state, action) => {
        applyTags(state, action.payload.recordingId, action.payload.tags);
      })
      .addCase(fetchRecordingDetail.pending, (state, action) => {
        if (!action.meta.arg.silent) {
          state.detailLoading = true;
        }
        state.detailError = null;
      })
      .addCase(fetchRecordingDetail.fulfilled, (state, action) => {
        state.detail = action.payload;
        state.detailLoading = false;
      })
      .addCase(fetchRecordingDetail.rejected, (state, action) => {
        state.detailLoading = false;
        state.detailError = action.payload ?? translate('errors.recordingLoad');
      })
      .addCase(deleteRecording.fulfilled, (state, action) => {
        state.items = state.items.filter((r) => r.id !== action.payload);
      })
      .addCase(processRecording.fulfilled, (state, action) => {
        upsertJob(state, action.payload);
      })
      .addCase(transcribeRecording.fulfilled, (state, action) => {
        upsertJob(state, action.payload);
      })
      .addCase(reprocessRecording.fulfilled, (state, action) => {
        upsertJob(state, action.payload);
      })
      .addCase(updateSummaryOptions.fulfilled, (state, action) => {
        if (state.detail) {
          state.detail.summaryOptions = action.payload;
        }
      })
      .addCase(retranscribeRecording.fulfilled, (state, action) => {
        upsertJob(state, action.payload);
        // Altes Transkript verwerfen, damit nach Abschluss neu geladen wird
        state.transcript = null;
      })
      .addCase(updateParticipant.fulfilled, (state, action) => {
        if (state.detail) {
          state.detail.participants = state.detail.participants.map((p) =>
            p.id === action.payload.id ? action.payload : p,
          );
        }
      })
      .addCase(fetchTranscript.pending, (state) => {
        state.transcriptLoading = true;
        state.transcriptError = null;
      })
      .addCase(fetchTranscript.fulfilled, (state, action) => {
        state.transcript = action.payload;
        state.transcriptLoading = false;
      })
      .addCase(fetchTranscript.rejected, (state, action) => {
        state.transcriptLoading = false;
        state.transcriptError = action.payload ?? translate('errors.transcriptLoad');
      })
      .addCase(updateSummary.fulfilled, (state, action) => {
        if (state.detail) {
          state.detail.summaries = state.detail.summaries.map((s) =>
            s.id === action.payload.id ? action.payload : s,
          );
        }
      })
      .addCase(deleteSummary.fulfilled, (state, action) => {
        if (state.detail) {
          state.detail.summaries = action.payload;
        }
      })
      .addCase(setCurrentSummary.fulfilled, (state, action) => {
        if (state.detail) {
          state.detail.summaries = action.payload;
        }
      })
      .addCase(fetchShares.pending, (state) => {
        state.sharesLoading = true;
        state.sharesError = null;
      })
      .addCase(fetchShares.fulfilled, (state, action) => {
        state.shares = action.payload;
        state.sharesLoading = false;
      })
      .addCase(fetchShares.rejected, (state, action) => {
        state.sharesLoading = false;
        state.sharesError = action.payload ?? translate('errors.sharesLoad');
      })
      .addCase(addShare.fulfilled, (state, action) => {
        state.shares.push(action.payload);
      })
      .addCase(removeShare.fulfilled, (state, action) => {
        state.shares = state.shares.filter((s) => s.id !== action.payload);
      })
      .addCase(fetchShareLinks.pending, (state) => {
        state.shareLinksLoading = true;
        state.shareLinksError = null;
      })
      .addCase(fetchShareLinks.fulfilled, (state, action) => {
        state.shareLinks = action.payload;
        state.shareLinksLoading = false;
      })
      .addCase(fetchShareLinks.rejected, (state, action) => {
        state.shareLinksLoading = false;
        state.shareLinksError = action.payload ?? translate('errors.shareLinksLoad');
      })
      .addCase(createShareLink.fulfilled, (state, action) => {
        // Neueste zuerst - wie die Reihenfolge des Backends
        state.shareLinks.unshift(action.payload);
      })
      .addCase(removeShareLink.fulfilled, (state, action) => {
        state.shareLinks = state.shareLinks.filter((l) => l.id !== action.payload);
      });
  },
});

export const { clearDetail } = recordingsSlice.actions;
export default recordingsSlice.reducer;
