CREATE TABLE scenario_candidate (
  id UUID PRIMARY KEY,
  status VARCHAR(24) NOT NULL,
  version_number INTEGER NOT NULL DEFAULT 0,
  category_code VARCHAR(40),
  secondary_category_code VARCHAR(40),
  difficulty VARCHAR(4),
  domain_text VARCHAR(120),
  situation_text CLOB,
  question_text CLOB,
  hint_text CLOB,
  options_json CLOB,
  correct_category_code VARCHAR(40),
  explanation_text CLOB,
  confused_with VARCHAR(40),
  contrast_explanation CLOB,
  content_hash VARCHAR(64),
  source_model VARCHAR(160),
  rejection_reasons_json CLOB NOT NULL,
  warnings_json CLOB NOT NULL,
  published_scenario_id UUID,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_candidate_category FOREIGN KEY (category_code) REFERENCES category(code),
  CONSTRAINT fk_candidate_secondary FOREIGN KEY (secondary_category_code) REFERENCES category(code),
  CONSTRAINT fk_candidate_correct FOREIGN KEY (correct_category_code) REFERENCES category(code),
  CONSTRAINT fk_candidate_confused FOREIGN KEY (confused_with) REFERENCES category(code),
  CONSTRAINT fk_candidate_published FOREIGN KEY (published_scenario_id) REFERENCES scenario(id),
  CONSTRAINT chk_candidate_status CHECK (
    status IN ('GENERATING', 'PENDING_REVIEW', 'AUTO_REJECTED', 'REJECTED', 'PUBLISHED')
  ),
  CONSTRAINT chk_candidate_version CHECK (version_number >= 0),
  CONSTRAINT chk_candidate_difficulty CHECK (
    difficulty IS NULL OR difficulty IN ('L1', 'L2', 'L3')
  )
);

CREATE INDEX idx_candidate_queue
  ON scenario_candidate(status, updated_at, created_at);
CREATE INDEX idx_candidate_hash ON scenario_candidate(content_hash);

CREATE TABLE moderation_action (
  id UUID PRIMARY KEY,
  candidate_id UUID NOT NULL,
  actor_id UUID NOT NULL,
  action_type VARCHAR(24) NOT NULL,
  reason_code VARCHAR(40),
  comment_text VARCHAR(1000),
  previous_status VARCHAR(24) NOT NULL,
  new_status VARCHAR(24) NOT NULL,
  previous_version INTEGER NOT NULL,
  new_version INTEGER NOT NULL,
  before_json CLOB NOT NULL,
  after_json CLOB NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_moderation_candidate
    FOREIGN KEY (candidate_id) REFERENCES scenario_candidate(id) ON DELETE CASCADE,
  CONSTRAINT fk_moderation_actor
    FOREIGN KEY (actor_id) REFERENCES app_user(id),
  CONSTRAINT chk_moderation_action CHECK (
    action_type IN ('GENERATE', 'AUTO_REJECT', 'EDIT', 'REJECT', 'APPROVE_PUBLISH')
  ),
  CONSTRAINT chk_moderation_versions CHECK (
    previous_version >= 0 AND new_version > previous_version
  )
);

CREATE INDEX idx_moderation_candidate
  ON moderation_action(candidate_id, created_at);
