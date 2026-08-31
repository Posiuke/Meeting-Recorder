import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { api, errorMessage } from '../api/client';
import { translate } from '../i18n';
import type { BotTemplateRequest, BotTemplateView } from '../types';

/**
 * Persönliche Bot-Vorlagen des angemeldeten Nutzers: benannte Meetingräume samt
 * Einstellungen, aus denen sich ein Bot ohne weitere Eingabe starten lässt. Der
 * Server liefert ausschließlich die eigenen Vorlagen.
 */
interface BotTemplatesState {
  items: BotTemplateView[];
  loading: boolean;
  loaded: boolean;
  error: string | null;
}

const initialState: BotTemplatesState = {
  items: [],
  loading: false,
  loaded: false,
  error: null,
};

export const fetchBotTemplates = createAsyncThunk<
  BotTemplateView[],
  void,
  { rejectValue: string }
>('botTemplates/fetch', async (_, { rejectWithValue }) => {
  try {
    return await api<BotTemplateView[]>('/api/bot-templates');
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const createBotTemplate = createAsyncThunk<
  BotTemplateView,
  BotTemplateRequest,
  { rejectValue: string }
>('botTemplates/create', async (body, { rejectWithValue }) => {
  try {
    return await api<BotTemplateView>('/api/bot-templates', { method: 'POST', body });
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const updateBotTemplate = createAsyncThunk<
  BotTemplateView,
  { id: string } & BotTemplateRequest,
  { rejectValue: string }
>('botTemplates/update', async ({ id, ...body }, { rejectWithValue }) => {
  try {
    return await api<BotTemplateView>(`/api/bot-templates/${id}`, { method: 'PUT', body });
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const deleteBotTemplate = createAsyncThunk<string, string, { rejectValue: string }>(
  'botTemplates/delete',
  async (id, { rejectWithValue }) => {
    try {
      await api<void>(`/api/bot-templates/${id}`, { method: 'DELETE' });
      return id;
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

const sortByName = (items: BotTemplateView[]) =>
  [...items].sort((a, b) => a.name.localeCompare(b.name, 'de', { sensitivity: 'base' }));

const botTemplatesSlice = createSlice({
  name: 'botTemplates',
  initialState,
  reducers: {},
  extraReducers: (builder) => {
    builder
      .addCase(fetchBotTemplates.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchBotTemplates.fulfilled, (state, action) => {
        state.items = action.payload;
        state.loading = false;
        state.loaded = true;
      })
      .addCase(fetchBotTemplates.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload ?? translate('errors.botTemplatesLoad');
      })
      .addCase(createBotTemplate.fulfilled, (state, action) => {
        state.items = sortByName([...state.items, action.payload]);
      })
      .addCase(updateBotTemplate.fulfilled, (state, action) => {
        state.items = sortByName(
          state.items.map((t) => (t.id === action.payload.id ? action.payload : t)),
        );
      })
      .addCase(deleteBotTemplate.fulfilled, (state, action) => {
        state.items = state.items.filter((t) => t.id !== action.payload);
      });
  },
});

export default botTemplatesSlice.reducer;
