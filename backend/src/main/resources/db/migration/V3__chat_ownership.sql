ALTER TABLE chat_session ADD COLUMN owner_id UUID;

UPDATE chat_session
SET owner_id = '00000000-0000-0000-0000-000000000001'
WHERE owner_id IS NULL;

ALTER TABLE chat_session ALTER COLUMN owner_id SET NOT NULL;

ALTER TABLE chat_session ADD CONSTRAINT fk_chat_session_owner
  FOREIGN KEY (owner_id) REFERENCES app_user(id);

CREATE INDEX idx_chat_session_owner_updated
  ON chat_session(owner_id, updated_at DESC);
