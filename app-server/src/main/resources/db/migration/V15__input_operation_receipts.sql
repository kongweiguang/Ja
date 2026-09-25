-- @author kongweiguang
-- Queue admission and its retry receipt share one SQLite transaction.
CREATE TABLE input_operation_receipts (
    client_operation_id TEXT PRIMARY KEY NOT NULL
        CHECK (length(client_operation_id)=35 AND substr(client_operation_id,1,3)='op_'
            AND substr(client_operation_id,4) NOT GLOB '*[^0-9a-f]*'),
    request_fingerprint TEXT NOT NULL
        CHECK (length(request_fingerprint)=64 AND request_fingerprint NOT GLOB '*[^0-9a-f]*'),
    thread_id TEXT NOT NULL REFERENCES threads(thread_id) ON DELETE RESTRICT,
    turn_id TEXT NOT NULL REFERENCES turns(turn_id) ON DELETE RESTRICT,
    input_id TEXT NOT NULL,
    input_kind TEXT NOT NULL CHECK (input_kind IN ('STEERING','FOLLOW_UP')),
    created_at TEXT NOT NULL
);

-- Prompt history reads recent committed user text without scanning complete Thread snapshots.
CREATE INDEX idx_input_history_recent ON timeline_messages(created_at DESC,item_id DESC)
    WHERE message_kind='USER_INPUT';
