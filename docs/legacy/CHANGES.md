# Changes

## December 2025: Robust Transcode and Monthly Cleanup

### Problem Statement

The recording system was experiencing transcode failures and data management issues:
1. Corrupted or unstable .webm files caused transcode failures
2. FFmpeg couldn't handle files still being written
3. No early detection of corrupted files before transcode attempts
4. Failed files weren't preserved for debugging
5. Recordings directory was cleaned on every restart, losing valuable data

### Solution Overview

Implemented robust transcode handling and monthly cleanup:
- File stability checks before transcoding
- FFprobe pre-validation to detect corruption early
- Tolerant ffmpeg flags for problematic files
- Retry logic with exponential backoff
- Failed transcodes copied to `recordings/failed/` for debugging
- Monthly cleanup instead of per-restart cleanup
- Configurable retention policies

### Technical Changes

#### New Module: `src/ffmpegHelper.ts`
- `fileStable()`: Waits for file size to stabilize before processing
- `transcodeWebmToMp3WithChecks()`: Robust transcode with:
  - 5-second stability check (configurable)
  - ffprobe pre-validation
  - Tolerant flags: `-fflags +genpts -probesize 50M -analyzeduration 100M`
  - 3 retry attempts with exponential backoff (configurable)
  - Detailed error messages with ffmpeg stderr

#### Updated: `src/index.ts`
- Integrated `transcodeWebmToMp3WithChecks()` into `transcodeToMp3IfEnabled()`
- Failed transcodes copied to `recordings/failed/` directory
- New `cleanupOldRecordingsIfNeeded()` function:
  - Runs once per month on startup (configurable)
  - Stores timestamp in `.last_cleanup`
  - Deletes files older than configured age
  - Skips special directories (failed/, .last_cleanup)

#### New Configuration Options (.env)
```bash
# Cleanup of old recordings
CLEANUP_ENABLED="true"                # Enable/disable cleanup
CLEANUP_INTERVAL_DAYS="30"            # Run cleanup every N days
CLEANUP_OLDER_THAN_DAYS="30"         # Delete files older than N days
```

#### Tests
- `tests/ffmpegHelper.test.ts`: Unit tests for file stability detection
- All existing tests continue to pass

#### Documentation
- `TRANSCODE_AND_CLEANUP.md`: Comprehensive guide covering:
  - Feature overview and usage
  - Configuration examples
  - Troubleshooting guide
  - Security considerations

### Benefits

1. **Reliability**: Transcode failures reduced through stability checks and validation
2. **Debuggability**: Failed files preserved for analysis
3. **Data Safety**: Monthly cleanup prevents accidental data loss
4. **Flexibility**: Configurable retention policies per deployment needs
5. **Performance**: Minimal overhead, only on-demand operations

### Configuration Examples

**Default (Monthly cleanup, 30-day retention)**:
```bash
CLEANUP_ENABLED="true"
CLEANUP_INTERVAL_DAYS="30"
CLEANUP_OLDER_THAN_DAYS="30"
```

**Weekly cleanup, 7-day retention**:
```bash
CLEANUP_ENABLED="true"
CLEANUP_INTERVAL_DAYS="7"
CLEANUP_OLDER_THAN_DAYS="7"
```

**Disable automatic cleanup**:
```bash
CLEANUP_ENABLED="false"
```

---

## Chat-Based Recording Command Detection Fix

## Problem Statement

The chat-based stop/start recognition system was experiencing issues where:
1. STARTRECORDING/STOPRECORDING commands sent after the session marker were lost
2. Chat poller appeared to stop or not reliably check messages after recording finalization
3. Commands sent during MP3 finalization/upload were not detected
4. Race conditions could occur between finalize() and chat scanning

## Solution Overview

Implemented a robust chat marker-based command detection system with:
- Persistent marker management with metadata
- Independent command detection that continues during finalization
- Word-boundary regex matching to prevent false positives
- Comprehensive test coverage (27 tests)
- Enhanced debug logging throughout marker lifecycle

## Technical Changes

### New Modules

#### `src/chat/marker.ts` - Session Marker Management
- `generateSessionMarker()`: Creates unique random markers (format: "REC" + 12 random chars)
- `setSessionMarker(markerString, messageId?)`: Persists marker with timestamp
- `getCurrentMarker()`: Retrieves active marker info
- `clearSessionMarker()`: Clears active marker
- `hasActiveMarker()`: Checks if marker is set

**Logging**: Structured logs for marker set/clear operations with timestamps.

#### `src/chat/commandDetection.ts` - Command Detection Logic
- `getAllChatText(ctx)`: Extracts complete chat text from DOM
- `getTextAfterMarker(ctx)`: Finds marker and returns subsequent text
- `detectCommandsAfterMarker(ctx, stopCmd, startCmd)`: Detects commands after marker
- `detectCommandInFullChat(ctx, cmd)`: Detects commands in full chat (for START without marker)

**Features**:
- Case-insensitive matching
- Word-boundary regex (rejects "NOSTOPRECORDING", accepts "STOPRECORDING")
- Uses shared `escapeRegExp` utility to prevent regex injection
- Comprehensive debug logging with `DEBUG_STATE` env var

