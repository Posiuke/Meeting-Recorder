# Upload Decision Layer

## Overview

The upload decision layer prevents empty or meaningless meetings from being uploaded to Gitea. It evaluates multiple criteria to determine if a recording contains substantial content worth preserving.

## How It Works

When a recording stops, the system:

1. **Collects Audio Metadata** - Scans the recordings directory for MP3 files matching the session prefix
   - File size (always available)
   - Duration in milliseconds (when ffprobe is available)

2. **Reads Transcript** - Checks for `{sessionPrefix}_transcript.txt` in the recordings directory
   - If present, reads the content
   - If absent, treats as empty string

3. **Analyzes Chat** - Uses the already-extracted chat content after the session marker
   - Only considers messages posted after the session marker
   - Filters out keepalive messages

4. **Evaluates Criteria** - Uses OR logic (any criterion passing triggers upload):
   - Audio duration ≥ 60 seconds (1 minute)
   - Transcript length ≥ 50 characters
   - Chat length ≥ 20 characters

5. **Takes Action**:
   - **If upload is decided**: Proceeds with normal summarization and Gitea upload
   - **If upload is skipped**: Writes `{sessionPrefix}_upload-skip-note.txt` with reasons and metrics

## Default Criteria

```typescript
const DEFAULT_CRITERIA = {
  minAudioDurationMs: 60000,    // 1 minute
  minTranscriptChars: 50,       // 50 characters
  minChatChars: 20,             // 20 characters
};
```

## Example Scenarios

### ✅ Will Upload

1. **Long meeting** - 5 minutes of audio, minimal chat/transcript
2. **Discussion-heavy** - 30 seconds audio, but 100+ character transcript
3. **Chat-based** - Short audio, but substantial chat discussion (≥20 chars)
4. **Normal meeting** - Any combination meeting minimum criteria

### ❌ Will Skip

1. **Empty meeting** - Bot joined, no one else joined, 10 seconds silence
2. **Technical test** - "Test 1 2 3", 15 seconds audio, no meaningful chat
3. **False start** - Meeting joined then immediately cancelled
4. **Brief interruption** - < 1 minute, no meaningful content

## Skip Note Example

When upload is skipped, a note file is created:

```
Upload skipped for session: test_meeting_20231204
Time: 2023-12-04T10:30:45.123Z

Metrics:
  Total audio duration: 15.0s
  Total audio size: 50000 bytes
  Transcript length: 12 chars
  Chat length: 7 chars

Reasons:
  - Audio duration 15.0s below minimum 60.0s
  - Transcript length 12 chars below minimum 50 chars
  - Chat length 7 chars below minimum 20 chars

This meeting was considered to have no meaningful content and was not uploaded to Gitea.
```

## Configuration

The criteria can be customized by modifying the call to `finalizeAndMaybeUpload` in `src/index.ts`:

```typescript
await finalizeAndMaybeUpload({
  recordingsDir: OUTPUT_DIR,
  sessionPrefix,
  chatText: chatTextAfterMarker,
  ffprobePath: FFPROBE_PATH,
  criteria: {
    minAudioDurationMs: 120000,  // 2 minutes
    minTranscriptChars: 100,     // 100 characters
    minChatChars: 50,            // 50 characters
  },
  doUploadFn: async () => {
    // ... upload logic
  }
});
```

## Integration Point

The upload decision layer is integrated in `src/index.ts` at the `stopRecording` function:

```typescript
// Around line 678 in src/index.ts
(async () => {
  console.info('[SUMMARY] Starte Hintergrund-Zusammenfassung für:', sessionPrefix);
  const summaryStart = Date.now();
  try {
    // Use finalizeAndMaybeUpload to decide whether to upload based on content
    await finalizeAndMaybeUpload({
      recordingsDir: OUTPUT_DIR,
      sessionPrefix,
      chatText: chatTextAfterMarker,
      ffprobePath: FFPROBE_PATH,
      doUploadFn: async () => {
        // Perform summarization and upload (existing logic)
        const summaryPath = await summarizeSession({ ... });
        // ...
      }
    });
  } catch (e: any) {
    console.warn('[SUMMARY] Fehler bei Hintergrund-Zusammenfassung:', e?.message || e);
  }
})();
```

## Files Added

1. **src/uploadDecider.ts** (119 lines)
   - Core decision logic
   - Type definitions
   - Default criteria

2. **src/finalizeUploadDecider.ts** (229 lines)
   - Integration wrapper
   - Audio metadata collection
   - Transcript reading
   - Skip note writing

3. **tests/uploadDecider.test.ts** (300 lines)
   - 15 test cases
   - 41 assertions
   - All tests pass

## Testing

Run the upload decider tests:

```bash
npx tsx tests/uploadDecider.test.ts
```

Expected output:
```
=== Upload Decider Tests ===
...
=== Test Summary ===
Passed: 41
Failed: 0
Total: 41

✓ All tests passed!
```

## Benefits

1. **Saves Storage** - Prevents upload of empty/test meetings
2. **Reduces Clutter** - Keeps Gitea repository focused on meaningful content
3. **Maintains Privacy** - Only uploads sessions with actual content
4. **Configurable** - Easy to adjust thresholds based on needs
5. **Transparent** - Clear logging and skip notes explain decisions
6. **Non-Breaking** - Defaults allow most real meetings through

## Future Enhancements

Potential improvements (not currently implemented):

1. **Environment Variables** - Make criteria configurable via ENV vars
2. **Per-Meeting Overrides** - Allow chat commands to force upload
3. **Additional Criteria** - Number of participants, recording duration, etc.
4. **Machine Learning** - Learn from past decisions to improve accuracy
5. **Retention Policies** - Auto-delete skipped recordings after N days
