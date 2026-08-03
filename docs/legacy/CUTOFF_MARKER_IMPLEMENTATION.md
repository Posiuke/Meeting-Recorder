# Cutoff Marker Implementation Summary

## Overview
This document describes the implementation of the cutoff marker feature to prevent the bot from reacting to its own START command messages.

## Problem Statement
After a STOPRECORDING command, the bot sends a hint message like:
```
Aufzeichnung verworfen. Zum erneuten Starten 'STARTRECORDING' eingeben.
```

This message contains the text "STARTRECORDING", which the bot would detect and incorrectly trigger on, causing unwanted automatic restarts.

## Solution: Cutoff Marker System

### Concept
The solution introduces a **cutoff marker** that marks the position of the last bot-generated message. When scanning for START commands, the bot only looks at messages that appear **after** this cutoff marker, effectively ignoring all previous messages including its own.

### Two Types of Markers

1. **Active Marker** (`activeMarker`)
   - Set when recording starts
   - Used to detect STOP commands during active recording
   - Cleared when recording stops

2. **Cutoff Marker** (`cutoffMarker`)
   - Set after bot posts a hint message following STOP
   - Used to filter out bot's own messages when scanning for START
   - Cleared when a new recording starts

### Implementation Details

#### MarkerStore Changes (`src/chat/marker.ts`)
```typescript
// Separate storage for each marker type
let activeMarker: SessionMarkerInfo | null = null;
let cutoffMarker: SessionMarkerInfo | null = null;

// New API
setActive(markerString, messageId?)      // Set active marker
getActive()                               // Get active marker
clearActive()                             // Clear active marker
hasActiveMarker()                         // Check if active marker exists

setCutoff(markerString, messageId?)      // Set cutoff marker
getCutoff()                               // Get cutoff marker
clearCutoff()                             // Clear cutoff marker
hasCutoffMarker()                         // Check if cutoff marker exists

// Backward compatibility (maps to active marker)
setSessionMarker()                        // @deprecated, use setActive()
getCurrentMarker()                        // @deprecated, use getActive()
clearSessionMarker()                      // @deprecated, use clearActive()
```

#### Command Detection Changes (`src/chat/commandDetection.ts`)
```typescript
// New functions for cutoff marker support
getTextAfterCutoff(ctx)                   // Extract text after cutoff marker
detectStartAfterCutoff(ctx, command)      // Detect START only after cutoff

// Existing functions continue to work with active marker
getTextAfterMarker(ctx)                   // Uses active marker
detectCommandsAfterMarker(ctx, stop, start)  // Uses active marker
```

#### Recording Flow Changes (`src/index.ts`)

**STOP Flow:**
1. User sends STOPRECORDING
2. Bot detects it after active marker
3. Bot generates a new cutoff marker
4. Bot sends hint message with marker appended: `"... [RECxyz]"`
5. Bot sets cutoffMarker to the new marker
6. Bot clears activeMarker

**START Detection (when idle):**
1. If cutoffMarker is set:
   - Scan for START only after cutoffMarker
   - Ignores all text before cutoffMarker (including bot's own hint)
2. If no cutoffMarker:
   - Fall back to global scan (existing behavior)

**START Flow (recording start):**
1. START command detected after cutoffMarker
2. Bot clears cutoffMarker
3. Bot generates and sets new activeMarker
4. Bot starts recording
5. Bot sends warning message with activeMarker appended: `"... [RECabc]"`

### Configuration
New environment variable:
- `CHAT_MESSAGE_DOM_WAIT_MS` (default: 2000): Time to wait for chat messages to appear in DOM

## Testing

### New Test Suite
`tests/cutoff-marker.test.ts` contains 26 tests covering:
- Marker independence (active and cutoff can coexist)
- STOP scenario with cutoff marker
- START detection that ignores bot's own messages
- START detection that finds real user commands
- Multiple STOP/START cycles
- Complex historical message scenarios

### Test Results
- Existing tests: 35/35 passing ✓
- New cutoff tests: 26/26 passing ✓
- Build: Success ✓
- CodeQL security scan: 0 alerts ✓

## Example Flow

```
1. [System] Recording starts
   Bot posts: "Recording started [REC123]"
   activeMarker = REC123
   
2. [User] STOPRECORDING
   
3. [Bot] Detects STOP after [REC123]
   Bot posts: "Aufzeichnung verworfen. Zum erneuten Starten 'STARTRECORDING' eingeben. [REC456]"
   cutoffMarker = REC456
   activeMarker = null
   
4. [Bot] Scans for START after [REC456]
   - "STARTRECORDING" appears in bot's message but BEFORE [REC456]
   - Ignored! No false trigger.
   
5. [User] STARTRECORDING
   
6. [Bot] Detects START after [REC456]
   - This STARTRECORDING is AFTER [REC456]
   - Detected! Recording starts.
   cutoffMarker = null
   activeMarker = REC789
```

## Benefits

1. **Prevents False Triggers**: Bot no longer reacts to its own messages
2. **Backward Compatible**: Existing code continues to work with new API
3. **Well Tested**: Comprehensive test coverage
4. **Configurable**: Wait times can be adjusted via environment variables
5. **Robust**: Handles complex scenarios with multiple STOP/START cycles

## Migration Notes

The implementation maintains backward compatibility. Existing code using `setSessionMarker()`, `getCurrentMarker()`, and `clearSessionMarker()` will continue to work as they are mapped to the new active marker functions.

For new code, prefer using the explicit functions:
- `setActive()` / `setCutoff()` instead of `setSessionMarker()`
- `getActive()` / `getCutoff()` instead of `getCurrentMarker()`
- `clearActive()` / `clearCutoff()` instead of `clearSessionMarker()`

## Logging

All marker operations are logged with the `[MARKER]` prefix:
```
[MARKER] Active marker set: "REC123" at 2025-12-03T06:30:00.000Z
[MARKER] Cutoff marker set: "REC456" at 2025-12-03T06:30:10.000Z
[MARKER] Active marker cleared: "REC123"
[CHAT-POLL] Scanning for START after cutoff marker
```

## Security

CodeQL analysis found no security issues with this implementation.
