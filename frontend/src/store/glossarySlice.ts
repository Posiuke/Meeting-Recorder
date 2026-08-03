import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { api, errorMessage } from '../api/client';
import { translate } from '../i18n';
import type { GlossaryEntryView } from '../types';

interface GlossaryState {
  items: GlossaryEntryView[];
  loading: boolean;
  error: string | null;
}

const initialState: GlossaryState = {
  items: [],
  loading: false,
  error: null,
};

export const fetchGlossary = createAsyncThunk<GlossaryEntryView[], void, { rejectValue: string }>(
  'glossary/fetch',
  async (_, { rejectWithValue }) => {
    try {
      return await api<GlossaryEntryView[]>('/api/glossary');
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

export const createGlossaryEntry = createAsyncThunk<
  GlossaryEntryView,
  { term: string; meaning: string | null },
  { rejectValue: string }
>('glossary/create', async (body, { rejectWithValue }) => {
  try {
    return await api<GlossaryEntryView>('/api/glossary', { method: 'POST', body });
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const updateGlossaryEntry = createAsyncThunk<
  GlossaryEntryView,
  { id: string; term: string; meaning: string | null },
  { rejectValue: string }
>('glossary/update', async ({ id, term, meaning }, { rejectWithValue }) => {
  try {
    return await api<GlossaryEntryView>(`/api/glossary/${id}`, {
      method: 'PUT',
      body: { term, meaning },
    });
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const deleteGlossaryEntry = createAsyncThunk<string, string, { rejectValue: string }>(
  'glossary/delete',
  async (id, { rejectWithValue }) => {
    try {
      await api<void>(`/api/glossary/${id}`, { method: 'DELETE' });
      return id;
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

/** Alphabetisch wie im Backend (nach Vergleichsform des Begriffs). */
const sortEntries = (entries: GlossaryEntryView[]) =>
  [...entries].sort((a, b) => a.term.localeCompare(b.term, 'de', { sensitivity: 'base' }));

const glossarySlice = createSlice({
  name: 'glossary',
  initialState,
  reducers: {},
  extraReducers: (builder) => {
    builder
      .addCase(fetchGlossary.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchGlossary.fulfilled, (state, action) => {
        state.items = action.payload;
        state.loading = false;
      })
      .addCase(fetchGlossary.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload ?? translate('errors.glossaryLoad');
      })
      .addCase(createGlossaryEntry.fulfilled, (state, action) => {
        state.items = sortEntries([...state.items, action.payload]);
      })
      .addCase(updateGlossaryEntry.fulfilled, (state, action) => {
        state.items = sortEntries(
          state.items.map((e) => (e.id === action.payload.id ? action.payload : e)),
        );
      })
      .addCase(deleteGlossaryEntry.fulfilled, (state, action) => {
        state.items = state.items.filter((e) => e.id !== action.payload);
      });
  },
});

export default glossarySlice.reducer;
