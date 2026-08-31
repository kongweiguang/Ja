// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Review Tauri 命令处理器。
//
// Command 只转换 DTO、解析 workspace binding 并调用 `ReviewService`；领域校验、CAS 与事务
// 分别由 domain/application/infrastructure 权威实现。

use super::dto::*;
use super::projection::*;
use crate::app_runtime::RuntimeHost;
use crate::app_runtime::{
    RuntimeCommandError, TurnChangeSetReadInputDto, TurnChangeSetReadResultDto,
};
use crate::review::application::{ReviewError, ReviewService};
use crate::review::compose_service;
use crate::review::domain::{
    ReviewAction, ReviewCatalogLimit, ReviewFileId, ReviewOperationId, ReviewRevision,
    ReviewSource, ReviewTarget,
};
use crate::workspace::WorkspaceHandle;
use tauri::Emitter;
/// 暴露 repository catalog，但不创建第二个 workspace authority。
#[tauri::command]
pub async fn ja_review_catalog(
    input: ReviewCatalogInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ReviewCatalogDto, ReviewCommandError> {
    let workspace_id = input.workspace_id.clone();
    validate_workspace_id(&workspace_id)?;
    let catalog_limit = input
        .max_commits
        .map(ReviewCatalogLimit::parse)
        .transpose()
        .map_err(|_| ReviewCommandError::invalid_input())?;
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let operation = ReviewService::operation(None).map_err(ReviewCommandError::from_review)?;
        let cancellation = operation.cancellation();
        with_workspace(&host, &workspace_id, |workspace| {
            let service = review_service(&host, &workspace_id, workspace)?;
            let catalog = match catalog_limit {
                Some(limit) => service.catalog_with_limit(limit, &cancellation)?,
                None => service.catalog(&cancellation)?,
            };
            Ok(project_catalog(&workspace_id, workspace, catalog))
        })
    })
    .await
    .map_err(|_| ReviewCommandError {
        code: ReviewErrorCodeDto::IoError,
    })?
}

/// 向 WebView 暴露一个权威 source snapshot。
#[tauri::command]
pub async fn ja_review_snapshot(
    input: ReviewSnapshotInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ReviewSnapshotDto, ReviewCommandError> {
    let workspace_id = input.workspace_id.clone();
    validate_workspace_id(&workspace_id)?;
    let source = ReviewSource::try_from(input.source)?;
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let operation = ReviewService::operation(None).map_err(ReviewCommandError::from_review)?;
        let cancellation = operation.cancellation();
        with_workspace(&host, &workspace_id, |workspace| {
            let service = review_service(&host, &workspace_id, workspace)?;
            let snapshot = service.snapshot(source, &cancellation)?;
            Ok(project_snapshot(&workspace_id, snapshot))
        })
    })
    .await
    .map_err(|_| ReviewCommandError {
        code: ReviewErrorCodeDto::IoError,
    })?
}

/// 按 opaque file id 与 revision 加载一个有界 file diff。
#[tauri::command]
pub async fn ja_review_file_diff(
    input: ReviewFileDiffInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ReviewFileDiffDto, ReviewCommandError> {
    let workspace_id = input.workspace_id.clone();
    validate_workspace_id(&workspace_id)?;
    let source = ReviewSource::try_from(input.source)?;
    let revision =
        ReviewRevision::parse(input.revision).map_err(|_| ReviewCommandError::invalid_input())?;
    let file_id =
        ReviewFileId::parse(input.file_id).map_err(|_| ReviewCommandError::invalid_input())?;
    let host = state.inner().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let operation = ReviewService::operation(None).map_err(ReviewCommandError::from_review)?;
        let cancellation = operation.cancellation();
        with_workspace(&host, &workspace_id, |workspace| {
            let service = review_service(&host, &workspace_id, workspace)?;
            let diff = service.file_diff(source, &revision, &file_id, &cancellation)?;
            Ok(project_file_diff(&workspace_id, diff))
        })
    })
    .await
    .map_err(|_| ReviewCommandError {
        code: ReviewErrorCodeDto::IoError,
    })?
}

