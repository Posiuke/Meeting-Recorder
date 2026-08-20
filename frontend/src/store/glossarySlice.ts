import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { api, errorMessage, glossaryPath } from '../api/client';
import { translate } from '../i18n';
import type { GlossaryEntryView, GlossaryScope } from '../types';

interface GlossaryList {
  items: GlossaryEntryView[];
  loading: boolean;
  error: string | null;
}

/**
 * Zwei Listen nebeneinander: das persönliche Glossar und das gemeinsame der
 * Installation. Beide durchlaufen denselben Ablauf, darum tragen die Thunks den
 * Geltungsbereich mit statt ihn zu verdoppeln.
 */
interface GlossaryState {
  personal: GlossaryList;
  shared: GlossaryList;
}

const emptyList: GlossaryList = { items: [], loading: false, error: null };

const initialState: GlossaryState = {
  personal: { ...emptyList },
  shared: { ...emptyList },
};

export const fetchGlossary = createAsyncThunk<
  GlossaryEntryView[],
  GlossaryScope,
  { rejectValue: string }
>('glossary/fetch', async (scope, { rejectWithValue }) => {
  try {
    return await api<GlossaryEntryView[]>(glossaryPath(scope));
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const createGlossaryEntry = createAsyncThunk<
  GlossaryEntryView,
  { scope: GlossaryScope; term: string; meaning: string | null },
  { rejectValue: string }
>('glossary/create', async ({ scope, term, meaning }, { rejectWithValue }) => {
  try {
    return await api<GlossaryEntryView>(glossaryPath(scope), {
      method: 'POST',
      body: { term, meaning },
    });
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const updateGlossaryEntry = createAsyncThunk<
  GlossaryEntryView,
  { scope: GlossaryScope; id: string; term: string; meaning: string | null },
  { rejectValue: string }
>('glossary/update', async ({ scope, id, term, meaning }, { rejectWithValue }) => {
  try {
    return await api<GlossaryEntryView>(`${glossaryPath(scope)}/${id}`, {
      method: 'PUT',
      body: { term, meaning },
    });
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const deleteGlossaryEntry = createAsyncThunk<
  string,
  { scope: GlossaryScope; id: string },
  { rejectValue: string }
>('glossary/delete', async ({ scope, id }, { rejectWithValue }) => {
  try {
    await api<void>(`${glossaryPath(scope)}/${id}`, { method: 'DELETE' });
    return id;
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

/** Alphabetisch wie im Backend (nach Vergleichsform des Begriffs). */
const sortEntries = (entries: GlossaryEntryView[]) =>
  [...entries].sort((a, b) => a.term.localeCompare(b.term, 'de', { sensitivity: 'base' }));

const glossarySlice = createSlice({
  name: 'glossary',
  initialState,
  reducers: {},
  extraReducers: (builder) => {
    builder
      .addCase(fetchGlossary.pending, (state, action) => {
        const list = state[action.meta.arg];
        list.loading = true;
        list.error = null;
      })
      .addCase(fetchGlossary.fulfilled, (state, action) => {
        const list = state[action.meta.arg];
        list.items = action.payload;
        list.loading = false;
      })
      .addCase(fetchGlossary.rejected, (state, action) => {
        const list = state[action.meta.arg];
        list.loading = false;
        list.error = action.payload ?? translate('errors.glossaryLoad');
      })
      .addCase(createGlossaryEntry.fulfilled, (state, action) => {
        const list = state[action.meta.arg.scope];
        list.items = sortEntries([...list.items, action.payload]);
      })
      .addCase(updateGlossaryEntry.fulfilled, (state, action) => {
        const list = state[action.meta.arg.scope];
        list.items = sortEntries(
          list.items.map((e) => (e.id === action.payload.id ? action.payload : e)),
        );
      })
      .addCase(deleteGlossaryEntry.fulfilled, (state, action) => {
        const list = state[action.meta.arg.scope];
        list.items = list.items.filter((e) => e.id !== action.payload);
      });
  },
});

export default glossarySlice.reducer;
