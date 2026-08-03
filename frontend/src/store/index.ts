import { configureStore } from '@reduxjs/toolkit';
import auth from './authSlice';
import bots from './botsSlice';
import recordings from './recordingsSlice';
import groups from './groupsSlice';
import admin from './adminSlice';
import promptTemplates from './promptTemplatesSlice';
import glossary from './glossarySlice';
import apiKeys from './apiKeysSlice';

export const store = configureStore({
  reducer: {
    auth,
    bots,
    recordings,
    groups,
    admin,
    promptTemplates,
    glossary,
    apiKeys,
  },
});

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;
