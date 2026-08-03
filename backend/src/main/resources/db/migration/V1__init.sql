-- Grundschema BBB-Bot v3

CREATE TABLE app_user (
    id            UUID PRIMARY KEY,
    username      VARCHAR(255) NOT NULL UNIQUE,
    display_name  VARCHAR(255),
    email         VARCHAR(255),
    is_admin      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL,
    last_login_at TIMESTAMPTZ
);

CREATE TABLE user_group (
    id         UUID PRIMARY KEY,
    name       VARCHAR(255) NOT NULL UNIQUE,
    owner_id   UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE group_member (
    id       UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES user_group(id) ON DELETE CASCADE,
    user_id  UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    added_at TIMESTAMPTZ NOT NULL,
    UNIQUE (group_id, user_id)
);

CREATE TABLE bot_session (
    id           UUID PRIMARY KEY,
    meeting_url  TEXT NOT NULL,
    bot_name     VARCHAR(255) NOT NULL,
    status       VARCHAR(32) NOT NULL,
    created_by   UUID NOT NULL REFERENCES app_user(id),
    created_at   TIMESTAMPTZ NOT NULL,
    ended_at     TIMESTAMPTZ,
    last_error   TEXT,
    auto_record  BOOLEAN NOT NULL DEFAULT TRUE,
    record_video BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE recording (
    id               UUID PRIMARY KEY,
    bot_session_id   UUID REFERENCES bot_session(id) ON DELETE SET NULL,
    owner_id         UUID NOT NULL REFERENCES app_user(id),
    title            VARCHAR(512),
    meeting_url      TEXT,
    status           VARCHAR(32) NOT NULL,
    started_at       TIMESTAMPTZ NOT NULL,
    ended_at         TIMESTAMPTZ,
    directory        TEXT NOT NULL,
    participants_log TEXT,
    chat_log         TEXT,
    duration_ms      BIGINT,
    discard_reason   TEXT
);
CREATE INDEX idx_recording_owner ON recording(owner_id);
CREATE INDEX idx_recording_status ON recording(status);

CREATE TABLE recording_segment (
    id              UUID PRIMARY KEY,
    recording_id    UUID NOT NULL REFERENCES recording(id) ON DELETE CASCADE,
    seq             INT NOT NULL,
    status          VARCHAR(32) NOT NULL,
    webm_path       TEXT,
    mp3_path        TEXT,
    size_bytes      BIGINT,
    duration_ms     BIGINT,
    transcript_text TEXT,
    error           TEXT,
    UNIQUE (recording_id, seq)
);

CREATE TABLE summary (
    id           UUID PRIMARY KEY,
    recording_id UUID NOT NULL REFERENCES recording(id) ON DELETE CASCADE,
    status       VARCHAR(32) NOT NULL,
    markdown     TEXT,
    model        VARCHAR(255),
    error        TEXT,
    created_at   TIMESTAMPTZ NOT NULL,
    finished_at  TIMESTAMPTZ
);
CREATE INDEX idx_summary_recording ON summary(recording_id);

CREATE TABLE share_grant (
    id               UUID PRIMARY KEY,
    recording_id     UUID NOT NULL REFERENCES recording(id) ON DELETE CASCADE,
    grantee_user_id  UUID REFERENCES app_user(id) ON DELETE CASCADE,
    grantee_group_id UUID REFERENCES user_group(id) ON DELETE CASCADE,
    created_by       UUID NOT NULL REFERENCES app_user(id),
    created_at       TIMESTAMPTZ NOT NULL,
    CHECK (grantee_user_id IS NOT NULL OR grantee_group_id IS NOT NULL)
);
CREATE INDEX idx_share_recording ON share_grant(recording_id);
CREATE INDEX idx_share_user ON share_grant(grantee_user_id);
CREATE INDEX idx_share_group ON share_grant(grantee_group_id);

CREATE TABLE processing_job (
    id           UUID PRIMARY KEY,
    recording_id UUID NOT NULL REFERENCES recording(id) ON DELETE CASCADE,
    job_type     VARCHAR(32) NOT NULL,
    status       VARCHAR(32) NOT NULL,
    immediate    BOOLEAN NOT NULL DEFAULT FALSE,
    attempts     INT NOT NULL DEFAULT 0,
    last_error   TEXT,
    created_at   TIMESTAMPTZ NOT NULL,
    started_at   TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ
);
CREATE INDEX idx_job_status ON processing_job(status);

CREATE TABLE app_setting (
    setting_key   VARCHAR(255) PRIMARY KEY,
    setting_value TEXT
);
