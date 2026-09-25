// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;

/// Java 的恢复状态是必需字段，真实快照不得因 DTO 漏字段被转换成运行时不可用。
#[test]
fn interaction_snapshot_retains_resume_state() {
    let value = json!({"threadId":"thr_demo","eventSequence":0,"request":null,"draft":null,"resumeState":"none"});
    let parsed: InteractionSnapshotDto = serde_json::from_value(value.clone()).expect("server snapshot");
    assert_eq!(serde_json::to_value(parsed).unwrap(), value);
    let mut invalid = value.clone();
    invalid["resumeState"] = json!("approved");
    assert!(serde_json::from_value::<InteractionSnapshotDto>(invalid).is_err());
    let mut missing = value;
    missing.as_object_mut().unwrap().remove("resumeState");
    assert!(serde_json::from_value::<InteractionSnapshotDto>(missing).is_err());
}

/// mutation DTO 必须拒绝旧 revision 别名与额外控制字段，防止绕过 Goal CAS。
#[test]
fn goal_mutation_input_is_closed() {
    let valid: GoalMutationInput = serde_json::from_value(json!({
        "goalId": "goal_demo",
        "expectedGoalRevision": 3,
        "idempotencyKey": "goal.pause:3"
    }))
    .expect("valid mutation");
    assert_eq!(valid.expected_goal_revision, 3);
    assert!(
        serde_json::from_value::<GoalMutationInput>(json!({
            "goalId": "goal_demo",
            "revision": 3,
            "idempotencyKey": "goal.pause:3"
        }))
        .is_err()
    );
}

/// 结构化计划接受稳定 DAG，并拒绝循环依赖而不解析 Markdown。
#[test]
fn plan_validation_rejects_dependency_cycles() {
    let mut plan = PlanDefinitionDto {
        objective: "完成 Goal".to_owned(),
        scope: vec!["src".to_owned()],
        non_goals: vec![],
        constraints: vec![],
        acceptance_criteria: vec![AcceptanceCriterionDto {
            criterion_id: "criterion_done".to_owned(),
            description: "测试通过".to_owned(),
            required: true,
        }],
        steps: vec![
            PlanStepDto {
                step_id: "step_a".to_owned(),
                title: "A".to_owned(),
                description: "A".to_owned(),
                required: true,
                depends_on: vec![],
            },
            PlanStepDto {
                step_id: "step_b".to_owned(),
                title: "B".to_owned(),
                description: "B".to_owned(),
                required: true,
                depends_on: vec!["step_a".to_owned()],
            },
        ],
        dependencies: vec![],
        risks: vec![],
        verification_strategy: vec!["运行测试".to_owned()],
    };
    assert!(validate_plan(&plan).is_ok());
    plan.steps[0].depends_on = vec!["step_b".to_owned()];
    assert_eq!(validate_plan(&plan).unwrap_err().code, "INVALID_PARAMS");
}

/// 响应 DTO 对 Goal 状态使用闭集枚举，并拒绝未知顶层字段。
#[test]
fn goal_projection_result_is_strict() {
    let fixture = json!({
        "goal": {
            "goalId":"goal_demo","owner":{"kind":"thread","threadId":"thr_demo"},
            "objective":"完成","goalDefinitionRevision":1,"acceptanceCriteria":[],
            "status":"active","phase":"working","revision":1,"planLink":null,
            "currentRunId":"run_demo","currentStepId":null,
            "completedRequiredSteps":0,"totalRequiredSteps":0,
            "attentionReason":null,"latestEvaluation":null,"createdAt":"2026-09-04T00:00:00Z",
            "updatedAt":"2026-09-04T00:00:00Z","achievedAt":null,"stoppedAt":null
        },
        "eventSequence":1
    });
    assert!(serde_json::from_value::<GoalProjectionResultDto>(fixture.clone()).is_ok());
    let mut missing_required_null = fixture.clone();
    missing_required_null["goal"]
        .as_object_mut()
        .unwrap()
        .remove("planLink");
    assert!(serde_json::from_value::<GoalProjectionResultDto>(missing_required_null).is_err());
    let mut invalid = fixture;
    invalid["localState"] = json!(true);
    assert!(serde_json::from_value::<GoalProjectionResultDto>(invalid).is_err());
}

/// Goal 历史 kind 必须与 schema 闭集一致，未知服务端值不能静默进入 UI。
#[test]
fn goal_event_kind_is_closed() {
    let valid = json!({
        "eventSequence": 1,
        "kind": "recovery_required",
        "summary": "需要人工恢复",
        "occurredAt": "2026-09-04T00:00:00Z"
    });
    assert!(serde_json::from_value::<GoalEventItemDto>(valid.clone()).is_ok());
    let mut approval = valid.clone();
    approval["kind"] = json!("tool_approval_requested");
    assert!(serde_json::from_value::<GoalEventItemDto>(approval).is_ok());
    let mut evidence = valid.clone();
    evidence["kind"] = json!("evidence_added");
    assert!(serde_json::from_value::<GoalEventItemDto>(evidence).is_ok());
    let mut no_progress = valid.clone();
    no_progress["kind"] = json!("continuation_no_progress");
    assert!(serde_json::from_value::<GoalEventItemDto>(no_progress).is_ok());
    let mut invalid = valid;
    invalid["kind"] = json!("failed");
    assert!(serde_json::from_value::<GoalEventItemDto>(invalid).is_err());
}
