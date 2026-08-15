ALTER TABLE practice_attempt ADD COLUMN rationale_text CLOB;
UPDATE practice_attempt
SET rationale_text = 'Ответ на вопрос:' || CHAR(10) || answer_text
  || CHAR(10) || CHAR(10) || 'Ход рассуждения:' || CHAR(10) || reasoning_text;
ALTER TABLE practice_attempt ALTER COLUMN rationale_text SET NOT NULL;
ALTER TABLE practice_attempt ALTER COLUMN answer_text DROP NOT NULL;
ALTER TABLE practice_attempt ALTER COLUMN reasoning_text DROP NOT NULL;

ALTER TABLE practice_draft ADD COLUMN rationale_text CLOB;
UPDATE practice_draft
SET rationale_text = 'Ответ на вопрос:' || CHAR(10) || answer_text
  || CHAR(10) || CHAR(10) || 'Ход рассуждения:' || CHAR(10) || reasoning_text;
ALTER TABLE practice_draft ALTER COLUMN rationale_text SET NOT NULL;
ALTER TABLE practice_draft ALTER COLUMN answer_text DROP NOT NULL;
ALTER TABLE practice_draft ALTER COLUMN reasoning_text DROP NOT NULL;

ALTER TABLE practice_example ADD COLUMN rationale_text CLOB;
UPDATE practice_example
SET rationale_text = 'Ответ на вопрос:' || CHAR(10) || answer_text
  || CHAR(10) || CHAR(10) || 'Ход рассуждения:' || CHAR(10) || reasoning_text;
ALTER TABLE practice_example ALTER COLUMN rationale_text SET NOT NULL;
ALTER TABLE practice_example ALTER COLUMN answer_text DROP NOT NULL;
ALTER TABLE practice_example ALTER COLUMN reasoning_text DROP NOT NULL;
