import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { api, ApiError, errorMessage, getToken, setToken } from '../api/client';
import { setLanguage, translate } from '../i18n';
import type { Language } from '../i18n';
import type { LoginResponse, UserView } from '../types';

export type AuthStatus = 'idle' | 'loading' | 'authenticated' | 'unauthenticated';

interface AuthState {
  user: UserView | null;
  token: string | null;
  /** idle = Token vorhanden, aber noch nicht validiert */
  status: AuthStatus;
  error: string | null;
}

const storedToken = getToken();

const initialState: AuthState = {
  user: null,
  token: storedToken,
  status: storedToken ? 'idle' : 'unauthenticated',
  error: null,
};

export const login = createAsyncThunk<
  LoginResponse,
  { username: string; password: string },
  { rejectValue: string }
>('auth/login', async (credentials, { rejectWithValue }) => {
  try {
    return await api<LoginResponse>('/api/auth/login', {
      method: 'POST',
      body: credentials,
    });
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      return rejectWithValue(translate('login.failedCredentials'));
    }
    return rejectWithValue(errorMessage(e));
  }
});

/**
 * Sprache umstellen: sofort in der Oberfläche wirksam und am Konto gespeichert,
 * damit sie auf jedem Gerät gilt. Schlägt das Speichern fehl (z.B. Netz weg),
 * bleibt die Umstellung für diese Sitzung trotzdem bestehen.
 */
export const saveLanguage = createAsyncThunk<UserView | null, Language, { rejectValue: string }>(
  'auth/saveLanguage',
  async (language, { rejectWithValue, getState }) => {
    setLanguage(language);
    const state = getState() as { auth: { status: string } };
    if (state.auth.status !== 'authenticated') return null;
    try {
      return await api<UserView>('/api/users/me/language', { method: 'PUT', body: { language } });
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

export const fetchMe = createAsyncThunk<UserView, void, { rejectValue: string }>(
  'auth/fetchMe',
  async (_, { rejectWithValue }) => {
    try {
      return await api<UserView>('/api/auth/me');
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

export const changePassword = createAsyncThunk<
  UserView,
  { currentPassword: string; newPassword: string },
  { rejectValue: string }
>('auth/changePassword', async (body, { rejectWithValue }) => {
  try {
    return await api<UserView>('/api/auth/change-password', { method: 'POST', body });
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

const authSlice = createSlice({
  name: 'auth',
  initialState,
  reducers: {
    loggedOut(state) {
      setToken(null);
      state.user = null;
      state.token = null;
      state.status = 'unauthenticated';
      state.error = null;
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(login.pending, (state) => {
        state.error = null;
      })
      .addCase(login.fulfilled, (state, action) => {
        setToken(action.payload.token);
        state.token = action.payload.token;
        state.user = action.payload.user;
        state.status = 'authenticated';
        state.error = null;
      })
      .addCase(login.rejected, (state, action) => {
        state.status = 'unauthenticated';
        state.error = action.payload ?? translate('login.failed');
      })
      .addCase(fetchMe.pending, (state) => {
        state.status = 'loading';
      })
      .addCase(fetchMe.fulfilled, (state, action) => {
        state.user = action.payload;
        state.status = 'authenticated';
      })
      .addCase(fetchMe.rejected, (state) => {
        setToken(null);
        state.user = null;
        state.token = null;
        state.status = 'unauthenticated';
      })
      .addCase(changePassword.fulfilled, (state, action) => {
        state.user = action.payload;
      })
      .addCase(saveLanguage.fulfilled, (state, action) => {
        if (action.payload) state.user = action.payload;
      });
  },
});

export const { loggedOut } = authSlice.actions;
export default authSlice.reducer;
