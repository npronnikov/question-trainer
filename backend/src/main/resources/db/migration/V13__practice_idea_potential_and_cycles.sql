ALTER TABLE practice_assignment ADD COLUMN sequence_number BIGINT;
ALTER TABLE practice_assignment ADD COLUMN cycle_number INTEGER;
ALTER TABLE practice_assignment ADD COLUMN cycle_position SMALLINT;

CREATE TABLE practice_assignment_cycle_backfill AS
SELECT id,
       ROW_NUMBER() OVER (
         PARTITION BY owner_id
         ORDER BY created_at, id
       ) AS sequence_number
FROM practice_assignment;

UPDATE practice_assignment assignment
SET sequence_number = (
      SELECT backfill.sequence_number
      FROM practice_assignment_cycle_backfill backfill
      WHERE backfill.id=assignment.id
    );

UPDATE practice_assignment
SET cycle_number = CAST(FLOOR((sequence_number - 1) / 7.0) + 1 AS INTEGER),
    cycle_position = CAST(MOD(sequence_number - 1, 7) + 1 AS SMALLINT);

DROP TABLE practice_assignment_cycle_backfill;

ALTER TABLE practice_assignment ALTER COLUMN sequence_number SET NOT NULL;
ALTER TABLE practice_assignment ALTER COLUMN cycle_number SET NOT NULL;
ALTER TABLE practice_assignment ALTER COLUMN cycle_position SET NOT NULL;

ALTER TABLE practice_assignment ADD CONSTRAINT uq_practice_assignment_sequence
  UNIQUE (owner_id, sequence_number);
ALTER TABLE practice_assignment ADD CONSTRAINT chk_practice_assignment_sequence
  CHECK (sequence_number > 0);
ALTER TABLE practice_assignment ADD CONSTRAINT chk_practice_assignment_cycle
  CHECK (cycle_number > 0 AND cycle_position BETWEEN 1 AND 7);
ALTER TABLE practice_assignment ADD CONSTRAINT chk_practice_assignment_cycle_coordinates
  CHECK (
    cycle_number = FLOOR((sequence_number - 1) / 7.0) + 1
    AND cycle_position = MOD(sequence_number - 1, 7) + 1
  );

ALTER TABLE practice_assessment ADD COLUMN idea_potential_score DECIMAL(3,2);
ALTER TABLE practice_assessment ADD COLUMN idea_potential_dimensions_json CLOB;
ALTER TABLE practice_assessment ADD CONSTRAINT chk_practice_idea_potential_score
  CHECK (idea_potential_score IS NULL OR idea_potential_score BETWEEN 0 AND 4);
