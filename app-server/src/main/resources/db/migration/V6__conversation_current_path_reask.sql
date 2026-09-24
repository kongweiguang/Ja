-- @author kongweiguang
-- Reask keeps the original message and its immutable attachment ownership auditable while
-- allowing the replacement message to reference the same Workspace-scoped attachment IDs.
ALTER TABLE turns ADD COLUMN current_path INTEGER NOT NULL DEFAULT 1 CHECK (current_path IN (0,1));
ALTER TABLE turns ADD COLUMN source_message_id TEXT;

-- USER_CONTINUATION is a normal execution cursor whose only difference is its durable
-- source-message relation; SQLite cannot widen the original CHECK constraint in place.
CREATE TABLE turn_execution_reask (
    turn_id TEXT PRIMARY KEY NOT NULL,
    schema_version INTEGER NOT NULL CHECK (schema_version=1),
    state_json TEXT NOT NULL CHECK (
        json_valid(state_json) AND json_type(state_json)='object'
        AND json_extract(state_json,'$.schemaVersion')=1
        AND json_extract(state_json,'$.common.origin') IN (
            'USER','USER_CONTINUATION','CHILD_TASK','GOAL_CONTINUATION','PLAN_EXECUTION'
        )
        AND (json_extract(state_json,'$.kind')<>'PROVIDER_PENDING'
             OR json_extract(state_json,'$.profile.collaborationMode') IN ('DEFAULT','PLAN'))
    ),
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT
);

INSERT INTO turn_execution_reask(turn_id,schema_version,state_json)
SELECT turn_id,schema_version,state_json FROM turn_execution;

DROP TABLE turn_execution;
ALTER TABLE turn_execution_reask RENAME TO turn_execution;

CREATE INDEX idx_turns_thread_current_path ON turns(thread_id,current_path,turn_sequence);
CREATE INDEX idx_turns_source_current_path
    ON turns(thread_id,source_message_id,current_path,turn_sequence)
    WHERE source_message_id IS NOT NULL;

-- Attachment content is immutable and may be shared by a reasked message. Access remains
-- limited by the reask transaction to IDs already bound to its source user message.
CREATE TABLE message_attachments_reask (
    message_id TEXT NOT NULL,
    attachment_id TEXT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 0 AND 9),
    created_at TEXT NOT NULL,
    PRIMARY KEY (message_id, ordinal),
    FOREIGN KEY (message_id) REFERENCES messages(message_id) ON DELETE RESTRICT,
    FOREIGN KEY (attachment_id) REFERENCES attachments(attachment_id) ON DELETE RESTRICT
);

INSERT INTO message_attachments_reask(message_id,attachment_id,ordinal,created_at)
SELECT message_id,attachment_id,ordinal,created_at FROM message_attachments;

DROP TABLE message_attachments;
ALTER TABLE message_attachments_reask RENAME TO message_attachments;
CREATE INDEX idx_message_attachments_attachment ON message_attachments(attachment_id, message_id);
