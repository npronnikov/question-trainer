CREATE TABLE category (
  code VARCHAR(40) PRIMARY KEY,
  sort_order SMALLINT NOT NULL UNIQUE,
  display_number VARCHAR(4) NOT NULL,
  name VARCHAR(120) NOT NULL,
  nickname VARCHAR(180) NOT NULL,
  operation_text VARCHAR(600) NOT NULL,
  signal_text VARCHAR(600) NOT NULL,
  when_text VARCHAR(900) NOT NULL,
  definition_text CLOB NOT NULL,
  mechanism_text CLOB NOT NULL,
  formula_json CLOB NOT NULL,
  examples_json CLOB NOT NULL,
  mistake_text CLOB NOT NULL,
  cue_text CLOB NOT NULL,
  strength_anchors_json CLOB NOT NULL
);

CREATE TABLE evidence_source (
  source_key VARCHAR(100) PRIMARY KEY,
  title VARCHAR(500) NOT NULL,
  source_url VARCHAR(1200),
  supports_text CLOB NOT NULL,
  evidence_grade VARCHAR(32) NOT NULL,
  CONSTRAINT chk_evidence_grade CHECK (
    evidence_grade IN ('RESEARCH_SUPPORTED', 'PRACTITIONER_METHOD', 'HEURISTIC')
  )
);

CREATE TABLE theory_section (
  id UUID PRIMARY KEY,
  category_code VARCHAR(40) NOT NULL,
  section_key VARCHAR(80) NOT NULL,
  title VARCHAR(240) NOT NULL,
  content_text CLOB NOT NULL,
  evidence_grade VARCHAR(32) NOT NULL,
  source_key VARCHAR(100),
  sort_order SMALLINT NOT NULL,
  CONSTRAINT uq_theory_section UNIQUE (category_code, section_key),
  CONSTRAINT fk_theory_category FOREIGN KEY (category_code) REFERENCES category(code),
  CONSTRAINT fk_theory_source FOREIGN KEY (source_key) REFERENCES evidence_source(source_key),
  CONSTRAINT chk_theory_evidence_grade CHECK (
    evidence_grade IN ('RESEARCH_SUPPORTED', 'PRACTITIONER_METHOD', 'HEURISTIC')
  )
);

CREATE TABLE category_contrast (
  category_code VARCHAR(40) NOT NULL,
  other_category_code VARCHAR(40) NOT NULL,
  contrast_text CLOB NOT NULL,
  PRIMARY KEY (category_code, other_category_code),
  CONSTRAINT fk_contrast_category FOREIGN KEY (category_code) REFERENCES category(code),
  CONSTRAINT fk_contrast_other FOREIGN KEY (other_category_code) REFERENCES category(code),
  CONSTRAINT chk_contrast_distinct CHECK (category_code <> other_category_code)
);

CREATE TABLE scenario (
  id UUID PRIMARY KEY,
  external_key VARCHAR(100) NOT NULL UNIQUE,
  category_code VARCHAR(40) NOT NULL,
  difficulty VARCHAR(4) NOT NULL,
  domain_text VARCHAR(120) NOT NULL,
  situation_text CLOB NOT NULL,
  question_text CLOB NOT NULL,
  explanation_text CLOB NOT NULL,
  confused_with VARCHAR(40),
  contrast_explanation CLOB,
  content_hash VARCHAR(64) NOT NULL UNIQUE,
  published BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_scenario_category FOREIGN KEY (category_code) REFERENCES category(code),
  CONSTRAINT fk_scenario_confused FOREIGN KEY (confused_with) REFERENCES category(code),
  CONSTRAINT chk_scenario_difficulty CHECK (difficulty IN ('L1', 'L2', 'L3')),
  CONSTRAINT chk_l3_contrast CHECK (
    difficulty <> 'L3' OR (confused_with IS NOT NULL AND contrast_explanation IS NOT NULL)
  )
);

CREATE INDEX idx_scenario_pool ON scenario(published, category_code, difficulty);

