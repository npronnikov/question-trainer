CREATE TABLE app_user (
  id UUID PRIMARY KEY,
  username VARCHAR(80) NOT NULL,
  normalized_username VARCHAR(80) NOT NULL UNIQUE,
  email VARCHAR(254),
  normalized_email VARCHAR(254) UNIQUE,
  password_hash VARCHAR(255),
  system_account BOOLEAN NOT NULL DEFAULT FALSE,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT chk_login_capability CHECK (system_account OR password_hash IS NOT NULL)
);

CREATE TABLE user_role (
  user_id UUID NOT NULL,
  role VARCHAR(24) NOT NULL,
  PRIMARY KEY (user_id, role),
  CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
  CONSTRAINT chk_user_role CHECK (role IN ('USER', 'ADMIN'))
);

INSERT INTO app_user(
  id, username, normalized_username, email, normalized_email,
  password_hash, system_account, enabled, created_at, updated_at
)
VALUES (
  '00000000-0000-0000-0000-000000000001',
  '__system__',
  '__system__',
  NULL,
  NULL,
  NULL,
  TRUE,
  TRUE,
  CURRENT_TIMESTAMP,
  CURRENT_TIMESTAMP
);
