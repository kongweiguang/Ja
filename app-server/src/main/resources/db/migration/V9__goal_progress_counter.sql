-- @author kongweiguang
-- Preserve the observed count without the V1 0..3 stop threshold.
CREATE TABLE goal_no_progress_counts (
    goal_id TEXT PRIMARY KEY NOT NULL REFERENCES goals(goal_id) ON DELETE RESTRICT,
    count INTEGER NOT NULL CHECK (count >= 0)
);

INSERT INTO goal_no_progress_counts(goal_id, count)
SELECT goal_id, progress_turns_without_change FROM goals;

CREATE TABLE goal_repeated_failure_counts (
    goal_id TEXT PRIMARY KEY NOT NULL REFERENCES goals(goal_id) ON DELETE RESTRICT,
    count INTEGER NOT NULL CHECK (count >= 0)
);

INSERT INTO goal_repeated_failure_counts(goal_id, count)
SELECT goal_id, repeated_failure_count FROM goals;
