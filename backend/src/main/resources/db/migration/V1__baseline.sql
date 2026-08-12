CREATE TABLE IF NOT EXISTS chat_session (
  id UUID PRIMARY KEY,
  title VARCHAR(180) NOT NULL,
  acp_session_id VARCHAR(255),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS chat_message (
  id UUID PRIMARY KEY,
  session_id UUID NOT NULL,
  role VARCHAR(24) NOT NULL,
  source VARCHAR(24) NOT NULL,
  content CLOB NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_message_session FOREIGN KEY (session_id) REFERENCES chat_session(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_message_session_created ON chat_message(session_id, created_at);

CREATE TABLE IF NOT EXISTS generated_scenario (
  id UUID PRIMARY KEY,
  situation VARCHAR(1200) NOT NULL,
  category VARCHAR(64) NOT NULL,
  explanation VARCHAR(1800) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