CREATE TABLE scenario_option (
  scenario_id UUID NOT NULL,
  category_code VARCHAR(40) NOT NULL,
  sort_order SMALLINT NOT NULL,
  PRIMARY KEY (scenario_id, category_code),
  CONSTRAINT uq_scenario_option_order UNIQUE (scenario_id, sort_order),
  CONSTRAINT fk_option_scenario FOREIGN KEY (scenario_id) REFERENCES scenario(id) ON DELETE CASCADE,
  CONSTRAINT fk_option_category FOREIGN KEY (category_code) REFERENCES category(code)
);

CREATE TABLE trainer_issuance (
  id UUID PRIMARY KEY,
  owner_id UUID NOT NULL,
  scenario_id UUID NOT NULL,
  status VARCHAR(16) NOT NULL,
  issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  answered_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT fk_issuance_owner FOREIGN KEY (owner_id) REFERENCES app_user(id) ON DELETE CASCADE,
  CONSTRAINT fk_issuance_scenario FOREIGN KEY (scenario_id) REFERENCES scenario(id),
  CONSTRAINT chk_issuance_status CHECK (status IN ('ISSUED', 'ANSWERED', 'EXPIRED'))
);

CREATE INDEX idx_issuance_owner_status ON trainer_issuance(owner_id, status, issued_at DESC);

CREATE TABLE trainer_attempt (
  id UUID PRIMARY KEY,
  issuance_id UUID NOT NULL UNIQUE,
  owner_id UUID NOT NULL,
  scenario_id UUID NOT NULL,
  selected_category_code VARCHAR(40) NOT NULL,
  rationale_text CLOB NOT NULL,
  correct BOOLEAN NOT NULL,
  mastery_delta DECIMAL(6,2) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_attempt_issuance FOREIGN KEY (issuance_id) REFERENCES trainer_issuance(id),
  CONSTRAINT fk_attempt_owner FOREIGN KEY (owner_id) REFERENCES app_user(id) ON DELETE CASCADE,
  CONSTRAINT fk_attempt_scenario FOREIGN KEY (scenario_id) REFERENCES scenario(id),
  CONSTRAINT fk_attempt_selected FOREIGN KEY (selected_category_code) REFERENCES category(code)
);

CREATE INDEX idx_attempt_owner_created ON trainer_attempt(owner_id, created_at DESC);

CREATE TABLE category_mastery (
  owner_id UUID NOT NULL,
  category_code VARCHAR(40) NOT NULL,
  mastery_score DECIMAL(6,2) NOT NULL DEFAULT 0,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  correct_count INTEGER NOT NULL DEFAULT 0,
  last_seen_at TIMESTAMP WITH TIME ZONE,
  next_review_at TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (owner_id, category_code),
  CONSTRAINT fk_mastery_owner FOREIGN KEY (owner_id) REFERENCES app_user(id) ON DELETE CASCADE,
  CONSTRAINT fk_mastery_category FOREIGN KEY (category_code) REFERENCES category(code),
  CONSTRAINT chk_mastery_score CHECK (mastery_score BETWEEN 0 AND 100),
  CONSTRAINT chk_mastery_counts CHECK (
    attempt_count >= 0 AND correct_count >= 0 AND correct_count <= attempt_count
  )
);

CREATE TABLE category_confusion (
  owner_id UUID NOT NULL,
  selected_category_code VARCHAR(40) NOT NULL,
  correct_category_code VARCHAR(40) NOT NULL,
  confusion_count INTEGER NOT NULL DEFAULT 0,
  last_confused_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (owner_id, selected_category_code, correct_category_code),
  CONSTRAINT fk_confusion_owner FOREIGN KEY (owner_id) REFERENCES app_user(id) ON DELETE CASCADE,
  CONSTRAINT fk_confusion_selected FOREIGN KEY (selected_category_code) REFERENCES category(code),
  CONSTRAINT fk_confusion_correct FOREIGN KEY (correct_category_code) REFERENCES category(code),
  CONSTRAINT chk_confusion_distinct CHECK (selected_category_code <> correct_category_code),
  CONSTRAINT chk_confusion_count CHECK (confusion_count > 0)
);

CREATE INDEX idx_confusion_owner_count
  ON category_confusion(owner_id, confusion_count DESC, last_confused_at DESC);
