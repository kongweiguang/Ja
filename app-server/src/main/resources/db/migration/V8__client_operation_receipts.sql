-- @author kongweiguang
-- Client operation receipts are committed in the same transaction as admission or approval.
-- They retain only request hashes and safe result identities so reconnects cannot repeat effects.
CREATE TABLE client_operations (
    client_operation_id TEXT PRIMARY KEY NOT NULL
        CHECK (length(client_operation_id)=35 AND substr(client_operation_id,1,3)='op_'
            AND substr(client_operation_id,4) NOT GLOB '*[^0-9a-f]*'),
    method TEXT NOT NULL CHECK (method IN ('turn/start','turn/continue','turn/reask','approval/respond')),
    request_fingerprint TEXT NOT NULL
        CHECK (length(request_fingerprint)=64 AND request_fingerprint NOT GLOB '*[^0-9a-f]*'),
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    thread_revision INTEGER NOT NULL CHECK (thread_revision>=0),
    queued INTEGER NOT NULL CHECK (queued IN (0,1)),
    approval_id TEXT,
    decision TEXT CHECK (decision IN ('approve','deny')),
    created_at TEXT NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    CHECK ((method='approval/respond')=(approval_id IS NOT NULL AND decision IS NOT NULL AND queued=0)),
    CHECK (method='approval/respond' OR queued=1)
);

CREATE INDEX idx_client_operations_turn ON client_operations(thread_id,turn_id);
