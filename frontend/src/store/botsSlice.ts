import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { api, errorMessage } from '../api/client';
import { translate } from '../i18n';
import type { BotSessionHistoryView, BotView, CreateBotRequest } from '../types';

interface BotsState {
  items: BotView[];
  loading: boolean;
  loaded: boolean;
  error: string | null;
  history: BotSessionHistoryView[];
  historyLoading: boolean;
  historyError: string | null;
}

const initialState: BotsState = {
  items: [],
  loading: false,
  loaded: false,
  error: null,
  history: [],
  historyLoading: false,
  historyError: null,
};

/** Arg: silent = true beim Polling (kein Lade-Spinner). */
export const fetchBots = createAsyncThunk<BotView[], boolean, { rejectValue: string }>(
  'bots/fetch',
  async (_silent, { rejectWithValue }) => {
    try {
      return await api<BotView[]>('/api/bots');
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

export const createBot = createAsyncThunk<BotView, CreateBotRequest, { rejectValue: string }>(
  'bots/create',
  async (request, { rejectWithValue }) => {
    try {
      return await api<BotView>('/api/bots', { method: 'POST', body: request });
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

export const stopBot = createAsyncThunk<string, string, { rejectValue: string }>(
  'bots/stop',
  async (sessionId, { rejectWithValue }) => {
    try {
      await api<void>(`/api/bots/${sessionId}`, { method: 'DELETE' });
      return sessionId;
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

export const startBotRecording = createAsyncThunk<void, string, { rejectValue: string }>(
  'bots/startRecording',
  async (sessionId, { rejectWithValue }) => {
    try {
      await api<void>(`/api/bots/${sessionId}/recording/start`, { method: 'POST' });
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

export const stopBotRecording = createAsyncThunk<
  void,
  { sessionId: string; discard: boolean },
  { rejectValue: string }
>('bots/stopRecording', async ({ sessionId, discard }, { rejectWithValue }) => {
  try {
    await api<void>(`/api/bots/${sessionId}/recording/stop?discard=${discard}`, {
      method: 'POST',
    });
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const fetchBotHistory = createAsyncThunk<
  BotSessionHistoryView[],
  void,
  { rejectValue: string }
>('bots/fetchHistory', async (_, { rejectWithValue }) => {
  try {
    return await api<BotSessionHistoryView[]>('/api/bots/history');
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

const botsSlice = createSlice({
  name: 'bots',
  initialState,
  reducers: {},
  extraReducers: (builder) => {
    builder
      .addCase(fetchBots.pending, (state, action) => {
        if (!action.meta.arg) {
          state.loading = true;
        }
        state.error = null;
      })
      .addCase(fetchBots.fulfilled, (state, action) => {
        state.items = action.payload;
        state.loading = false;
        state.loaded = true;
      })
      .addCase(fetchBots.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload ?? translate('errors.botsLoad');
      })
      .addCase(createBot.fulfilled, (state, action) => {
        const idx = state.items.findIndex((b) => b.sessionId === action.payload.sessionId);
        if (idx >= 0) {
          state.items[idx] = action.payload;
        } else {
          state.items.unshift(action.payload);
        }
      })
      .addCase(stopBot.fulfilled, (state, action) => {
        state.items = state.items.filter((b) => b.sessionId !== action.payload);
      })
      .addCase(fetchBotHistory.pending, (state) => {
        state.historyLoading = true;
        state.historyError = null;
      })
      .addCase(fetchBotHistory.fulfilled, (state, action) => {
        state.history = action.payload;
        state.historyLoading = false;
      })
      .addCase(fetchBotHistory.rejected, (state, action) => {
        state.historyLoading = false;
        state.historyError = action.payload ?? translate('errors.historyLoad');
      });
  },
});

export default botsSlice.reducer;
