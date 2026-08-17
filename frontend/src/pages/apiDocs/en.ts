import type { ApiDocs } from './index';

/** English version of the API help section. Structure: see `./index.ts`. */
export const apiDocsEn: ApiDocs = {
  quickstart: {
    intro:
      'The API can do everything this web interface can – they are the same endpoints. All you need is a key (create one above) and to send it with every request. Responses are JSON, timestamps are ISO-8601 in UTC.',
    baseUrlLabel: 'Base URL of this installation',
    example: `export BBB="https://bbb.example.intern"
export KEY="bbb_..."

# list recordings
curl -s -H "X-API-Key: $KEY" "$BBB/api/recordings"

# read the summary of a recording
curl -s -H "X-API-Key: $KEY" "$BBB/api/recordings/<id>/summary"

# transcribe a file and wait for the result
curl -s -H "X-API-Key: $KEY" -F file=@note.m4a \\
  "$BBB/api/transcriptions?wait=300"`,
  },

  auth: {
    title: 'Authenticating with a key',
    text:
      'Send the key in the X-API-Key header. Authorization: Bearer bbb_… works as well – the bbb_ prefix is how the server tells it apart from a login token. There is no session: the key is valid until it is revoked or its expiry date passes.',
    notes: [
      'A key can never do more than you can: other people’s recordings stay invisible, admin endpoints need admin rights.',
      'A read-only key may only use GET. Any other method is rejected with 403.',
      'Not available via key: key management (/api/api-keys) and changing your password. Both require a signed-in session – that way revoking a key really ends its access.',
      'The key itself appears only in the response that creates it. Only its fingerprint is stored; if you lose it, create a new one.',
    ],
  },

  sections: [
    {
      id: 'recordings',
      title: 'Recordings',
      intro:
        'Recordings from bot sessions, uploads and screen captures. You see your own plus those shared with you.',
      endpoints: [
        {
          method: 'GET',
          path: '/api/recordings',
          summary: 'List and search recordings.',
          params: [
            { name: 'q', description: 'search term for title, room name, meeting URL and tags' },
            { name: 'tag', description: 'only recordings carrying this tag' },
            { name: 'content', description: 'true = also search transcript and summary' },
          ],
          example: `curl -s -H "X-API-Key: $KEY" \\
  "$BBB/api/recordings?q=technik&content=true"`,
          response: `[
  {
    "id": "8f14e45f-...",
    "title": "Weekly meeting engineering",
    "status": "DONE",
    "startedAt": "2026-07-21T08:00:12Z",
    "durationMs": 3612000,
    "source": "BOT",
    "tags": ["Projekt Nord"],
    "mine": true
  }
]`,
        },
        {
          method: 'GET',
          path: '/api/recordings/{id}',
          summary:
            'One recording with segments, summaries, jobs and participants. The segment IDs are what you need for audio downloads.',
          example: `curl -s -H "X-API-Key: $KEY" "$BBB/api/recordings/$ID"`,
        },
        {
          method: 'POST',
          path: '/api/recordings/upload',
          summary:
            'Import an existing audio/video file as a recording (multipart/form-data). If you only want a transcript, /api/transcriptions is more convenient.',
          params: [
            { name: 'file', description: 'the file (mp3, wav, m4a, mp4, mkv, …)' },
            { name: 'title', description: 'title; empty = file name' },
            { name: 'aiAnalysis', description: 'true (default) = transcribe and summarise' },
            { name: 'processNow', description: 'true = process immediately instead of at night' },
            { name: 'diarize', description: 'true = speaker recognition (if enabled)' },
            {
              name: 'summaryPrompt',
              description:
                'Analysis prompt for this recording (max. 8000 characters); empty = the administrator default. It already applies to the first analysis – including processNow=true.',
            },
            {
              name: 'sttLanguage',
              description:
                'Language for speech recognition, e.g. de or en; auto = detect automatically, empty = the administrator default (whisper.language).',
            },
          ],
          example: `curl -s -H "X-API-Key: $KEY" \\
  -F file=@meeting.mp3 -F title="Jour Fixe" -F processNow=true \\
  "$BBB/api/recordings/upload"`,
        },
        {
          method: 'DELETE',
          path: '/api/recordings/{id}',
          summary: 'Delete a recording including its files (owner only). Not reversible.',
          example: `curl -s -X DELETE -H "X-API-Key: $KEY" "$BBB/api/recordings/$ID"`,
        },
        {
          method: 'GET',
          path: '/api/recordings/tags',
          summary: 'All visible tags with the number of recordings.',
        },
        {
          method: 'POST',
          path: '/api/recordings/{id}/tags',
          summary: 'Add a tag (owner only). Returns the new list.',
          example: `curl -s -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \\
  -d '{"name":"Projekt Nord"}' "$BBB/api/recordings/$ID/tags"`,
        },
        {
          method: 'DELETE',
          path: '/api/recordings/{id}/tags?name=…',
          summary: 'Remove a tag (owner only).',
        },
      ],
    },

    {
      id: 'transcripts',
      title: 'Transcripts',
      intro:
        'Every recording has the raw Whisper transcript and – once the AI smoothing has run – a cleaned-up version that took your glossary into account.',
      endpoints: [
        {
          method: 'GET',
          path: '/api/recordings/{id}/transcript',
          summary: 'Transcript in both versions, as plain text and as timestamped entries.',
          example: `curl -s -H "X-API-Key: $KEY" "$BBB/api/recordings/$ID/transcript"`,
          response: `{
  "transcript": "[00:05] ähm also guten morgen ...",
  "correctedTranscript": "[00:05] Guten Morgen ...",
  "hasCorrected": true,
  "correctionStatus": "READY",
  "entries": [
    { "startSeconds": 5, "speaker": "SPEAKER_00", "text": "Guten Morgen ..." }
  ]
}`,
        },
        {
          method: 'POST',
          path: '/api/recordings/{id}/transcribe',
          summary: 'Transcribe only (step 1), no summary. Runs immediately. Returns the job.',
          example: `curl -s -X POST -H "X-API-Key: $KEY" "$BBB/api/recordings/$ID/transcribe"`,
          response: `{ "id": "3d2b...", "status": "PENDING", "immediate": true, "transcribeOnly": true }`,
        },
        {
          method: 'POST',
          path: '/api/recordings/{id}/retranscribe',
          summary:
            'Discard the transcript and create it again – e.g. after a Whisper change. Existing smoothing is redone as well.',
        },
        {
          method: 'PUT',
          path: '/api/recordings/{id}/participants/{participantId}',
          summary:
            'Rename a detected speaker (owner only). Applies to the display, to transcript.md and to future summaries.',
          example: `curl -s -X PUT -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \\
  -d '{"displayName":"Ms Meier"}' \\
  "$BBB/api/recordings/$ID/participants/$PID"`,
        },
      ],
    },

    {
      id: 'summaries',
      title: 'Summaries',
      endpoints: [
        {
          method: 'GET',
          path: '/api/recordings/{id}/summary',
          summary: 'Latest summary as Markdown. 404 while there is none.',
          example: `curl -s -H "X-API-Key: $KEY" "$BBB/api/recordings/$ID/summary"`,
          response: `{
  "id": "b21f...",
  "status": "DONE",
  "markdown": "# Weekly meeting engineering\\n\\n## Outcomes\\n- ...",
  "model": "qwen2.5-32b-instruct",
  "finishedAt": "2026-07-22T02:14:51Z"
}`,
        },
        {
          method: 'GET',
          path: '/api/recordings/{id}/summary/download',
          summary: 'The same summary as an .md file.',
          example: `curl -s -H "X-API-Key: $KEY" -OJ \\
  "$BBB/api/recordings/$ID/summary/download"`,
        },
        {
          method: 'POST',
          path: '/api/recordings/{id}/process',
          summary:
            'Process now: transcribe (if needed) and summarise without waiting for the night window.',
          example: `curl -s -X POST -H "X-API-Key: $KEY" "$BBB/api/recordings/$ID/process"`,
        },
        {
          method: 'POST',
          path: '/api/recordings/{id}/reprocess',
          summary:
            'Process again: existing transcripts are reused, only the summary is recreated and replaces the old one.',
        },
        {
          method: 'POST',
          path: '/api/recordings/{id}/summary-options',
          summary:
            'Adjust the analysis: custom prompt, word count, language. Applies to the next run for this recording.',
          example: `curl -s -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \\
  -d '{"prompt":"Decisions and tasks only.","maxWords":300,"language":"en"}' \\
  "$BBB/api/recordings/$ID/summary-options"`,
        },
        {
          method: 'PUT',
          path: '/api/recordings/{id}/summaries/{summaryId}',
          summary: 'Overwrite a summary by hand (owner only).',
          example: `curl -s -X PUT -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \\
  -d '{"markdown":"# Outcome\\n- Decision A"}' \\
  "$BBB/api/recordings/$ID/summaries/$SID"`,
        },
        {
          method: 'DELETE',
          path: '/api/recordings/{id}/summaries/{summaryId}',
          summary: 'Delete one summary (owner only).',
        },
      ],
    },

    {
      id: 'transcriptions',
      title: 'Transcribe directly',
      intro:
        'File in, transcript out – no summary. The job runs through the same pipeline as an upload (transcoding, Whisper, AI smoothing with your glossary), so it is not instant: either wait with wait= or pick the result up later. Every job is a normal recording in your account and can be deleted afterwards.',
      endpoints: [
        {
          method: 'POST',
          path: '/api/transcriptions',
          summary:
            'Start a transcription (multipart/form-data). Returns 202 with the job ID – or 200 with the transcript if it finished within wait.',
          params: [
            { name: 'file', description: 'audio or video file (mp3, wav, m4a, mp4, mkv, …)' },
            { name: 'title', description: 'title of the resulting recording; empty = file name' },
            { name: 'diarize', description: 'true = speaker recognition (if enabled)' },
            {
              name: 'sttLanguage',
              description:
                'Language for speech recognition, e.g. de or en; auto = detect automatically, empty = the administrator default (whisper.language).',
            },
            { name: 'wait', description: 'seconds to wait (0 = answer immediately, max 600)' },
          ],
          example: `# short file: one call
curl -s -H "X-API-Key: $KEY" -F file=@note.m4a \\
  "$BBB/api/transcriptions?wait=300"

# long recording: start now, collect later
curl -s -H "X-API-Key: $KEY" -F file=@meeting.mp4 \\
  "$BBB/api/transcriptions"`,
          response: `{ "id": "a1b2c3d4-...", "status": "PENDING" }`,
        },
        {
          method: 'GET',
          path: '/api/transcriptions/{id}',
          summary:
            'State and – once ready – the transcript. status is PENDING, RUNNING, DONE or FAILED; wait= works here too.',
          params: [{ name: 'wait', description: 'seconds to wait (0 = answer immediately, max 600)' }],
          example: `curl -s -H "X-API-Key: $KEY" "$BBB/api/transcriptions/$ID?wait=60"`,
          response: `{
  "id": "a1b2c3d4-...",
  "status": "DONE",
  "durationMs": 187000,
  "text": "[00:00] Good morning, let's start ...",
  "entries": [
    { "startSeconds": 0, "speaker": null, "text": "Good morning, let's start ..." }
  ]
}`,
        },
        {
          method: 'DELETE',
          path: '/api/recordings/{id}',
          summary:
            'Clean up: once you have the transcript and do not want to keep the recording, delete it using the same ID.',
          example: `curl -s -X DELETE -H "X-API-Key: $KEY" "$BBB/api/recordings/$ID"`,
        },
      ],
    },

    {
      id: 'glossary',
      title: 'Glossary',
      intro:
        'Your abbreviations and technical terms. They feed into the AI smoothing of your transcripts – including direct transcriptions.',
      endpoints: [
        {
          method: 'GET',
          path: '/api/glossary',
          summary: 'All of your entries, alphabetically.',
          example: `curl -s -H "X-API-Key: $KEY" "$BBB/api/glossary"`,
          response: `[
  { "id": "c7f1...", "term": "RZ", "meaning": "Rechenzentrum", "createdAt": "2026-07-20T09:12:00Z" }
]`,
        },
        {
          method: 'POST',
          path: '/api/glossary',
          summary: 'Create a term. 409 if it already exists (case-insensitive).',
          example: `curl -s -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \\
  -d '{"term":"RZ","meaning":"Rechenzentrum"}' "$BBB/api/glossary"`,
        },
        {
          method: 'PUT',
          path: '/api/glossary/{id}',
          summary: 'Change a term or its meaning.',
          example: `curl -s -X PUT -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \\
  -d '{"term":"RZ","meaning":"Rechenzentrum Nord"}' "$BBB/api/glossary/$GID"`,
        },
        {
          method: 'DELETE',
          path: '/api/glossary/{id}',
          summary: 'Delete an entry.',
        },
        {
          method: 'GET',
          path: '/api/glossary/export',
          summary: 'The whole glossary as CSV (Begriff;Bedeutung, UTF-8 with BOM).',
          example: `curl -s -H "X-API-Key: $KEY" "$BBB/api/glossary/export" -o glossary.csv`,
        },
        {
          method: 'POST',
          path: '/api/glossary/import',
          summary:
            'Import a CSV (multipart/form-data, field file). Merges: existing terms are updated, new ones added, nothing is deleted.',
          example: `curl -s -H "X-API-Key: $KEY" -F file=@glossary.csv \\
  "$BBB/api/glossary/import"`,
          response: `{ "created": 12, "updated": 3, "unchanged": 40, "skipped": 1,
  "warnings": ["Zeile 8: kein Begriff angegeben"] }`,
        },
      ],
    },

    {
      id: 'bots',
      title: 'Bots and recording control',
      intro:
        'A bot joins a BigBlueButton room and records the audio. The meeting URL is the ready-made invitation URL, exactly as you would open it in a browser.',
      endpoints: [
        {
          method: 'GET',
          path: '/api/bots',
          summary: 'Active bots with state, participant count and current recording.',
          example: `curl -s -H "X-API-Key: $KEY" "$BBB/api/bots"`,
        },
        {
          method: 'POST',
          path: '/api/bots',
          summary:
            'Start a bot. sttLanguage sets the speech recognition language for this bot’s recordings (auto = detect automatically, omitted = the administrator default).',
          example: `curl -s -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \\
  -d '{"meetingUrl":"https://bbb.example.intern/b/abc-def-ghi",
       "botName":"Minutes bot","autoRecord":true,
       "recordVideo":false,"aiAnalysis":true,"diarize":false,
       "sttLanguage":"en"}' \\
  "$BBB/api/bots"`,
          response: `{ "sessionId": "9a7c...", "status": "STARTING", "roomName": "Technikrunde" }`,
        },
        {
          method: 'POST',
          path: '/api/bots/{sessionId}/recording/start',
          summary: 'Start recording.',
        },
        {
          method: 'POST',
          path: '/api/bots/{sessionId}/recording/stop',
          summary: 'Stop recording. With ?discard=true it is discarded instead of processed.',
          example: `curl -s -X POST -H "X-API-Key: $KEY" \\
  "$BBB/api/bots/$SID/recording/stop"`,
        },
        {
          method: 'DELETE',
          path: '/api/bots/{sessionId}',
          summary: 'Stop the bot; a running recording is finalised.',
        },
        {
          method: 'GET',
          path: '/api/bots/history',
          summary: 'Finished bot sessions with time range and errors.',
        },
      ],
    },

    {
      id: 'sharing',
      title: 'Sharing and groups',
      endpoints: [
        {
          method: 'GET',
          path: '/api/recordings/{id}/shares',
          summary: 'Existing shares of a recording.',
        },
        {
          method: 'POST',
          path: '/api/recordings/{id}/shares',
          summary: 'Share with a user or a group – provide exactly one of userId/groupId.',
          example: `curl -s -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \\
  -d '{"userId":"4f2a..."}' "$BBB/api/recordings/$ID/shares"`,
        },
        {
          method: 'DELETE',
          path: '/api/recordings/{id}/shares/{shareId}',
          summary: 'Revoke a share.',
        },
        {
          method: 'GET',
          path: '/api/recordings/{id}/share-links',
          summary:
            'Public share links for this recording (owner only) – including expiry and view count.',
        },
        {
          method: 'POST',
          path: '/api/recordings/{id}/share-links',
          summary:
            'Create a share link (owner only). The default is account-bound: the recipient signs in and the recording is shared with their account automatically. With requireLogin=false the link shows video, audio, transcript and summary without signing in. Without expiresInDays the link is valid until revoked. The address is <base URL>/share/<token>.',
          params: [
            { name: 'expiresInDays', description: 'validity in days (1–3650); omit for "until revoked"' },
            {
              name: 'requireLogin',
              description:
                'true (default) = sign-in required, the share is granted on the way; false = access without signing in. If the admin switched off sharing.publicLinks, false is rejected with 409.',
            },
          ],
          example: `curl -s -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \\
  -d '{"expiresInDays":30}' "$BBB/api/recordings/$ID/share-links"`,
          response: `{
  "id": "0c9d1e2f-...",
  "token": "brqk6JmNhZf9oO55m_98lBOZoRskgzRh7K3fBMY12Ok",
  "createdAt": "2026-08-12T13:04:38Z",
  "expiresAt": "2026-09-11T13:04:38Z",
  "expired": false,
  "views": 0,
  "lastViewedAt": null,
  "requiresLogin": true
}`,
        },
        {
          method: 'DELETE',
          path: '/api/recordings/{id}/share-links/{linkId}',
          summary: 'Revoke a share link – the address becomes invalid immediately.',
        },
        {
          method: 'POST',
          path: '/api/share-links/{token}/claim',
          summary:
            'Redeem an account-bound share link: the recording is shared with the signed-in user (redeeming twice still creates only one share); the response carries its id. For a link that needs no sign-in, no share is created on purpose.',
          example: `curl -s -X POST -H "X-API-Key: $KEY" \\
  "$BBB/api/share-links/$SHARE_TOKEN/claim"`,
          response: `{"recordingId":"8f14e45f-...","title":"Jour Fixe","shared":true}`,
        },
        {
          method: 'GET',
          path: '/api/share-links/config',
          summary: 'May this server share without sign-in? {"publicLinksAllowed":true}',
        },
        {
          method: 'GET',
          path: '/api/public/shares/{token}',
          summary:
            'Contents of a share link: header data, audio segments, transcript and summary. Needs NO key – plus /video, /video/download, /segments/{segmentId}/audio and /summary/download. Unknown, expired and revoked tokens all answer with 404; an account-bound link answers with 403 (redeem it via /api/share-links/{token}/claim).',
          example: `curl -s "$BBB/api/public/shares/$SHARE_TOKEN"`,
        },
        {
          method: 'GET',
          path: '/api/groups',
          summary: 'Your groups and groups you are a member of.',
        },
        {
          method: 'POST',
          path: '/api/groups',
          summary: 'Create a group.',
          example: `curl -s -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \\
  -d '{"name":"Engineering"}' "$BBB/api/groups"`,
        },
        {
          method: 'GET',
          path: '/api/groups/{groupId}/members',
          summary: 'Members of a group.',
        },
        {
          method: 'POST',
          path: '/api/groups/{groupId}/members',
          summary: 'Add a member: {"userId":"…"}.',
        },
        {
          method: 'DELETE',
          path: '/api/groups/{groupId}/members/{userId}',
          summary: 'Remove a member.',
        },
        {
          method: 'GET',
          path: '/api/users/search?q=…',
          summary: 'Search users (from 2 characters) – gives you the IDs for shares and groups.',
          example: `curl -s -H "X-API-Key: $KEY" "$BBB/api/users/search?q=mei"`,
        },
      ],
    },

    {
      id: 'templates',
      title: 'Prompt templates',
      intro:
        'Your own analysis prompts, reusable when processing a recording – managed in the “Templates” tab of the frontend.',
      endpoints: [
        { method: 'GET', path: '/api/prompt-templates', summary: 'List your templates.' },
        {
          method: 'GET',
          path: '/api/prompt-templates/default-prompt',
          summary: 'The administrator’s default – a starting point for your own templates.',
          response: `{ "prompt": "You are an assistant that …" }`,
        },
        {
          method: 'POST',
          path: '/api/prompt-templates',
          summary: 'Create a template.',
          example: `curl -s -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \\
  -d '{"name":"Tasks only","prompt":"List tasks with owners, nothing else."}' \\
  "$BBB/api/prompt-templates"`,
        },
        { method: 'PUT', path: '/api/prompt-templates/{id}', summary: 'Change a template.' },
        { method: 'DELETE', path: '/api/prompt-templates/{id}', summary: 'Delete a template.' },
      ],
    },

    {
      id: 'media',
      title: 'Audio and video',
      intro: 'Segment IDs come from the detail response of a recording (GET /api/recordings/{id}).',
      endpoints: [
        {
          method: 'GET',
          path: '/api/recordings/{id}/segments/{segmentId}/audio',
          summary: 'One audio segment as MP3.',
          example: `curl -s -H "X-API-Key: $KEY" \\
  "$BBB/api/recordings/$ID/segments/$SEGID/audio" -o segment.mp3`,
        },
        {
          method: 'GET',
          path: '/api/recordings/{id}/video',
          summary: 'Meeting video as MP4 (only if the recording has one).',
        },
        {
          method: 'GET',
          path: '/api/recordings/{id}/video/download',
          summary: 'The same video with a download file name.',
        },
      ],
    },

    {
      id: 'account',
      title: 'Account and administration',
      endpoints: [
        {
          method: 'GET',
          path: '/api/auth/me',
          summary: 'Who am I? A good first call to check a key.',
          example: `curl -s -H "X-API-Key: $KEY" "$BBB/api/auth/me"`,
          response: `{ "id": "5d34...", "username": "m.mustermann", "admin": false, "local": false }`,
        },
        {
          method: 'PUT',
          path: '/api/users/me/language',
          summary: 'Set your account language: {"language":"de"} or {"language":"en"}.',
        },
        {
          method: 'GET',
          path: '/api/admin/settings',
          summary:
            'All settings (Whisper, LLM, time window, bots) – admin only. Change them with PUT in the same format.',
          example: `curl -s -H "X-API-Key: $KEY" "$BBB/api/admin/settings"`,
        },
        {
          method: 'GET',
          path: '/api/admin/users',
          summary:
            'All known users – admin only. Each user also carries lastSeenAt/online (frontend activity, 5 minute window) and activeRecordings (recordings running right now).',
          response: `[{ "username": "m.mustermann", "online": true,
   "lastSeenAt": "2026-08-13T09:12:44Z",
   "activeRecordings": [{ "id": "5d34…", "status": "RECORDING",
                          "source": "CAPTURE", "startedAt": "2026-08-13T09:03:00Z" }] }]`,
        },
      ],
    },
  ],

  errors: {
    title: 'Errors and status codes',
    intro:
      'Errors always come back as JSON with a message field explaining the cause in plain words (German, as everywhere in this application). Branch on the status code; the message is for humans.',
    rows: [
      { code: '200 / 202', meaning: 'Success. 202 means accepted and still running (transcription).' },
      { code: '400', meaning: 'Unusable request – message names the reason (missing field, wrong format, unsupported file type).' },
      { code: '401', meaning: 'Missing, unknown, revoked or expired key.' },
      { code: '403', meaning: 'Not allowed: someone else’s recording, admin endpoint without admin rights, read-only key on a writing call, or an area blocked for keys.' },
      { code: '404', meaning: 'Does not exist – or is not visible to you.' },
      { code: '409', meaning: 'Conflict: e.g. the term is already in the glossary, processing is already running.' },
      { code: '413', meaning: 'File larger than the server-side upload limit.' },
      { code: '500', meaning: 'Server error. It is in the server log; message names the technical cause.' },
    ],
    example: `{ "message": "Dieser API-Schluessel darf nur lesen (POST nicht erlaubt)" }`,
  },
};
