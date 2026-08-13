CREATE TABLE practice_draft (
  assignment_id UUID PRIMARY KEY,
  owner_id UUID NOT NULL,
  base_attempt_id UUID,
  question_text CLOB NOT NULL,
  answer_text CLOB NOT NULL,
  reasoning_text CLOB NOT NULL,
  solution_text CLOB NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_practice_draft_assignment
    FOREIGN KEY (assignment_id) REFERENCES practice_assignment(id) ON DELETE CASCADE,
  CONSTRAINT fk_practice_draft_owner
    FOREIGN KEY (owner_id) REFERENCES app_user(id) ON DELETE CASCADE,
  CONSTRAINT fk_practice_draft_base_attempt
    FOREIGN KEY (base_attempt_id) REFERENCES practice_attempt(id)
);

CREATE INDEX idx_practice_draft_owner_updated
  ON practice_draft(owner_id, updated_at DESC);

CREATE TABLE practice_example (
  id UUID PRIMARY KEY,
  category_code VARCHAR(40) NOT NULL UNIQUE,
  domain_text VARCHAR(120) NOT NULL,
  situation_text CLOB NOT NULL,
  question_text CLOB NOT NULL,
  answer_text CLOB NOT NULL,
  reasoning_text CLOB NOT NULL,
  solution_text CLOB NOT NULL,
  recommendation_text CLOB NOT NULL,
  published BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_practice_example_category
    FOREIGN KEY (category_code) REFERENCES category(code)
);

CREATE INDEX idx_practice_example_published
  ON practice_example(published, category_code);
