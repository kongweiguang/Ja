-- @author kongweiguang

-- V4 只能按顺序猜测遗留 final；仅降级迁移安装时已经存在、因而没有 terminal.finalMessage 身份的行。
UPDATE timeline_messages
SET message_kind = 'ASSISTANT_PROGRESS',
    model_round = COALESCE((SELECT MAX(u.model_round) FROM usage u
                            WHERE u.turn_id=timeline_messages.turn_id),1)
WHERE message_kind = 'FINAL_ANSWER'
  AND julianday(created_at) <= (
      SELECT julianday(installed_on) FROM flyway_schema_history
      WHERE version='4' AND success=1 ORDER BY installed_rank DESC LIMIT 1
  );

-- 历史 Tool output 无法通过当前 secret 集可靠重投影；保留 call/error 配对但删除 raw 正文。
-- 迁移窗口临时移除 immutable update trigger，完成一次性净化后立即恢复同名约束。
DROP TRIGGER messages_immutable_update;
UPDATE messages
SET blocks_json=(
    SELECT json_group_array(json(block_json)) FROM (
        SELECT CASE WHEN json_extract(value,'$.kind')='tool_result'
                    THEN json_object('kind','tool_result','callId',json_extract(value,'$.callId'),
                                     'content','[historical tool output unavailable]',
                                     'error',json_extract(value,'$.error'))
                    ELSE value END AS block_json
        FROM json_each(messages.blocks_json) ORDER BY CAST(key AS INTEGER)
    )
)
WHERE role='TOOL';
CREATE TRIGGER messages_immutable_update BEFORE UPDATE ON messages
BEGIN SELECT RAISE(ABORT, 'messages are immutable'); END;

-- V4 曾把无法确认的遗留 Tool 展示成协议外 unknown；公开状态必须在数据库升级时一次性收敛。
UPDATE tools
SET presentation_json = json_set(
        presentation_json,
        '$.status',
        'error',
        '$.outputPreview',
        COALESCE(json_extract(presentation_json, '$.outputPreview'),
                 'The previous Tool outcome could not be confirmed.'))
WHERE json_extract(presentation_json, '$.status') = 'unknown';