### Modified Files

#### `src/index.ts` - Main Application
**Key Changes**:
1. Removed duplicate `getAllChatText()` function (now in commandDetection module)
2. Added `finalizeInProgress` flag to track MP3 finalization state
3. Updated `stopRecording()`:
   - Sets `finalizeInProgress = true` before finalization
   - Clears flag after finalization complete
   - Uses `clearSessionMarker()` instead of manual marker reset
4. Chat command detection:
   - Uses `detectCommandsAfterMarker()` for STOP commands
   - Uses `detectCommandInFullChat()` for START commands
   - Continues scanning even when `finalizeInProgress === true`
5. Marker management:
   - Uses `setSessionMarker()` when starting recording (manual or automatic)
   - Uses `clearSessionMarker()` when stopping or on failure

**Logging Additions**:
- `[REC] Finalize started - poller will continue scanning for commands`
- `[REC] Finalize complete - clearing session state`
- `[CHAT] STOP command detected after session marker! finalizeInProgress=%s`
- `[CHAT-POLL] Marker found, scanned N chars after marker`
- `[CHAT-POLL] Session marker not found in chat yet`

### Test Suite

#### `tests/chat-marker-detection.test.ts`
27 comprehensive test cases covering:
1. Marker generation (uniqueness, format)
2. Marker persistence lifecycle
3. STOP command detection after marker
4. Commands before marker ignored
5. Case-insensitive matching
6. Word boundary matching (partial match rejection)
7. Multiple messages after marker
8. Marker not yet posted scenario
9. START command detection

**Test Results**: ✓ All 27 tests pass

#### `tests/README.md`
Documentation for test suite including:
- How to run tests
- Test coverage description
- Requirements and setup

### Documentation & Configuration

#### `package.json`
Added `"test": "tsx tests/chat-marker-detection.test.ts"` script

## Behavior Changes

### Before
- Marker was a simple string variable
- No marker metadata (timestamp, messageId)
- Chat poller might stop during finalization
- Commands during finalization could be lost
- Simple substring search (case-sensitive in some places)
- No comprehensive tests

### After
- Marker is an object with metadata (markerString, timestamp, messageId)
- Proper marker lifecycle management
- Chat poller continues during finalization (`finalizeInProgress` flag)
- Commands during finalization are reliably detected
- Robust word-boundary regex (case-insensitive)
- 27 test cases validate all scenarios

## No Breaking Changes

All existing functionality is preserved:
- Recording start/stop logic unchanged
- MP3 finalization process unchanged
- Summary/upload workflows unchanged
- Environment variable configuration unchanged
- Existing chat command syntax unchanged

## Debug Logging

Enable detailed logging with `DEBUG_STATE=true`:

```
[MARKER] Session marker set: "RECabc123456" at 2025-12-02T11:37:35.657Z
[CHAT-POLL] Marker found, scanned 245 chars after marker
[CHAT] STOP command detected after session marker! finalizeInProgress=true
[REC] Finalize started - poller will continue scanning for commands
[REC] Finalize complete - clearing session state
[MARKER] Session marker cleared: "RECabc123456"
```

## Security Improvements

1. **Regex Injection Prevention**: Uses shared `escapeRegExp()` utility to sanitize command strings before building regex patterns
2. **Consistent Text Processing**: Normalizes text before regex matching to prevent unexpected behavior
3. **HTML Sanitization**: Clear documentation that HTML cleaning is for text extraction only, not rendering

## Testing

To run tests:
```bash
npm test
# or
npx tsx tests/chat-marker-detection.test.ts
```

## Migration Notes

No migration needed - changes are backward compatible. The system will automatically:
- Generate new markers on recording start
- Use enhanced detection on next poll cycle
- Clear markers on recording stop

## Future Enhancements

Possible improvements for future versions:
1. Store messageId when marker is posted (requires DOM inspection)
2. Add marker position cache to avoid repeated searches
3. Support multiple concurrent markers (if multiple sessions needed)
4. Add metrics for command detection latency
5. Expose marker info via health endpoint

## Related Files

Modified:
- `src/index.ts`
- `src/chat/commandDetection.ts` (new)
- `src/chat/marker.ts` (new)
- `tests/chat-marker-detection.test.ts` (new)
- `tests/README.md` (new)
- `package.json`

Unchanged:
- `src/chat/extraction.ts`
- `src/chat/messaging.ts`
- `src/chat/normalization.ts`
- `src/recorder.ts`
- `src/summary.ts`
- All other modules

## Performance Impact

Minimal to none:
- Marker lookup is O(n) string search (n = chat length), performed once per poll cycle
- Poll interval unchanged (default 5 seconds)
- No additional network requests
- Regex compilation cached by V8
- Test suite adds ~500ms to build time

## Rollback Plan

If issues arise, rollback is simple:
1. Revert to previous commit
2. Remove new modules: `src/chat/marker.ts`, `src/chat/commandDetection.ts`
3. No data migration needed (markers are session-only, not persisted to disk)
