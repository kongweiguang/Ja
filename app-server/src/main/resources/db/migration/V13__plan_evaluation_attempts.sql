-- @author kongweiguang
-- A frozen Plan input may need several independently audited model requests. The prior
-- unique key on input_digest turned every transient UNKNOWN into a permanent pause.
CREATE TABLE plan_evaluation_requests_next (
    request_id TEXT PRIMARY KEY NOT NULL,
    plan_id TEXT NOT NULL,
    plan_revision_id TEXT NOT NULL,
    run_id TEXT NOT NULL,
    owner_thread_id TEXT NOT NULL,
    input_digest TEXT NOT NULL CHECK (length(input_digest)=64 AND input_digest NOT GLOB '*[^0-9a-f]*'),
    attempt_ordinal INTEGER NOT NULL CHECK (attempt_ordinal>=1),
    profile_json TEXT NOT NULL CHECK (json_valid(profile_json) AND json_type(profile_json)='object'),
    outcome TEXT NOT NULL CHECK (outcome IN ('RUNNING','SUCCEEDED','FAILED','UNKNOWN')),
    certainty TEXT NOT NULL CHECK (certainty IN ('UNKNOWN','KNOWN')),
    verdict TEXT CHECK (verdict IS NULL OR verdict IN ('MET','NOT_MET','INCONCLUSIVE')),
    criteria_json TEXT CHECK (criteria_json IS NULL OR (json_valid(criteria_json) AND json_type(criteria_json)='array')),
    summary TEXT CHECK (summary IS NULL OR length(summary) BETWEEN 1 AND 4000),
    input_tokens INTEGER CHECK (input_tokens IS NULL OR input_tokens>=0),
    output_tokens INTEGER CHECK (output_tokens IS NULL OR output_tokens>=0),
    total_tokens INTEGER CHECK (total_tokens IS NULL OR total_tokens>=input_tokens+output_tokens),
    started_at TEXT NOT NULL,
    completed_at TEXT,
    FOREIGN KEY (plan_id) REFERENCES plans(plan_id) ON DELETE RESTRICT,
    FOREIGN KEY (plan_revision_id) REFERENCES plan_revisions(plan_revision_id) ON DELETE RESTRICT,
    FOREIGN KEY (run_id) REFERENCES execution_runs(run_id) ON DELETE RESTRICT,
    FOREIGN KEY (owner_thread_id) REFERENCES threads(thread_id) ON DELETE RESTRICT,
    UNIQUE (plan_id,plan_revision_id,run_id,input_digest,attempt_ordinal),
    CHECK ((certainty='UNKNOWN')=(input_tokens IS NULL AND output_tokens IS NULL AND total_tokens IS NULL)),
    CHECK ((certainty='KNOWN')=(input_tokens IS NOT NULL AND output_tokens IS NOT NULL AND total_tokens IS NOT NULL)),
    CHECK ((outcome='RUNNING')=(completed_at IS NULL)),
    CHECK ((outcome='SUCCEEDED')=(verdict IS NOT NULL AND criteria_json IS NOT NULL AND summary IS NOT NULL))
);

INSERT INTO plan_evaluation_requests_next(
    request_id,plan_id,plan_revision_id,run_id,owner_thread_id,input_digest,attempt_ordinal,
    profile_json,outcome,certainty,verdict,criteria_json,summary,input_tokens,output_tokens,
    total_tokens,started_at,completed_at)
SELECT request_id,plan_id,plan_revision_id,run_id,owner_thread_id,input_digest,1,
    profile_json,outcome,certainty,verdict,criteria_json,summary,input_tokens,output_tokens,
    total_tokens,started_at,completed_at
FROM plan_evaluation_requests;

DROP TABLE plan_evaluation_requests;
ALTER TABLE plan_evaluation_requests_next RENAME TO plan_evaluation_requests;
CREATE INDEX idx_plan_evaluation_requests_run ON plan_evaluation_requests(plan_id,run_id,outcome);
CREATE INDEX idx_plan_evaluation_requests_input ON plan_evaluation_requests(
    plan_id,plan_revision_id,run_id,input_digest,attempt_ordinal DESC);
CREATE TRIGGER plan_evaluation_requests_immutable_identity
BEFORE UPDATE OF request_id,plan_id,plan_revision_id,run_id,owner_thread_id,input_digest,
    attempt_ordinal,profile_json,started_at
ON plan_evaluation_requests
BEGIN SELECT RAISE(ABORT,'Plan evaluator request identity is immutable'); END;