/// 通过 application service 执行 native-owned all/file/hunk mutation，并支持 operation cancel。
#[tauri::command]
pub async fn ja_review_apply<R: tauri::Runtime>(
    input: ReviewApplyInput,
    app: tauri::AppHandle<R>,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<ReviewApplyResultDto, ReviewCommandError> {
    let workspace_id = input.workspace_id.clone();
    validate_workspace_id(&workspace_id)?;
    let source = ReviewSource::try_from(input.source)?;
    let revision =
        ReviewRevision::parse(input.revision).map_err(|_| ReviewCommandError::invalid_input())?;
    let action = ReviewAction::from(input.action);
    let target = ReviewTarget::try_from(input.target)?;
    let operation_id = ReviewOperationId::parse(input.operation_id)
        .map_err(|_| ReviewCommandError::invalid_input())?;
    let generation = state
        .state()
        .map_err(|_| ReviewCommandError {
            code: ReviewErrorCodeDto::IoError,
        })?
        .generation
        .max(1);
    let host = state.inner().clone();
    let app = app.clone();
    tauri::async_runtime::spawn_blocking(move || {
        let operation = ReviewService::operation(Some(operation_id.clone()))
            .map_err(ReviewCommandError::from_review)?;
        let cancellation = operation.cancellation();
        let result = with_workspace(&host, &workspace_id, |workspace| {
            let service = review_service(&host, &workspace_id, workspace)?;
            let applied = service.apply(source, &revision, action, target, &cancellation)?;
            Ok(project_apply_result(
                &workspace_id,
                operation_id.as_str(),
                applied,
            ))
        });
        drop(operation);
        if result.is_ok() {
            let event = ReviewInvalidatedEventDto {
                workspace_id: workspace_id.clone(),
                generation,
                reason: ReviewInvalidatedReason::Mutation,
            };
            // event 只是 refetch hint；投递失败不能把已经 commit 的 Git mutation 反报为失败。
            if app.emit(JA_REVIEW_INVALIDATED_EVENT, event).is_err() {
                tracing::warn!("Review invalidation hint could not be delivered");
            }
        }
        result
    })
    .await
    .map_err(|_| ReviewCommandError {
        code: ReviewErrorCodeDto::IoError,
    })?
}

/// 取消 in-flight operation；native service state 仍保持权威。
#[tauri::command]
pub fn ja_review_cancel(
    input: ReviewCancelInput,
    _state: tauri::State<'_, RuntimeHost>,
) -> Result<ReviewCancelResultDto, ReviewCommandError> {
    validate_workspace_id(&input.workspace_id)?;
    let operation_id = ReviewOperationId::parse(input.operation_id)
        .map_err(|_| ReviewCommandError::invalid_input())?;
    Ok(ReviewCancelResultDto {
        workspace_id: input.workspace_id,
        operation_id: operation_id.as_str().to_owned(),
        cancelled: ReviewService::cancel_operation(&operation_id)
            .map_err(ReviewCommandError::from_review)?,
    })
}

/// 读取本 Turn 已由 Java 持久化的 frozen diff；绝不重新查询当前 Git worktree。
#[tauri::command]
pub async fn ja_turn_change_set_read(
    input: TurnChangeSetReadInputDto,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<TurnChangeSetReadResultDto, RuntimeCommandError> {
    let host = state.inner().clone();
    let (workspace_id, input) = input.into_domain();
    tauri::async_runtime::spawn_blocking(move || {
        host.turn_change_set_read(&workspace_id, input)
            .map(Into::into)
    })
    .await
    .map_err(|_| RuntimeCommandError::unavailable())?
}

/// 校验协议 workspace id，不暴露 internal UUID syntax。
fn validate_workspace_id(value: &str) -> Result<(), ReviewCommandError> {
    if value.len() <= 3
        || value.len() > 99
        || !value.starts_with("ws_")
        || !value[3..]
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-' | b'.'))
    {
        return Err(ReviewCommandError::invalid_input());
    }
    Ok(())
}

/// 在 RuntimeHost 的 configured workspace binding 内执行一次 service operation。
fn with_workspace<T>(
    state: &RuntimeHost,
    workspace_id: &str,
    operation: impl FnOnce(&WorkspaceHandle) -> Result<T, ReviewError>,
) -> Result<T, ReviewCommandError> {
    state
        .with_configured_workspace(workspace_id, operation)
        .map_err(ReviewCommandError::from_lookup)?
        .map_err(ReviewCommandError::from_review)
}

/// 在 interface composition 边界注入唯一 native adapter；路径队列由 workspace 基础设施共享。
fn review_service(
    _state: &RuntimeHost,
    _workspace_id: &str,
    workspace: &WorkspaceHandle,
) -> Result<ReviewService, ReviewError> {
    compose_service(workspace.clone())
}
