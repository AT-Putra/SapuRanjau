-- message — inbox pemain (ADR-0021). docs/08_DATA_SCHEMA.md §2.12.
CREATE TABLE message (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id     bigint NOT NULL REFERENCES app_user(id),
  admin_id    bigint NOT NULL,                            -- pengirim (skema admin terpisah)
  body        text   NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now(),
  read_at     timestamptz
);
CREATE INDEX message_inbox  ON message (user_id, created_at DESC);
CREATE INDEX message_unread ON message (user_id) WHERE read_at IS NULL;   -- badge belum-dibaca
