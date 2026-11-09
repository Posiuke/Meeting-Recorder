import { configureStore } from '@reduxjs/toolkit';
import auth from './authSlice';
import bots from './botsSlice';
import botTemplates from './botTemplatesSlice';
import recordings from './recordingsSlice';
import groups from './groupsSlice';
import admin from './adminSlice';
import promptTemplates from './promptTemplatesSlice';
import glossary from './glossarySlice';
import apiKeys from './apiKeysSlice';
import features from './featuresSlice';

export const store = configureStore({
  reducer: {
    auth,
    bots,
    botTemplates,
    recordings,
    groups,
    admin,
    promptTemplates,
    glossary,
    apiKeys,
    features,
  },
});

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;
