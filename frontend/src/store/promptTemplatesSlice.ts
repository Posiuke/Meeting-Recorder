import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { api, errorMessage } from '../api/client';
import { translate } from '../i18n';
import type { PromptTemplateView } from '../types';

/**
 * Persönliche Promptvorlagen des angemeldeten Nutzers: benannte
 * Auswertungs-Prompts, die im Dialog "Auswertung anpassen" wiederverwendet
 * werden können.
 */
interface PromptTemplatesState {
  items: PromptTemplateView[];
  loading: boolean;
  loaded: boolean;
  error: string | null;
}

const initialState: PromptTemplatesState = {
  items: [],
  loading: false,
  loaded: false,
  error: null,
};

export const fetchPromptTemplates = createAsyncThunk<
  PromptTemplateView[],
  void,
  { rejectValue: string }
>('promptTemplates/fetch', async (_, { rejectWithValue }) => {
  try {
    return await api<PromptTemplateView[]>('/api/prompt-templates');
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const createPromptTemplate = createAsyncThunk<
  PromptTemplateView,
  { name: string; prompt: string },
  { rejectValue: string }
>('promptTemplates/create', async (body, { rejectWithValue }) => {
  try {
    return await api<PromptTemplateView>('/api/prompt-templates', { method: 'POST', body });
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const updatePromptTemplate = createAsyncThunk<
  PromptTemplateView,
  { id: string; name: string; prompt: string },
  { rejectValue: string }
>('promptTemplates/update', async ({ id, name, prompt }, { rejectWithValue }) => {
  try {
    return await api<PromptTemplateView>(`/api/prompt-templates/${id}`, {
      method: 'PUT',
      body: { name, prompt },
    });
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const deletePromptTemplate = createAsyncThunk<string, string, { rejectValue: string }>(
  'promptTemplates/delete',
  async (id, { rejectWithValue }) => {
    try {
      await api<void>(`/api/prompt-templates/${id}`, { method: 'DELETE' });
      return id;
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

const sortByName = (items: PromptTemplateView[]) =>
  [...items].sort((a, b) => a.name.localeCompare(b.name, 'de', { sensitivity: 'base' }));

const promptTemplatesSlice = createSlice({
  name: 'promptTemplates',
  initialState,
  reducers: {},
  extraReducers: (builder) => {
    builder
      .addCase(fetchPromptTemplates.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchPromptTemplates.fulfilled, (state, action) => {
        state.items = action.payload;
        state.loading = false;
        state.loaded = true;
      })
      .addCase(fetchPromptTemplates.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload ?? translate('errors.templatesLoad');
      })
      .addCase(createPromptTemplate.fulfilled, (state, action) => {
        state.items = sortByName([...state.items, action.payload]);
      })
      .addCase(updatePromptTemplate.fulfilled, (state, action) => {
        state.items = sortByName(
          state.items.map((t) => (t.id === action.payload.id ? action.payload : t)),
        );
      })
      .addCase(deletePromptTemplate.fulfilled, (state, action) => {
        state.items = state.items.filter((t) => t.id !== action.payload);
      });
  },
});

export default promptTemplatesSlice.reducer;
