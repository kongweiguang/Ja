// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;

/// Java 的恢复状态是必需字段，真实快照不得因 DTO 漏字段被转换成运行时不可用。
#[test]
fn interaction_snapshot_retains_resume_state() {
    let value = json!({"threadId":"thr_demo","eventSequence":0,"request":null,"draft":null,"resumeState":"none"});
    let parsed: InteractionSnapshotDto =
        serde_json::from_value(value.clone()).expect("server snapshot");
    assert_eq!(serde_json::to_value(parsed).unwrap(), value);
    let mut invalid = value.clone();
    invalid["resumeState"] = json!("approved");
    assert!(serde_json::from_value::<InteractionSnapshotDto>(invalid).is_err());
    let mut missing = value;
    missing.as_object_mut().unwrap().remove("resumeState");
    assert!(serde_json::from_value::<InteractionSnapshotDto>(missing).is_err());
}

/// 结构化计划接受稳定 DAG，并拒绝循环依赖而不解析 Markdown。
#[test]
fn plan_validation_rejects_dependency_cycles() {
    let mut plan = PlanDefinitionDto {
        objective: "完成独立计划".to_owned(),
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

/// events/read 必须按 Java 公共事件形状反序列化，保留显式 null 游标并拒绝历史别名。
#[test]
fn plan_events_result_is_strict() {
    let fixture = json!({
        "planId": "plan_demo",
        "planRevision": 2,
        "eventSequence": 4,
        "items": [{
            "eventSequence": 4,
            "kind": "plan_execution_started",
            "summary": "计划开始执行",
            "occurredAt": "2026-09-04T00:00:00Z"
        }],
        "nextCursor": null
    });
    assert!(serde_json::from_value::<PlanEventsResultDto>(fixture.clone()).is_ok());
    let mut removed_kind = fixture.clone();
    removed_kind["items"][0]["kind"] = json!("plan_budget_exhausted");
    assert!(serde_json::from_value::<PlanEventsResultDto>(removed_kind).is_err());
    let mut missing_required_null = fixture.clone();
    missing_required_null
        .as_object_mut()
        .unwrap()
        .remove("nextCursor");
    assert!(serde_json::from_value::<PlanEventsResultDto>(missing_required_null).is_err());
    let mut invalid = fixture;
    invalid["items"][0]["unexpected"] = json!("legacy");
    assert!(serde_json::from_value::<PlanEventsResultDto>(invalid).is_err());
}
