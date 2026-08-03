# FFmpeg Transcode Improvements and Cleanup Features

This document describes the enhanced transcode robustness and recordings cleanup features implemented in the BBB Bot.

## Overview

The bot now includes:
1. **Robust WebM to MP3 transcoding** with stability checks, pre-validation, and retry logic
2. **Monthly cleanup behavior** to automatically delete old recordings without wiping everything on every restart
3. **Failed transcode debugging** by copying problematic files to a dedicated directory

## 1. Robust Transcoding (`src/ffmpegHelper.ts`)

### Features

The new `transcodeWebmToMp3WithChecks()` function provides:

#### File Stability Check
- Waits for input file size to remain unchanged for a configurable period (default: 5 seconds)
- Prevents transcoding files that are still being written
- Configurable via `stableMs` and `pollMs` parameters

#### FFprobe Pre-validation
- Runs `ffprobe` to validate the file before attempting transcode
- Fails fast if the file is corrupted or invalid
- Provides early detection of problematic files

#### Tolerant FFmpeg Flags
Uses special flags to handle problematic WebM files:
- `-fflags +genpts` - Generate presentation timestamps
- `-probesize 50M` - Analyze up to 50MB of input
- `-analyzeduration 100M` - Analyze for up to 100M microseconds

#### Retry Logic with Exponential Backoff
- Configurable number of attempts (default: 3)
- Exponential backoff: 1s, 2s, 4s, etc.
- Predictable timing (no jitter) for file operations
- Maximum delay capped at 10 seconds

#### Detailed Error Messages
- Captures and reports ffmpeg stderr output
- Includes file paths and specific error information
- Helps diagnose transcode failures

### Usage

The helper is automatically used by `transcodeToMp3IfEnabled()` in `src/index.ts`:

```typescript
await transcodeWebmToMp3WithChecks(inputWebm, outputMp3, {
  ffmpegPath: FFMPEG_PATH,
  ffprobePath: FFPROBE_PATH,
  bitrate: MP3_BITRATE,
  sampleRate: '48000',
  attempts: RETRY_ATTEMPTS,
  stableMs: 5000,
  pollMs: 500
});
```

### Failed Transcode Handling

When transcoding fails:
1. The original `.webm` file is copied to `recordings/failed/failed-<filename>.webm`
2. A fallback repair attempt is made (if `FFMPEG_REPAIR_ATTEMPT=true`)
3. A `_corrupt.txt` note file is created with error details
4. Optionally triggers a reconnect (if `RECONNECT_ON_FFMPEG_ERROR=true`)

This preserves problematic files for debugging while allowing the bot to continue operating.

## 2. Monthly Cleanup Behavior

### Problem Solved

Previous behavior: Recordings directory might be cleaned on every restart, losing valuable data.

New behavior: Cleanup runs **once per month** (configurable), preserving recent recordings.

### How It Works

1. **On startup**, the bot checks if cleanup is needed
2. Reads last cleanup timestamp from `OUTPUT_DIR/.last_cleanup`
3. If `CLEANUP_INTERVAL_DAYS` have passed, performs cleanup
4. Deletes files older than `CLEANUP_OLDER_THAN_DAYS`
5. Updates timestamp for next cleanup cycle
6. Skips special directories (`.last_cleanup`, `failed/`, etc.)

### Configuration

Add to your `.env` file:

```bash
# Cleanup of old recordings (runs once per interval on startup)
CLEANUP_ENABLED="true"
CLEANUP_INTERVAL_DAYS="30"        # How often to run cleanup (in days)
CLEANUP_OLDER_THAN_DAYS="30"     # Delete recordings older than this (in days)
```

### Example Scenarios

#### Scenario 1: Default Monthly Cleanup
```bash
CLEANUP_ENABLED="true"
CLEANUP_INTERVAL_DAYS="30"
CLEANUP_OLDER_THAN_DAYS="30"
```
- Cleanup runs every 30 days
- Deletes recordings older than 30 days
- Good for: Regular maintenance with 1 month retention

#### Scenario 2: Weekly Cleanup, 7-day Retention
```bash
CLEANUP_ENABLED="true"
CLEANUP_INTERVAL_DAYS="7"
CLEANUP_OLDER_THAN_DAYS="7"
```
- Cleanup runs every 7 days
- Deletes recordings older than 7 days
- Good for: High-volume recordings with limited storage

