CREATE TABLE prompt_version (
  prompt_key VARCHAR(80) NOT NULL,
  version_number INTEGER NOT NULL,
  schema_version VARCHAR(32) NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (prompt_key, version_number),
  CONSTRAINT uq_active_prompt_hash UNIQUE (prompt_key, content_hash)
);

CREATE TABLE practice_assignment (
  id UUID PRIMARY KEY,
  owner_id UUID NOT NULL,
  scenario_id UUID,
  target_category_code VARCHAR(40) NOT NULL,
  domain_text VARCHAR(120) NOT NULL,
  situation_text CLOB NOT NULL,
  guidance_text CLOB NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_practice_assignment_owner
    FOREIGN KEY (owner_id) REFERENCES app_user(id) ON DELETE CASCADE,
  CONSTRAINT fk_practice_assignment_scenario
    FOREIGN KEY (scenario_id) REFERENCES scenario(id),
  CONSTRAINT fk_practice_assignment_category
    FOREIGN KEY (target_category_code) REFERENCES category(code)
);

CREATE INDEX idx_practice_assignment_owner
  ON practice_assignment(owner_id, created_at DESC);

CREATE TABLE practice_attempt (
  id UUID PRIMARY KEY,
  assignment_id UUID NOT NULL,
  owner_id UUID NOT NULL,
  parent_attempt_id UUID,
  attempt_number INTEGER NOT NULL,
  question_text CLOB NOT NULL,
  answer_text CLOB NOT NULL,
  reasoning_text CLOB NOT NULL,
  solution_text CLOB NOT NULL,
  revised_fields_json CLOB NOT NULL,
  status VARCHAR(24) NOT NULL,
  requested_model VARCHAR(120),
  idempotency_key VARCHAR(100),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  completed_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT uq_practice_attempt_number UNIQUE (assignment_id, attempt_number),
  CONSTRAINT uq_practice_attempt_idempotency UNIQUE (owner_id, idempotency_key),
  CONSTRAINT fk_practice_attempt_assignment
    FOREIGN KEY (assignment_id) REFERENCES practice_assignment(id),
  CONSTRAINT fk_practice_attempt_owner
    FOREIGN KEY (owner_id) REFERENCES app_user(id) ON DELETE CASCADE,
  CONSTRAINT fk_practice_attempt_parent
    FOREIGN KEY (parent_attempt_id) REFERENCES practice_attempt(id),
  CONSTRAINT chk_practice_attempt_number CHECK (attempt_number > 0),
  CONSTRAINT chk_practice_attempt_status CHECK (
    status IN ('EVALUATING', 'PASSED', 'NEEDS_REVISION', 'UNVERIFIED')
  )
);

CREATE INDEX idx_practice_attempt_owner_status
  ON practice_attempt(owner_id, status, created_at DESC);

CREATE TABLE practice_assessment (
  id UUID PRIMARY KEY,
  attempt_id UUID NOT NULL UNIQUE,
  outcome VARCHAR(16) NOT NULL,
  completeness_status VARCHAR(8) NOT NULL,
  step_results_json CLOB NOT NULL,
  category_fit_score SMALLINT,
  category_fit_evidence CLOB,
  confused_with VARCHAR(40),
  question_strength_score SMALLINT,
  strength_dimensions_json CLOB,
  confidence VARCHAR(8),
  strengths_json CLOB NOT NULL,
  correction_what CLOB NOT NULL,
  correction_why CLOB NOT NULL,
  correction_example CLOB NOT NULL,
  fields_to_revise_json CLOB NOT NULL,
  feedback_text CLOB NOT NULL,
  prompt_key VARCHAR(80) NOT NULL,
  prompt_version INTEGER NOT NULL,
  schema_version VARCHAR(32) NOT NULL,
  model_id VARCHAR(160),
  latency_ms BIGINT NOT NULL,
  failure_reason VARCHAR(80),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_practice_assessment_attempt
    FOREIGN KEY (attempt_id) REFERENCES practice_attempt(id) ON DELETE CASCADE,
  CONSTRAINT fk_practice_assessment_confused
    FOREIGN KEY (confused_with) REFERENCES category(code),
  CONSTRAINT fk_practice_assessment_prompt
    FOREIGN KEY (prompt_key, prompt_version)
      REFERENCES prompt_version(prompt_key, version_number),
  CONSTRAINT chk_practice_assessment_outcome CHECK (
    outcome IN ('VERIFIED', 'UNVERIFIED')
  ),
  CONSTRAINT chk_practice_completeness CHECK (
    completeness_status IN ('PASS', 'FAIL')
  ),
  CONSTRAINT chk_practice_category_fit CHECK (
    category_fit_score IS NULL OR category_fit_score BETWEEN 0 AND 3
  ),
  CONSTRAINT chk_practice_strength CHECK (
    question_strength_score IS NULL OR question_strength_score BETWEEN 0 AND 4
  ),
  CONSTRAINT chk_practice_confidence CHECK (
    confidence IS NULL OR confidence IN ('HIGH', 'MEDIUM', 'LOW')
  ),
  CONSTRAINT chk_practice_verified_scores CHECK (
    outcome='UNVERIFIED' OR (
      category_fit_score IS NOT NULL
      AND question_strength_score IS NOT NULL
      AND confidence IS NOT NULL
    )
  )
);
