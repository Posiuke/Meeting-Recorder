import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { api } from '../api/client';

/**
 * Optionale Bereiche dieser Installation (GET /api/public/features).
 * bbbEnabled kommt aus der Umgebungsvariable BBB_ON des Servers: Nur dann gibt
 * es den Bots-Reiter samt Bot-Vorlagen, Bot-Einstellungen und Bot-API-Doku.
 */
interface FeaturesState {
  bbbEnabled: boolean;
  loaded: boolean;
}

interface FeaturesView {
  bbbEnabled: boolean;
}

const initialState: FeaturesState = {
  bbbEnabled: false,
  loaded: false,
};

export const fetchFeatures = createAsyncThunk<FeaturesView>('features/fetch', async () =>
  api<FeaturesView>('/api/public/features'),
);

const featuresSlice = createSlice({
  name: 'features',
  initialState,
  reducers: {},
  extraReducers: (builder) => {
    builder
      .addCase(fetchFeatures.fulfilled, (state, action) => {
        state.bbbEnabled = action.payload.bbbEnabled;
        state.loaded = true;
      })
      // Nicht erreichbar: lieber ohne BBB-Bereiche weiter als gar nicht.
      .addCase(fetchFeatures.rejected, (state) => {
        state.bbbEnabled = false;
        state.loaded = true;
      });
  },
});

export default featuresSlice.reducer;
