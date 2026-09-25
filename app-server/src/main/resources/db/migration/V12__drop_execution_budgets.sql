-- @author kongweiguang
-- SQLite 3.35+ can drop these inert columns in place, preserving execution_runs rowid,
-- inbound foreign keys, usage totals and Plan/Goal identities without table replacement.
ALTER TABLE execution_runs DROP COLUMN turns_used;
ALTER TABLE execution_runs DROP COLUMN turn_budget;
ALTER TABLE execution_runs DROP COLUMN max_model_rounds;
ALTER TABLE execution_runs DROP COLUMN max_tool_calls;
ALTER TABLE execution_runs DROP COLUMN wall_budget_millis;
