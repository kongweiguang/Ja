-- @author kongweiguang
-- Remove the V1 task-count checks while preserving message, usage and FK facts.
CREATE TABLE timeline_messages_v10 (
    item_id TEXT PRIMARY KEY NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    message_kind TEXT NOT NULL CHECK (message_kind IN (
        'USER_INPUT','THREAD_MESSAGE','ASSISTANT_PROGRESS','REASONING_SUMMARY','FINAL_ANSWER'
    )),
    public_text TEXT NOT NULL,
    model_round INTEGER CHECK (model_round >= 1),
    created_at TEXT NOT NULL,
    source_thread_id TEXT,
    source_title TEXT,
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    CHECK ((message_kind IN ('ASSISTANT_PROGRESS','REASONING_SUMMARY')) = (model_round IS NOT NULL)),
    CHECK ((message_kind='THREAD_MESSAGE') = (source_thread_id IS NOT NULL AND source_title IS NOT NULL)),
    CHECK (message_kind!='THREAD_MESSAGE' OR length(source_title) BETWEEN 1 AND 512)
);

INSERT INTO timeline_messages_v10
    (item_id,thread_id,turn_id,message_kind,public_text,model_round,created_at,source_thread_id,source_title)
SELECT item_id,thread_id,turn_id,message_kind,public_text,model_round,created_at,source_thread_id,source_title
FROM timeline_messages;
DROP TABLE timeline_messages;
ALTER TABLE timeline_messages_v10 RENAME TO timeline_messages;
CREATE INDEX idx_timeline_messages_thread_created
    ON timeline_messages(thread_id,created_at,item_id);

CREATE TABLE usage_v10 (
    usage_id TEXT PRIMARY KEY NOT NULL,
    request_id TEXT UNIQUE NOT NULL,
    thread_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    model_round INTEGER NOT NULL CHECK (model_round >= 1),
    request_ordinal INTEGER NOT NULL CHECK (request_ordinal >= 1),
    purpose TEXT NOT NULL CHECK (purpose IN ('ASSISTANT','SUMMARY')),
    certainty TEXT NOT NULL CHECK (certainty IN ('KNOWN','UNKNOWN')),
    profile_json TEXT NOT NULL CHECK (json_valid(profile_json) AND json_type(profile_json)='object'),
    input_tokens INTEGER CHECK (input_tokens IS NULL OR input_tokens >= 0),
    output_tokens INTEGER CHECK (output_tokens IS NULL OR output_tokens >= 0),
    total_tokens INTEGER CHECK (total_tokens IS NULL OR total_tokens >= 0),
    created_at TEXT NOT NULL,
    cache_read_tokens INTEGER CHECK (cache_read_tokens IS NULL OR cache_read_tokens >= 0),
    cache_write_tokens INTEGER CHECK (cache_write_tokens IS NULL OR cache_write_tokens >= 0),
    new_input_tokens INTEGER CHECK (new_input_tokens IS NULL OR new_input_tokens >= 0),
    input_accounting TEXT NOT NULL DEFAULT 'UNKNOWN'
      CHECK (input_accounting IN ('INPUT_EXCLUDES_CACHE','INPUT_INCLUDES_CACHE','UNKNOWN')),
    FOREIGN KEY (thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    FOREIGN KEY (turn_id) REFERENCES turns(turn_id) ON DELETE RESTRICT,
    UNIQUE (turn_id,request_ordinal),
    CHECK ((certainty='UNKNOWN' AND input_tokens IS NULL AND output_tokens IS NULL AND total_tokens IS NULL)
        OR (certainty='KNOWN' AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL
            AND total_tokens IS NOT NULL AND total_tokens >= input_tokens + output_tokens))
);

INSERT INTO usage_v10
    (usage_id,request_id,thread_id,turn_id,model_round,request_ordinal,purpose,certainty,profile_json,
     input_tokens,output_tokens,total_tokens,created_at,cache_read_tokens,cache_write_tokens,
     new_input_tokens,input_accounting)
SELECT usage_id,request_id,thread_id,turn_id,model_round,request_ordinal,purpose,certainty,profile_json,
       input_tokens,output_tokens,total_tokens,created_at,cache_read_tokens,cache_write_tokens,
       new_input_tokens,input_accounting
FROM usage;
DROP TABLE usage;
ALTER TABLE usage_v10 RENAME TO usage;

CREATE TRIGGER usage_unknown_only_insert BEFORE INSERT ON usage
WHEN NEW.certainty<>'UNKNOWN'
BEGIN SELECT RAISE(ABORT,'usage request must start as UNKNOWN'); END;

CREATE TRIGGER usage_settlement_only_update BEFORE UPDATE ON usage
WHEN NOT (
    OLD.certainty='UNKNOWN'
    AND NEW.certainty='KNOWN'
    AND NEW.usage_id=OLD.usage_id AND NEW.request_id=OLD.request_id
    AND NEW.thread_id=OLD.thread_id AND NEW.turn_id=OLD.turn_id
    AND NEW.model_round=OLD.model_round AND NEW.request_ordinal=OLD.request_ordinal
    AND NEW.purpose=OLD.purpose
    AND NEW.profile_json=OLD.profile_json AND NEW.created_at>=OLD.created_at
    AND NEW.input_tokens IS NOT NULL AND NEW.output_tokens IS NOT NULL
    AND NEW.total_tokens>=NEW.input_tokens+NEW.output_tokens
)
BEGIN SELECT RAISE(ABORT,'usage update is not a request settlement'); END;

CREATE TRIGGER usage_immutable_delete BEFORE DELETE ON usage
WHEN NOT EXISTS(
    SELECT 1 FROM temporary_side_chats c
    WHERE c.thread_id=OLD.thread_id AND c.state='CLOSING'
)
BEGIN SELECT RAISE(ABORT,'usage is immutable'); END;
