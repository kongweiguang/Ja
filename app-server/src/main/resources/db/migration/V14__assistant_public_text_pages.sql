-- @author kongweiguang
-- Assistant 正文保留一份可分页的公开文本；完整模型块仍负责 Provider 上下文与审计。
CREATE TABLE assistant_public_text (
    message_id TEXT PRIMARY KEY NOT NULL,
    content TEXT NOT NULL,
    FOREIGN KEY (message_id) REFERENCES messages(message_id) ON DELETE CASCADE
);

-- json_each 按数组序号展开，旧会话在迁移后也由同一分页路径读取，不保留双轨解析。
INSERT INTO assistant_public_text(message_id, content)
SELECT m.message_id,
       COALESCE((SELECT group_concat(json_extract(j.value, '$.text'), '')
                 FROM json_each(m.blocks_json) j
                 WHERE json_extract(j.value, '$.kind') = 'text'), '')
FROM messages m WHERE m.role = 'ASSISTANT';
