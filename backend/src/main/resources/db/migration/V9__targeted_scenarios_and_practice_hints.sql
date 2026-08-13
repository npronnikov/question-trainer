ALTER TABLE scenario_candidate
  ADD COLUMN content_target VARCHAR(16) NOT NULL DEFAULT 'TRAINER';

ALTER TABLE scenario_candidate
  ADD CONSTRAINT chk_candidate_target
  CHECK (content_target IN ('PRACTICE', 'TRAINER'));

ALTER TABLE scenario
  ADD COLUMN content_target VARCHAR(16) NOT NULL DEFAULT 'TRAINER';

ALTER TABLE scenario
  ADD COLUMN hint_text CLOB;

ALTER TABLE scenario ALTER COLUMN difficulty DROP NOT NULL;
ALTER TABLE scenario ALTER COLUMN question_text DROP NOT NULL;
ALTER TABLE scenario ALTER COLUMN explanation_text DROP NOT NULL;

ALTER TABLE scenario
  ADD CONSTRAINT chk_scenario_target
  CHECK (content_target IN ('PRACTICE', 'TRAINER'));

ALTER TABLE practice_assignment
  ADD COLUMN hint_text CLOB;
