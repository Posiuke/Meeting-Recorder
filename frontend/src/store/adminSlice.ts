import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { api, errorMessage } from '../api/client';
import { translate } from '../i18n';
import type { LdapTestResult, UserView } from '../types';

type SettingsMap = Record<string, string>;

interface AdminState {
  settings: SettingsMap | null;
  defaults: SettingsMap | null;
  settingsLoading: boolean;
  settingsError: string | null;
  authConfig: SettingsMap | null;
  authLoading: boolean;
  authError: string | null;
  users: UserView[];
  usersLoading: boolean;
  usersError: string | null;
}

const initialState: AdminState = {
  settings: null,
  defaults: null,
  settingsLoading: false,
  settingsError: null,
  authConfig: null,
  authLoading: false,
  authError: null,
  users: [],
  usersLoading: false,
  usersError: null,
};

export const fetchAuthConfig = createAsyncThunk<SettingsMap, void, { rejectValue: string }>(
  'admin/fetchAuthConfig',
  async (_, { rejectWithValue }) => {
    try {
      return await api<SettingsMap>('/api/admin/auth');
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

export const saveAuthConfig = createAsyncThunk<SettingsMap, SettingsMap, { rejectValue: string }>(
  'admin/saveAuthConfig',
  async (changes, { rejectWithValue }) => {
    try {
      return await api<SettingsMap>('/api/admin/auth', { method: 'PUT', body: changes });
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

export const testLdap = createAsyncThunk<
  LdapTestResult,
  { username: string; password: string },
  { rejectValue: string }
>('admin/testLdap', async (body, { rejectWithValue }) => {
  try {
    return await api<LdapTestResult>('/api/admin/auth/test', { method: 'POST', body });
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const fetchSettings = createAsyncThunk<
  { settings: SettingsMap; defaults: SettingsMap },
  void,
  { rejectValue: string }
>('admin/fetchSettings', async (_, { rejectWithValue }) => {
  try {
    const [settings, defaults] = await Promise.all([
      api<SettingsMap>('/api/admin/settings'),
      api<SettingsMap>('/api/admin/settings/defaults'),
    ]);
    return { settings, defaults };
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const saveSettings = createAsyncThunk<SettingsMap, SettingsMap, { rejectValue: string }>(
  'admin/saveSettings',
  async (changed, { rejectWithValue }) => {
    try {
      return await api<SettingsMap>('/api/admin/settings', { method: 'PUT', body: changed });
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

export const fetchAdminUsers = createAsyncThunk<UserView[], void, { rejectValue: string }>(
  'admin/fetchUsers',
  async (_, { rejectWithValue }) => {
    try {
      return await api<UserView[]>('/api/admin/users');
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

export const setUserAdmin = createAsyncThunk<
  UserView,
  { userId: string; admin: boolean },
  { rejectValue: string }
>('admin/setUserAdmin', async ({ userId, admin }, { rejectWithValue }) => {
  try {
    return await api<UserView>(`/api/admin/users/${userId}/admin`, {
      method: 'PUT',
      body: { admin },
    });
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

const adminSlice = createSlice({
  name: 'admin',
  initialState,
  reducers: {},
  extraReducers: (builder) => {
    builder
      .addCase(fetchSettings.pending, (state) => {
        state.settingsLoading = true;
        state.settingsError = null;
      })
      .addCase(fetchSettings.fulfilled, (state, action) => {
        state.settings = action.payload.settings;
        state.defaults = action.payload.defaults;
        state.settingsLoading = false;
      })
      .addCase(fetchSettings.rejected, (state, action) => {
        state.settingsLoading = false;
        state.settingsError = action.payload ?? translate('errors.settingsLoad');
      })
      .addCase(saveSettings.fulfilled, (state, action) => {
        state.settings = { ...state.settings, ...action.payload };
      })
      .addCase(fetchAuthConfig.pending, (state) => {
        state.authLoading = true;
        state.authError = null;
      })
      .addCase(fetchAuthConfig.fulfilled, (state, action) => {
        state.authConfig = action.payload;
        state.authLoading = false;
      })
      .addCase(fetchAuthConfig.rejected, (state, action) => {
        state.authLoading = false;
        state.authError = action.payload ?? translate('errors.authConfigLoad');
      })
      .addCase(saveAuthConfig.fulfilled, (state, action) => {
        state.authConfig = action.payload;
      })
      .addCase(fetchAdminUsers.pending, (state) => {
        state.usersLoading = true;
        state.usersError = null;
      })
      .addCase(fetchAdminUsers.fulfilled, (state, action) => {
        state.users = action.payload;
        state.usersLoading = false;
      })
      .addCase(fetchAdminUsers.rejected, (state, action) => {
        state.usersLoading = false;
        state.usersError = action.payload ?? translate('errors.usersLoad');
      })
      .addCase(setUserAdmin.fulfilled, (state, action) => {
        const idx = state.users.findIndex((u) => u.id === action.payload.id);
        if (idx >= 0) {
          state.users[idx] = action.payload;
        }
      });
  },
});

export default adminSlice.reducer;
