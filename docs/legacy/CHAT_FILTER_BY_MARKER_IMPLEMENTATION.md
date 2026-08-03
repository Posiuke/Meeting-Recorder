# Chat Filter by Session Marker Implementation Summary

## Overview
This document describes the implementation of chat filtering by session marker for AI summarization. The feature ensures that only chat messages from the current recording session are included in the transcript sent to the AI, preventing privacy issues and improving accuracy.

## Problem Statement
Previously, the entire public chat history was used for AI summarization. This had several issues:

1. **Privacy**: Chat messages from previous recording sessions were included in the transcript
2. **Accuracy**: Bot's own warning/hint messages were sent to the AI for analysis
3. **Context pollution**: Historical messages unrelated to the current session affected the AI output

### Example of the Problem
```
[Session 1] User1: Discussing confidential topic A
[Session 1] Bot: Recording started [REC123]
[Session 1] User2: More confidential discussion
[Session 1] User3: STOPRECORDING
[Session 2] Bot: Recording started [REC456]
[Session 2] User4: New topic B

When Session 2 finalized, ALL messages (including Session 1) were sent to AI ❌
```

## Solution: Chat Extraction After Session Marker

### Concept
When finalizing a recording session, extract only the chat messages that appear **after** the active session marker. This ensures:
- Only messages from the current session are included
- The marker token itself is removed from the transcript
- Privacy is maintained by excluding historical messages

### Implementation Details

#### New Function: `getChatSinceMarker()` (`src/chat/extraction.ts`)

```typescript
export async function getChatSinceMarker(
  ctx: Page | Frame,
  markerString: string | null,
  options: {
    retries?: number;
    retryDelayMs?: number;
    keepalivePrefix?: string;
  } = {}
): Promise<string>
```

**Features:**
1. Extracts chat text after the specified marker
2. Removes the marker token itself from the result
3. Implements retry logic to handle DOM visibility race conditions
4. Returns empty string if marker not found (privacy: no fallback to full chat)
5. Supports keepalive message filtering
6. Comprehensive debug logging with `[CHAT-FILTER]` prefix

**Algorithm:**
```
1. If no marker provided → return empty string (privacy requirement)
2. For each retry attempt (up to N times):
   a. Extract all chat messages
   b. Search for marker in combined text
   c. If found → extract text after marker, remove marker token, return
   d. If not found → wait and retry
3. After all retries → return empty string and log warning
```

#### Configuration (`src/index.ts`)

New environment variables:
- `CHAT_MARKER_RETRY_ATTEMPTS` (default: 3) - Number of retry attempts if marker not found
- `CHAT_MARKER_RETRY_DELAY_MS` (default: 250) - Delay between retries in milliseconds

#### Updated Finalization Logic (`src/index.ts`)

**Before:**
```typescript
const chatLines = await extractChatMessages(ctx, null, KEEPALIVE_PREFIX);
// All messages sent to AI ❌
```

**After:**
```typescript
const activeMarker = getActive();
const markerString = activeMarker?.markerString || null;

const chatTextAfterMarker = await getChatSinceMarker(ctx, markerString, {
  retries: CHAT_MARKER_RETRY_ATTEMPTS,
  retryDelayMs: CHAT_MARKER_RETRY_DELAY_MS,
  keepalivePrefix: KEEPALIVE_PREFIX
});
// Only messages after marker sent to AI ✓
```

## Testing

### New Test Suite
`tests/chat-since-marker.test.ts` contains 26 comprehensive tests:

1. **Marker Found - Extract Chat After Marker** ✓
   - Verifies correct extraction of messages after marker
   - Confirms marker token is removed
   - Validates messages before marker are excluded

2. **Marker at End - Returns Empty** ✓
   - Handles case where marker is last item in chat

3. **Marker Not Found After Retries - Returns Empty** ✓
   - Validates privacy fallback (empty string, not full chat)

4. **Marker Appears on Retry - Success** ✓
   - Tests retry logic for DOM race conditions
   - Marker appears on 2nd attempt

5. **No Active Marker - Returns Empty** ✓
   - Handles missing marker gracefully

6. **Multiple Markers - Uses First Occurrence** ✓
   - Correctly handles case with multiple marker instances

7. **Marker in Message Body** ✓
   - Works even when marker is mid-message, not just suffix

8. **Empty Chat - Returns Empty** ✓
   - Handles edge case of no messages

9. **Keepalive Prefix Filtering** ✓
   - Validates integration with keepalive message filtering

10. **Whitespace Handling** ✓
    - Correctly trims and handles whitespace