#### Scenario 3: Quarterly Cleanup, 90-day Retention
```bash
CLEANUP_ENABLED="true"
CLEANUP_INTERVAL_DAYS="90"
CLEANUP_OLDER_THAN_DAYS="90"
```
- Cleanup runs every 90 days
- Deletes recordings older than 90 days
- Good for: Archive-style retention with ample storage

#### Scenario 4: Disable Cleanup
```bash
CLEANUP_ENABLED="false"
```
- No automatic cleanup
- Manual cleanup required
- Good for: Development, testing, or manual archival systems

### Cleanup Log Output

When cleanup runs, you'll see output like:

```
[CLEANUP] Starting cleanup - last cleanup was 31 days ago
[CLEANUP] Deleted old file: 2024-11-01_10-30-00Z__bbb-audio_part1.mp3 (45MB, age: 37 days)
[CLEANUP] Deleted old file: 2024-11-01_10-30-00Z__bbb-audio_participants.txt (2MB, age: 37 days)
[CLEANUP] Completed - deleted 15 files (678MB total)
```

If cleanup is not needed:

```
[CLEANUP] Skipping - last cleanup was 5 days ago (interval: 30 days)
```

## 3. Chat/Marker Polling Robustness

The existing implementation already includes:
- Marker-based chat command detection
- Retry logic for chat extraction (configurable attempts and delay)
- Polling continues during finalization
- Protection against processing duplicate commands

These features ensure reliable detection of `STARTRECORDING` and `STOPRECORDING` commands even under adverse network conditions.

## Testing

### Unit Tests

Run the ffmpegHelper tests:
```bash
npx tsx tests/ffmpegHelper.test.ts
```

Tests cover:
- File stability detection
- Behavior with non-existent files
- Already-stable file handling

### Integration Testing

To test transcoding with actual files:
1. Create a test `.webm` file (can be corrupted for testing failure handling)
2. Set up `.env` with test values
3. Run the bot and observe transcode logs
4. Check `recordings/failed/` for any failed transcodes

To test cleanup behavior:
1. Create some old test files in `OUTPUT_DIR`
2. Manually set/remove `.last_cleanup` timestamp
3. Restart the bot
4. Observe cleanup logs

## Troubleshooting

### Transcode keeps failing

**Symptoms**: Multiple retry attempts, files in `recordings/failed/`

**Possible causes**:
- ffmpeg/ffprobe not installed or not in PATH
- Corrupted input files from unstable network/recording
- Insufficient disk space
- Permission issues

**Solutions**:
1. Verify ffmpeg installation: `ffmpeg -version`
2. Check `recordings/failed/` files with: `ffprobe failed-<file>.webm`
3. Check disk space: `df -h`
4. Check file permissions on OUTPUT_DIR

### Cleanup not running

**Symptoms**: No `[CLEANUP]` logs, old files not deleted

**Possible causes**:
- `CLEANUP_ENABLED=false`
- `.last_cleanup` timestamp is recent
- Bot not restarting

**Solutions**:
1. Check `CLEANUP_ENABLED` in `.env`
2. Check `.last_cleanup` content: `cat recordings/.last_cleanup`
3. Delete `.last_cleanup` to force cleanup on next restart
4. Check cleanup interval vs. time since last cleanup

### Cleanup deleting too much or too little

**Symptoms**: Important files deleted, or old files not deleted

**Solutions**:
1. Review `CLEANUP_OLDER_THAN_DAYS` setting
2. Check file timestamps: `ls -lth recordings/`
3. Adjust retention period to match your needs
4. Consider disabling cleanup and using external archival system

## Security Considerations

1. **Failed transcodes directory** (`recordings/failed/`) may contain sensitive audio data - ensure proper access controls
2. **Cleanup timestamp** (`.last_cleanup`) is not authenticated - an attacker with write access could manipulate cleanup timing
3. **No cleanup of failed directory** - failed transcodes are preserved indefinitely; consider periodic manual review

## Performance Impact

- **Transcode stability check**: Adds 5-10 seconds per segment (configurable)
- **FFprobe validation**: Adds <1 second per segment
- **Cleanup**: Runs only on startup, time proportional to number of files
- **Failed file copy**: Minimal impact, only on transcode failure

## Future Enhancements

Possible improvements:
- [ ] Automatic retry of failed transcodes after cooldown period
- [ ] Cleanup of failed transcode directory based on age
- [ ] Email notifications for persistent transcode failures
- [ ] Integration with external storage services (S3, etc.)
- [ ] Configurable cleanup rules based on file type
- [ ] Compression of old recordings before deletion
