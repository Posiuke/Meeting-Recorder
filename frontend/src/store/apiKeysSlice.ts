import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { api, errorMessage } from '../api/client';
import { translate } from '../i18n';
import type { ApiKeyCreated, ApiKeyView } from '../types';

interface ApiKeysState {
  items: ApiKeyView[];
  loading: boolean;
  error: string | null;
}

const initialState: ApiKeysState = {
  items: [],
  loading: false,
  error: null,
};

export const fetchApiKeys = createAsyncThunk<ApiKeyView[], void, { rejectValue: string }>(
  'apiKeys/fetch',
  async (_, { rejectWithValue }) => {
    try {
      return await api<ApiKeyView[]>('/api/api-keys');
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

export const createApiKey = createAsyncThunk<
  ApiKeyCreated,
  { name: string; readOnly: boolean; expiresAt: string | null },
  { rejectValue: string }
>('apiKeys/create', async (body, { rejectWithValue }) => {
  try {
    return await api<ApiKeyCreated>('/api/api-keys', { method: 'POST', body });
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const deleteApiKey = createAsyncThunk<string, string, { rejectValue: string }>(
  'apiKeys/delete',
  async (id, { rejectWithValue }) => {
    try {
      await api<void>(`/api/api-keys/${id}`, { method: 'DELETE' });
      return id;
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

const apiKeysSlice = createSlice({
  name: 'apiKeys',
  initialState,
  reducers: {},
  extraReducers: (builder) => {
    builder
      .addCase(fetchApiKeys.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchApiKeys.fulfilled, (state, action) => {
        state.items = action.payload;
        state.loading = false;
      })
      .addCase(fetchApiKeys.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload ?? translate('errors.apiKeysLoad');
      })
      .addCase(createApiKey.fulfilled, (state, action) => {
        // Neueste zuerst - wie im Backend sortiert
        state.items = [action.payload.key, ...state.items];
      })
      .addCase(deleteApiKey.fulfilled, (state, action) => {
        state.items = state.items.filter((k) => k.id !== action.payload);
      });
  },
});

export default apiKeysSlice.reducer;
