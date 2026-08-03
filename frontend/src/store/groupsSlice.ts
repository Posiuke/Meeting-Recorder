import { createAsyncThunk, createSlice } from '@reduxjs/toolkit';
import { api, errorMessage } from '../api/client';
import { translate } from '../i18n';
import type { GroupMemberView, GroupView } from '../types';

interface GroupsState {
  items: GroupView[];
  loading: boolean;
  loaded: boolean;
  error: string | null;
  membersByGroup: Record<string, GroupMemberView[]>;
  membersLoading: Record<string, boolean>;
}

const initialState: GroupsState = {
  items: [],
  loading: false,
  loaded: false,
  error: null,
  membersByGroup: {},
  membersLoading: {},
};

export const fetchGroups = createAsyncThunk<GroupView[], void, { rejectValue: string }>(
  'groups/fetch',
  async (_, { rejectWithValue }) => {
    try {
      return await api<GroupView[]>('/api/groups');
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

export const createGroup = createAsyncThunk<GroupView, string, { rejectValue: string }>(
  'groups/create',
  async (name, { rejectWithValue }) => {
    try {
      return await api<GroupView>('/api/groups', { method: 'POST', body: { name } });
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

export const deleteGroup = createAsyncThunk<string, string, { rejectValue: string }>(
  'groups/delete',
  async (groupId, { rejectWithValue }) => {
    try {
      await api<void>(`/api/groups/${groupId}`, { method: 'DELETE' });
      return groupId;
    } catch (e) {
      return rejectWithValue(errorMessage(e));
    }
  },
);

export const fetchGroupMembers = createAsyncThunk<
  { groupId: string; members: GroupMemberView[] },
  string,
  { rejectValue: string }
>('groups/fetchMembers', async (groupId, { rejectWithValue }) => {
  try {
    const members = await api<GroupMemberView[]>(`/api/groups/${groupId}/members`);
    return { groupId, members };
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const addGroupMember = createAsyncThunk<
  void,
  { groupId: string; userId: string },
  { rejectValue: string }
>('groups/addMember', async ({ groupId, userId }, { rejectWithValue }) => {
  try {
    await api<unknown>(`/api/groups/${groupId}/members`, {
      method: 'POST',
      body: { userId },
    });
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

export const removeGroupMember = createAsyncThunk<
  { groupId: string; userId: string },
  { groupId: string; userId: string },
  { rejectValue: string }
>('groups/removeMember', async ({ groupId, userId }, { rejectWithValue }) => {
  try {
    await api<void>(`/api/groups/${groupId}/members/${userId}`, { method: 'DELETE' });
    return { groupId, userId };
  } catch (e) {
    return rejectWithValue(errorMessage(e));
  }
});

const groupsSlice = createSlice({
  name: 'groups',
  initialState,
  reducers: {},
  extraReducers: (builder) => {
    builder
      .addCase(fetchGroups.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchGroups.fulfilled, (state, action) => {
        state.items = action.payload;
        state.loading = false;
        state.loaded = true;
      })
      .addCase(fetchGroups.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload ?? translate('errors.groupsLoad');
      })
      .addCase(createGroup.fulfilled, (state, action) => {
        state.items.push(action.payload);
      })
      .addCase(deleteGroup.fulfilled, (state, action) => {
        state.items = state.items.filter((g) => g.id !== action.payload);
        delete state.membersByGroup[action.payload];
      })
      .addCase(fetchGroupMembers.pending, (state, action) => {
        state.membersLoading[action.meta.arg] = true;
      })
      .addCase(fetchGroupMembers.fulfilled, (state, action) => {
        state.membersByGroup[action.payload.groupId] = action.payload.members;
        state.membersLoading[action.payload.groupId] = false;
      })
      .addCase(fetchGroupMembers.rejected, (state, action) => {
        state.membersLoading[action.meta.arg] = false;
      })
      .addCase(removeGroupMember.fulfilled, (state, action) => {
        const { groupId, userId } = action.payload;
        const members = state.membersByGroup[groupId];
        if (members) {
          state.membersByGroup[groupId] = members.filter((m) => m.userId !== userId);
        }
      });
  },
});

export default groupsSlice.reducer;
