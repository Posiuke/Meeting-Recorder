# Meeting Recorder

Recording bot for **BigBlueButton** meetings — including rooms fronted by the
**Nextcloud BBB integration**: the bot joins through the ready-made room URL (no
BBB API or checksums needed), records the meeting and offers playback, a
transcript and an AI summary in the web UI. Existing audio/video files can be
analysed by upload, without a bot.

Frontend (React) and backend (Spring Boot, Java 21, Playwright) run together in
**one** container; PostgreSQL runs separately.

## Features

- 🎙️ **Audio recording** of the meeting (mixed participant streams), segmented into MP3
- 🎬 **Optional video recording** of the meeting view as MP4 (playback & download in the UI)
- 🤖 **Bot templates**: save a room you record regularly once — URL, bot name and settings — then it is just "Start bot". With an optional **schedule** (e.g. Mon/Wed/Fri 09:00–10:00) the bot joins and leaves on its own, and each template picks the **analysis template** used afterwards. Templates are per user and visible to nobody else
- 📤 **File upload**: take existing audio/video files (MP3, WAV, M4A, MP4, MKV …) in as a recording and analyse them — the analysis template can be picked right in the upload dialog
- 🖥️ **Screen recording in the browser**: capture a screen, window or tab including system audio and an optional microphone, straight from the tool — for meetings without a bot (Teams/Zoom/WebEx, in-person). **Requires HTTPS** and Chrome/Edge, see [docs/SCREEN_CAPTURE.md](https://github.com/Posiuke/Meeting-Recorder/blob/master/docs/SCREEN_CAPTURE.md)
- 📝 **Transcription** either through **your own Whisper server** (optionally with speaker separation via WhisperX) or an **OpenAI-compatible cloud API** — with continuous timestamps across the whole recording and a structured view in the UI
- ✨ **AI smoothing of the transcript** before the analysis (filler words, punctuation, recognition errors) — the original is kept and can be toggled in the transcript tab; plus a **personal and a shared glossary** for abbreviations and domain terms
- 🧠 **AI summary** through any **OpenAI-compatible** chat endpoint — local (vLLM, Ollama) or cloud (OpenAI, Anthropic, Google Gemini, Groq, Mistral …)
- 📎 **Attached documents**: upload the agenda, slides or papers for a recording — their text feeds into the summary (PDF/Office/OCR through an Apache Tika server)
- 🏷️ **Tags & search**: tag recordings, filter by tag, and search titles, meeting URLs, tags and — on request — **transcript and summary**
- 📚 **Paged list with filters**: the recordings list loads page by page and filters by period, source (bot, upload, screen) and owner, sorted by date or title
- 🎛️ **Analysis adjustable per recording**: your own analysis prompt (with templates for talks, interviews, voice notes), maximum length, summary language — and the language of the speech recognition (including "detect automatically")
- 📄 **Download as Markdown or Word**: transcript (smoothed or original) and summary as `.md` or as `.doc`, which Word and LibreOffice open directly — and can save as DOCX or PDF from there
- ▶️ **Jump from the transcript into the recording**: clicking a transcript line sets the playback position and the current line is highlighted — plus a continuous player and a download of the whole recording as a single MP3
- 🔁 **Analyse again** (summary only) and **recreate the transcript** (speech recognition + summary) with one click — every analysis adds another **version** you can switch between
- ⏱️ **Processing time window** (STT/LLM at night, for example) plus "Analyse now"
- 📊 **Queue overview for admins**: the **Processing** tab shows what is waiting, what is running, recent failures with their reason, the duration of each step, and whether the time window is open — failed jobs can be retried from there
- 🧪 **Connection tests** for Whisper and the LLM right in the admin area
- 👥 **Sharing & groups**, admin area for settings and users
- 🔗 **Share link**: pass a recording on by URL — either **account-bound** (the recipient signs in and is granted access automatically, the default) or **without sign-in** for external people; with an optional lifetime, revocable at any time, and installation-wide restrictable to "sign-in required" by an admin switch
- 🌐 **Interface in German and English** – each user picks their own language, stored with the account (so it applies on every device)
- 🔐 **Sign-in** with a local account **or** LDAP/Active Directory — fully configurable and testable in the admin area
- 🧹 **Housekeeping**: retention period for old recordings, cleanup of recordings that got stuck

## Tags

- `latest` — current state
- `<git-hash>` — a specific commit (reproducible deployments)
- version tags such as `v3.0.0` where applicable

## Ports & volumes

- Port **8080** — UI + API
- Volume **`/data/recordings`** — recordings, transcripts, summaries, MP4s, attached documents

## Important environment variables

| Variable | Meaning |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL connection (required) |
| `JWT_SECRET` | Signing key for sessions (at least 32 random characters) |
| `JWT_TTL_HOURS` | Sign-in session lifetime in hours (default 168 = 7 days) |
| `ADMIN_USERNAME`, `ADMIN_INITIAL_PASSWORD` | Local admin account on first start (a password change is enforced) |
| `STORAGE_DIR` | Recording directory (default `/data/recordings`) |
| `MAX_UPLOAD_SIZE` | Maximum size for file uploads (default `4GB`) |
| `MAX_CONCURRENT_BOTS` | Maximum concurrent bots (default 5) |
| `INSECURE_TLS` | Accept self-signed certificates on an intranet |
| `SERVER_PORT` | HTTP port inside the container (default 8080) |

> Everything else (Whisper, LLM, time window, bot behaviour, screen recording,
> retention, LDAP) is configured **at runtime in the admin area** and stored in
> the database — no container restarts needed.

## Reverse proxy / HTTPS

The container speaks HTTP; TLS termination is usually handled by a reverse proxy
in front of it. **HTTPS is mandatory for screen recording** — without a secure
context the browser does not expose `getDisplayMedia` at all. The proxy should
set `X-Forwarded-Proto`/`-Host` (the app evaluates them) and must not buffer the
request body (`proxy_request_buffering off`), so that the incrementally uploaded
recording passes straight through. Ready-made nginx and Apache configurations:
[docs/SCREEN_CAPTURE.md](https://github.com/Posiuke/Meeting-Recorder/blob/master/docs/SCREEN_CAPTURE.md).

## Quick start (docker compose)

```yaml
services:
  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: bbbbot
      POSTGRES_USER: bbbbot
      POSTGRES_PASSWORD: please-change-me
    volumes:
      - pgdata:/var/lib/postgresql/data

  app:
    image: posiuke/bbb-recorder:latest
    depends_on:
      - db
    environment:
      DB_URL: jdbc:postgresql://db:5432/bbbbot
      DB_USER: bbbbot
      DB_PASSWORD: please-change-me
      JWT_SECRET: please-enter-a-long-random-value
      ADMIN_USERNAME: admin
      ADMIN_INITIAL_PASSWORD: please-change-me
      STORAGE_DIR: /data/recordings
    ports:
      - "8090:8080"
    volumes:
      - ./data/recordings:/data/recordings
    shm_size: "1gb"

volumes:
  pgdata:
```

## First sign-in

Sign in with `ADMIN_USERNAME` / `ADMIN_INITIAL_PASSWORD`. A new password has to
be set on the first sign-in. After that you can optionally enable and test
LDAP/AD under **Admin → Authentication**.

## Setting up speech recognition and AI

Recording and playback work even without these services configured — only the
transcript and the summary need Whisper and an LLM. Both are configured under
**Admin → Settings**; a click on **"Test connection"** checks the stored
configuration right away (reachability, API key and model name).

### Option A: your own servers (intranet, the default)

| Setting | Value |
|---|---|
| `whisper.provider` | `local` |
| `whisper.url` | e.g. `http://whisper:9000/asr` ([onerahmet/openai-whisper-asr-webservice](https://hub.docker.com/r/onerahmet/openai-whisper-asr-webservice); with `ASR_ENGINE=whisperx` speaker separation is possible, then enable `whisper.diarize`) |
| `llm.baseUrl` | e.g. `http://vllm:8000/v1` (vLLM, Ollama or any other OpenAI-compatible server) |
| `llm.model` | Name of the loaded model |

The data stays entirely inside your own network.

### Option B: public cloud APIs

**Transcription** (OpenAI audio API format):

| Setting | Value |
|---|---|
| `whisper.provider` | `openai` |
| `whisper.openaiUrl` | `https://api.openai.com/v1/audio/transcriptions` (compatible providers such as Groq work the same way) |
| `whisper.openaiApiKey` | The provider's API key |
| `whisper.openaiModel` | e.g. `whisper-1` (with timestamps) or `gpt-4o-mini-transcribe` |

**Summary** (any OpenAI-compatible chat endpoint):

| Provider | `llm.baseUrl` | `llm.model` (example) |
|---|---|---|
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| Anthropic | `https://api.anthropic.com/v1` | `claude-sonnet-5` |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` | `gemini-2.5-flash` |
| Groq | `https://api.groq.com/openai/v1` | `llama-3.3-70b-versatile` |
| Mistral | `https://api.mistral.ai/v1` | `mistral-large-latest` |

Set `llm.apiKey` alongside each of them.

> ⚠️ **Data protection:** with cloud APIs, audio data (Whisper) and the
> transcript plus chat log (LLM) leave your own network. Speaker separation
> (diarisation) is not supported by the OpenAI audio API and is only available
> with your own WhisperX server.

## Transcript smoothing and glossary

Between speech recognition and analysis, the LLM smooths the raw transcript. The
summary uses the smoothed version; the Whisper original stays stored and can be
viewed in the transcript tab via *Corrected / Original* (`transcript.md` and
`transcript_original.md`).

Smoothing works **sentence by sentence and in several steps**: Whisper lines
often end mid-sentence, so they are joined into whole sentences; a sentence is
never cut across two steps, and a change of speaker always ends a sentence.
Timestamps and speaker labels are not left to the model: it only receives the
numbered sentences, and the backend puts the structure back in front of them. If
smoothing fails, the analysis carries on unaffected with the original.

Under **Glossary** there are two lists: your own and a **shared glossary for the
installation**, maintained by admins and readable by everyone. For a recording
both feed into the smoothing prompt — the shared one and the personal one of its
owner; if a term appears in both, the personal entry wins.

| Setting | Default | Meaning |
|---|---|---|
| `correction.enabled` | `true` | Smoothing on/off |
| `correction.systemPrompt` | (built-in default prompt) | Instruction to the LLM; keep the `number \| sentence` response format |
| `correction.chunkChars` | `3000` | Characters per smoothing step (also determines the response token budget) |
| `correction.maxSentenceChars` | `500` | Where to split when the transcript contains no punctuation |
| `correction.glossaryMaxChars` | `12000` | How much glossary goes into the prompt (0 = unlimited) |

## Tags, search and filters

The owner assigns tags on the detail page (max. 40 characters, 20 per recording;
different spellings are merged); everyone with read access sees and filters by
them. Above the list, a search field covers title/room name, meeting URL and
tags — with the checkbox **"Also search transcript and summary"** the contents as
well. There is nothing to configure; the search only ever returns your own and
shared recordings.

The list loads **page by page** ("Load more") and can be narrowed by **period**,
**source** (bot in the meeting, upload, screen recording) and **owner**, sorted
by date or title. Slicing, filtering and sorting happen in the database, so the
browser only ever holds what it displays.

The content search uses `LIKE` without a full-text index — unproblematic for a
few thousand recordings; for very large holdings a Postgres full-text index
would be the next step.

## Screen recording

Users record their screen via **Recordings → Record screen** directly in the
browser; the running recording is uploaded in chunks, so a crash costs at most
the last few seconds. HTTPS and Chrome/Edge are required (Firefox does not
capture system audio).

| Setting | Default | Meaning |
|---|---|---|
| `capture.enabled` | `true` | Feature available to users |
| `capture.maxMegabytes` | `8192` | Upper limit per recording (rule of thumb: 0.5 GB per hour at standard quality, about 60 MB without video) |
| `capture.staleMinutes` | `5` | After this long without data a recording counts as aborted; the server then finalises it with the data it has |

Setup, reverse proxy configuration and troubleshooting:
[docs/SCREEN_CAPTURE.md](https://github.com/Posiuke/Meeting-Recorder/blob/master/docs/SCREEN_CAPTURE.md).

## Attached documents

In a recording's **Documents** tab you can attach whatever was discussed in the
meeting — the agenda, slides, an offer. Their text feeds into the next AI
analysis, so the summary knows the subject and not just what was said.

Text and Markdown files are read by the app itself. **PDF, Office files and
scans need an Apache Tika server**; OCR of scanned pages is done by Tika using
tesseract. To set that up, enable the commented-out `tika` service in
[`docker-compose.yml`](https://github.com/Posiuke/Meeting-Recorder/blob/master/docker-compose.yml) (`apache/tika:latest-full` ships with tesseract) and set
`tikaUrl` to `http://tika:9998` in the admin area under *Attached documents*. If
you already run a Tika server, just enter its address there. Without it, PDFs
fail with a clear message — and the tab says so beforehand.

The documents section feeds into **every** analysis step; how much of it is
limited by `documents.maxCharsPerDocument` (per file) and
`documents.promptMaxChars` (in total). With a cloud API the documents therefore
leave your own network as well. They do not appear in a share view.

## Adjusting the analysis per recording

On the recording detail page the owner can use **"Customise analysis"** to set
their own analysis prompt (with templates for talks, interviews and voice notes),
a maximum length and the summary language — handy for uploaded files that are not
meetings. The **language of the recording** for speech recognition (including
"detect automatically") lives there too; it can also be picked in the upload
dialog, in the bot form and when starting a screen recording, because a wrong
language hint damages the transcript from the very beginning. The settings take
effect on the next analysis ("Analyse now", "Analyse again" or "Recreate
transcript"). The administrator's default can be pulled into the field with
**"Load default"** and edited there, instead of having to replace it entirely.

Model and temperature can be overridden **per template and per recording**
(`llm.model`/`llm.temperature` remain the default). That makes it possible to
compare two models on the same recording: switch, "Analyse again", read both
versions side by side.

A repeated analysis **does not replace the summary**; it adds another **version**
next to it — labelled with template, model and timestamp, along with the prompt
it was created from. In the **Summary** tab you can switch between versions;
exactly one is the current one and is used in the download, the API, the share
view and `summary.md`. An older version can be brought back with **"Make
current"**, and older ones can be deleted individually. Nothing is cleaned up
automatically — hand-edited versions stay.

Summary and smoothed transcript are rendered as GitHub Markdown — tables and task
lists appear as such. Code blocks with the language `mermaid` are drawn as a
diagram; you can ask for that in the analysis prompt ("… additionally as a
Mermaid flowchart").

Both can be **downloaded**: the transcript in the transcript tab (always the
version currently shown — corrected or original), the summary via the button in
the recording header. Besides Markdown there is a **Word version** (`.doc`) of
each, which Word and LibreOffice open directly and from which DOCX or PDF can be
saved.

## Queue overview for admins

With one GPU, a nightly time window and several bots, the queue is where it is
decided whether everything is finished by morning. The **Admin → Processing** tab
is the page to open in the morning:

- the **time window** at the top: open or closed, with the configured times — and
  how many jobs are only waiting for it,
- **counters**: waiting / running / failed / finished,
- the **queue** with recording, task, attempt ("2 of 3") and waiting time,
- **recent failures** with their reason and a **Retry** button. It resets the
  attempt counter and starts the job immediately, regardless of the time window,
- **step durations**: median and maximum of the last 50 finished jobs, broken
  down into speech recognition, smoothing and summary — so an outlier stands out
  against the normal case.

Also available through the API: `GET /api/admin/processing` and
`POST /api/admin/processing/jobs/{jobId}/retry` (both admin only).

## Share link

Via **Share → Link for sharing** the owner of a recording creates a URL
(`https://<host>/share/<token>`) and chooses the **access**:

- **Sign-in required** (default): opening the link takes the recipient to the
  sign-in page; afterwards the recording is shared with their account and appears
  in their recordings list. Every access stays attributable to a person.
- **Without sign-in**: anyone who knows the URL sees **video, audio, transcript
  and summary** without an account — for recipients who have no access to the
  system.

The chat log and session log remain reserved for the signed-in view. The lifetime
is either unlimited (until revoked) or 7/30/90 days; a revocation takes effect
immediately. The dialog shows the kind, the number of views and the last access
for each link. If the recording is deleted, its links go with it.

If accesses must stay attributable as a matter of policy, switch off the admin
setting `sharing.publicLinks`: then **all** share links require a sign-in, including
ones created earlier.

A note on downloads: whether the browser asks for a target folder when
downloading is a browser setting (Chrome/Edge: *Settings → Downloads → "Ask where
to save each file before downloading"*). The application cannot force it.

## Source code, bugs and feature requests

The source code is public on GitHub:
[Posiuke/Meeting-Recorder](https://github.com/Posiuke/Meeting-Recorder).

Bug reports and feature requests are welcome — from anyone, straight as an issue:
[report a bug](https://github.com/Posiuke/Meeting-Recorder/issues/new?template=bug_report.yml)
· [request a feature](https://github.com/Posiuke/Meeting-Recorder/issues/new?template=feature_request.yml)
· [all issues](https://github.com/Posiuke/Meeting-Recorder/issues).

Please strip credentials, API keys, internal host names and meeting content from
logs first — issues are publicly readable.
