DELETE FROM practice_assessment
WHERE attempt_id IN (SELECT id FROM practice_attempt);

DELETE FROM practice_attempt;
DELETE FROM practice_draft;
DELETE FROM practice_assignment;