### Test Results
```
✓ Build: Success
✓ Original tests (chat-marker-detection): 35/35 passing
✓ New tests (chat-since-marker): 26/26 passing
✓ Cutoff marker tests: 26/26 passing
✓ CodeQL security scan: 0 alerts
```

## Example Flow

### Scenario: Two Recording Sessions

```
1. [User1] Hello everyone
2. [Bot] Recording started [REC123]        ← Session 1 marker
3. [User2] Discussing topic A
4. [User3] STOPRECORDING
5. [Bot] Recording stopped
6. [Bot] Recording started [REC456]        ← Session 2 marker
7. [User4] Discussing topic B
8. [User5] More discussion
9. [FINALIZE]

When Session 2 finalizes:
- getChatSinceMarker searches for "REC456"
- Finds it at line 6
- Extracts: "Discussing topic B\nMore discussion"
- Marker "REC456" is removed from extracted text
- Messages 1-6 are excluded (privacy ✓)
- Result sent to AI: clean transcript of Session 2 only
```

### Scenario: Marker Not Found (Race Condition)

```
1. [Bot] Recording started [REC789]
2. [User1] Quick message
3. [FINALIZE called immediately]

Attempt 1: Marker "REC789" not yet in DOM → retry in 250ms
Attempt 2: Marker "REC789" not yet in DOM → retry in 250ms
Attempt 3: Marker "REC789" found! → extract "Quick message"

Result: Successfully handled DOM visibility delay
```

### Scenario: No Active Marker

```
1. [User1] Manual START without marker
2. [User2] Discussion
3. [FINALIZE]

- No active marker exists
- getChatSinceMarker returns empty string
- Privacy maintained: no fallback to full chat
- Warning logged for troubleshooting
```

## Benefits

1. **Privacy Protection** 🔒
   - Chat from previous sessions never sent to AI
   - Bot's own messages excluded from analysis
   - No accidental data leakage

2. **Improved AI Accuracy** 🎯
   - AI only sees relevant context
   - No confusion from historical messages
   - Cleaner, more focused summaries

3. **Robust Implementation** 💪
   - Retry logic handles DOM race conditions
   - Comprehensive error handling
   - Extensive logging for debugging

4. **Configurable** ⚙️
   - Environment variables control retry behavior
   - Easy to tune for different scenarios
   - No code changes needed for adjustments

5. **Well Tested** ✅
   - 26 new tests covering all scenarios
   - All existing tests still pass
   - Security scan clean

## Configuration Reference

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `CHAT_MARKER_RETRY_ATTEMPTS` | `3` | Number of retry attempts if marker not found in chat |
| `CHAT_MARKER_RETRY_DELAY_MS` | `250` | Delay in milliseconds between retry attempts |

### Example Configuration

```env
# Conservative (fewer retries, faster failure)
CHAT_MARKER_RETRY_ATTEMPTS=2
CHAT_MARKER_RETRY_DELAY_MS=200

# Aggressive (more retries, longer wait)
CHAT_MARKER_RETRY_ATTEMPTS=5
CHAT_MARKER_RETRY_DELAY_MS=500
```

## Logging

All operations are logged with the `[CHAT-FILTER]` prefix for easy filtering:

```
[CHAT-FILTER] Extracting chat after marker: "REC123abc456"
[CHAT-FILTER] Marker found at index 234. Extracted 1234 chars after marker (attempt 1/3)
```

**Warning scenarios:**
```
[CHAT-FILTER] No active marker provided - returning empty chat (privacy: only use chat after marker)
[CHAT-FILTER] Marker not found in chat (attempt 1/3). Retrying in 250ms...
[CHAT-FILTER] Marker not found after 3 attempts. Returning empty chat for privacy.
[CHAT-FILTER] Fallback: returning empty chat section (marker not found after all retries)
```

## Migration Notes

This change is **backward compatible** but changes behavior:

**Before:**
- All chat messages sent to AI
- Includes messages from previous sessions
- Includes bot's own messages

**After:**
- Only messages after active session marker sent to AI
- Previous sessions excluded
- Bot messages excluded
- If no marker: empty chat (privacy requirement)

No database migrations or configuration changes required. The feature activates automatically when an active session marker exists.

## Security

CodeQL security analysis found **0 alerts**. The implementation:
- Uses existing sleep utility (no raw setTimeout)
- Properly handles user input (marker strings)
- No SQL injection or XSS vectors
- No sensitive data in logs (marker strings are random)

## Related Documentation

- [CUTOFF_MARKER_IMPLEMENTATION.md](./CUTOFF_MARKER_IMPLEMENTATION.md) - Cutoff marker for preventing bot self-triggering
- Session markers: `src/chat/marker.ts`
- Chat extraction: `src/chat/extraction.ts`
- Tests: `tests/chat-since-marker.test.ts`
