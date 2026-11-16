-- V25 - Make sponsor impression writes idempotent per overlay display token.
CREATE UNIQUE INDEX IF NOT EXISTS uq_broadcast_sponsor_impression_session
    ON broadcast_sponsor_impressions(session_key)
    WHERE session_key IS NOT NULL;
