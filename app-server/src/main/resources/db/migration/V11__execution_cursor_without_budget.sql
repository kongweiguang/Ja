-- @author kongweiguang
-- The request timeout belongs to a live runtime lease, never to a resumable Turn cursor.
-- Keep every other cursor fact intact while removing the two obsolete V1 budget fields.
UPDATE turn_execution
SET state_json = json_remove(state_json, '$.common.deadlineAt', '$.common.activeBudgetMillis')
WHERE json_type(state_json, '$.common.deadlineAt') IS NOT NULL
   OR json_type(state_json, '$.common.activeBudgetMillis') IS NOT NULL;
