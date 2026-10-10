// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! CLI 全局模型设置面板；配置仍由 App Server 持有，界面只产生最小编辑意图。

use std::collections::{BTreeMap, BTreeSet};

use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use ratatui::Frame;
use ratatui::layout::{Constraint, Direction, Layout, Position, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span, Text};
use ratatui::widgets::{Block, Borders, Clear, Paragraph, Wrap};
use serde_json::{Map, Value, json};
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;
use uuid::Uuid;

const API_SPECS: [&str; 3] = [
    "openai_responses",
    "anthropic_messages",
    "openai_chat_completions",
];
const REASONING_LEVELS: [&str; 7] = ["off", "minimal", "low", "medium", "high", "xhigh", "max"];
const DEFAULT_CONTEXT_WINDOW: u64 = 128_000;
const DEFAULT_MAX_OUTPUT: u64 = 16_384;
const DEFAULT_CONNECT_TIMEOUT_MS: u64 = 10_000;
const DEFAULT_REQUEST_TIMEOUT_MS: u64 = 300_000;
const MAX_FIELD_GRAPHEMES: usize = 8_192;
const MAX_TEXT_LENGTH: usize = 512;

/// UI 到 controller 的配置动作；ID 仅用于相关请求和忽略迟到结果。
#[derive(Clone, Eq, PartialEq)]
pub enum ModelSettingsRequest {
    Read {
        operation_id: String,
    },
    Save {
        operation_id: String,
        expected_version: String,
        expected_credential_version: String,
        patch: Value,
        credential: Option<ModelSettingsCredential>,
    },
    Verify {
        operation_id: String,
    },
    Test {
        operation_id: String,
        provider_id: String,
        model_id: String,
    },
    Repair {
        operation_id: String,
        expected_version: String,
        document: Value,
    },
}

impl std::fmt::Debug for ModelSettingsRequest {
    /// 序列化或诊断请求时让凭据走自定义脱敏格式，避免 Debug 暴露 API Key。
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Read { operation_id } => formatter
                .debug_struct("Read")
                .field("operation_id", operation_id)
                .finish(),
            Self::Save {
                operation_id,
                expected_version,
                expected_credential_version,
                patch,
                credential,
            } => formatter
                .debug_struct("Save")
                .field("operation_id", operation_id)
                .field("expected_version", expected_version)
                .field("expected_credential_version", expected_credential_version)
                .field("patch", patch)
                .field("credential", credential)
                .finish(),
            Self::Verify { operation_id } => formatter
                .debug_struct("Verify")
                .field("operation_id", operation_id)
                .finish(),
            Self::Test {
                operation_id,
                provider_id,
                model_id,
            } => formatter
                .debug_struct("Test")
                .field("operation_id", operation_id)
                .field("provider_id", provider_id)
                .field("model_id", model_id)
                .finish(),
            Self::Repair {
                operation_id,
                expected_version,
                document,
            } => formatter
                .debug_struct("Repair")
                .field("operation_id", operation_id)
                .field("expected_version", expected_version)
                .field("document", document)
                .finish(),
        }
    }
}

/// 单个 Provider 凭据的写入载体；Secret 只在当前进程内暂存。
#[derive(Clone, Eq, PartialEq)]
pub struct ModelSettingsCredential {
    pub provider_id: String,
    pub credential_id: String,
    pub secret: String,
}

impl std::fmt::Debug for ModelSettingsCredential {
    /// 防止错误诊断、断言失败或调试输出打印明文密钥。
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("ModelSettingsCredential")
            .field("provider_id", &self.provider_id)
            .field("credential_id", &self.credential_id)
            .field("secret", &"[REDACTED]")
            .finish()
    }
}

impl Drop for ModelSettingsCredential {
    /// 请求对象离开队列时清空凭据缓冲，缩短 API Key 在进程内存中的驻留时间。
    fn drop(&mut self) {
        wipe_secret(&mut self.secret);
    }
}

/// App Server 的一次配置操作结果；configuration 保留权威 CAS 与脱敏凭据投影。
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ModelSettingsOutcome {
    Loaded {
        configuration: Value,
    },
    Saved {
        configuration: Value,
    },
    NeedsCredential {
        configuration: Value,
        message: String,
    },
    Uncertain {
        configuration: Option<Value>,
        message: String,
    },
    Conflict {
        configuration: Option<Value>,
        message: String,
    },
    Failed {
        message: String,
    },
    Tested {
        response_model: String,
        latency_ms: u64,
    },
}

/// 每个异步结果必须匹配当前 pending ID 才能改变可见草稿。
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ModelSettingsResult {
    pub operation_id: String,
    pub outcome: ModelSettingsOutcome,
}

/// 面板向 reducer 返回的键盘结果，不直接触碰终端、RPC 或会话生命周期。
pub(super) enum ModelSettingsEffect {
    None,
    Request(ModelSettingsRequest),
    Close { start_conversation: bool },
    Trust { trusted: bool },
}

/// 可编辑字段的展示行；Secret 的实际内容不会被提供给 renderer。
pub(super) struct ModelSettingsFieldView {
    pub label: String,
    pub value: String,
    pub selected: bool,
    pub secret: bool,
    pub cursor: Option<usize>,
}

/// 设置面板渲染所需的只读投影，不包含凭据明文。
pub(super) struct ModelSettingsView {
    pub title: String,
    pub subtitle: String,
    pub rows: Vec<(String, String, bool)>,
    pub fields: Vec<ModelSettingsFieldView>,
    pub selected: usize,
    pub scroll: usize,
    pub status: Option<String>,
    pub error: Option<String>,
    pub footer: String,
    pub form: bool,
    pub wrap_rows: bool,
    pub loading: bool,
}

struct FormValidationError {
    field_key: &'static str,
    message: String,
}

#[derive(Clone)]
struct ProviderModelRef {
    provider_id: String,
    model_id: String,
}

#[derive(Clone)]
enum SettingsRowTarget {
    Provider(String),
    Model(ProviderModelRef),
}

struct SettingsForm {
    provider_id: String,
    credential_id: String,
    model_id: Option<String>,
    target: FormTarget,
    fields: Vec<FormField>,
    focus: usize,
    cursor: usize,
    advanced: bool,
    make_default: bool,
    initial_default: bool,
    initial_fields: Vec<String>,
}

#[derive(Clone, Copy, Eq, PartialEq)]
enum FormTarget {
    Provider,
    Model,
    NewProvider,
    NewModel,
}

struct FormField {
    key: &'static str,
    label: &'static str,
    value: String,
    kind: FieldKind,
    advanced: bool,
}

#[derive(Clone, Copy)]
enum FieldKind {
    Text,
    Secret,
    Number,
    Protocol,
    Boolean,
    ReasoningMap,
    ReasoningDefault,
}

enum SettingsMode {
    List,
    Form(SettingsForm),
    LeaveConfirm {
        selected: usize,
        form: Option<SettingsForm>,
        start_conversation: bool,
    },
    DeleteConfirm {
        target: SettingsRowTarget,
        selected: usize,
        default_replacements: Vec<ProviderModelRef>,
        clears_default: bool,
        clears_subagents: bool,
    },
    TestConfirm {
        provider_id: String,
        model_id: String,
        selected: usize,
    },
    RepairConfirm {
        selected: usize,
    },
    TrustConfirm {
        cwd: String,
        selected: usize,
        return_to_settings: bool,
    },
}

/// 局部 UI 状态；主会话 Composer 由 UiState 原样持有，不复制或重新设置文本。
pub(super) struct ModelSettingsState {
    standalone: bool,
    pending_operation_id: Option<String>,
    pending_kind: Option<PendingKind>,
    server_configuration: Option<Value>,
    baseline: Option<Value>,
    draft: Option<Value>,
    mode: SettingsMode,
    selected: usize,
    scroll: usize,
    status: Option<String>,
    error: Option<String>,
}

#[derive(Clone, Copy, Eq, PartialEq)]
enum PendingKind {
    Read,
    Save,
    Verify,
    Test,
    Repair,
}

impl ModelSettingsState {
    /// Open 事件携带 controller 已立即发出的读取 ID，UI 只等待与之匹配的权威快照。
    pub(super) fn open(operation_id: String, standalone: bool) -> Self {
        Self {
            standalone,
            pending_operation_id: Some(operation_id),
            pending_kind: Some(PendingKind::Read),
            server_configuration: None,
            baseline: None,
            draft: None,
            mode: SettingsMode::List,
            selected: 0,
            scroll: 0,
            status: Some("正在读取全局模型配置…".to_owned()),
            error: None,
        }
    }

    /// Trust 确认可能出现在无会话启动路径，因此单独建面板也能返回历史而不改 Workspace。
    pub(super) fn trust_required(cwd: String, return_to_settings: bool) -> Self {
        Self {
            standalone: !return_to_settings,
            pending_operation_id: None,
            pending_kind: None,
            server_configuration: None,
            baseline: None,
            draft: None,
            mode: SettingsMode::TrustConfirm {
                cwd,
                selected: 0,
                return_to_settings,
            },
            selected: 0,
            scroll: 0,
            status: None,
            error: None,
        }
    }

    /// 返回是否接纳当前操作，让外层仅在匹配的保存完成后返回模型选择，迟到结果不能关闭新草稿。
    pub(super) fn apply_result(&mut self, result: ModelSettingsResult) -> bool {
        if self.pending_operation_id.as_deref() != Some(result.operation_id.as_str()) {
            return false;
        }
        self.pending_operation_id = None;
        self.pending_kind = None;
        self.error = None;
        let preserve_draft = self.is_dirty();
        match result.outcome {
            ModelSettingsOutcome::Loaded { configuration } => {
                self.accept_configuration(configuration, preserve_draft);
                self.status = Some(if preserve_draft {
                    "已读取最新配置；本地草稿保留，请检查后保存".to_owned()
                } else {
                    "配置已从 App Server 刷新".to_owned()
                });
            }
            ModelSettingsOutcome::Saved { configuration } => {
                self.clear_form_secret();
                self.accept_configuration(configuration, false);
                self.mode = SettingsMode::List;
                self.status = Some("模型配置已保存并核实".to_owned());
            }
            ModelSettingsOutcome::NeedsCredential {
                configuration,
                message,
            } => {
                self.accept_configuration(configuration, true);
                self.status = Some(format!(
                    "配置已保存，密钥待补齐：{}",
                    self.safe_message(&message)
                ));
                // 保留当前表单中的 Secret 供原样补写；只在 UI 内存中，不进入行或诊断。
            }
            ModelSettingsOutcome::Uncertain {
                configuration,
                message,
            } => {
                if let Some(configuration) = configuration {
                    self.accept_configuration(configuration, true);
                }
                self.status = Some(format!(
                    "{} · F5 核实，不会重复写入",
                    self.safe_message(&message)
                ));
                self.pending_kind = Some(PendingKind::Verify);
            }
            ModelSettingsOutcome::Conflict {
                configuration,
                message,
            } => {
                if let Some(configuration) = configuration {
                    self.accept_configuration(configuration, true);
                }
                self.status = Some(format!(
                    "配置已变化；草稿保留，请检查后重新保存：{}",
                    self.safe_message(&message)
                ));
            }
            ModelSettingsOutcome::Failed { message } => {
                self.error = Some(self.safe_message(&message));
            }
            ModelSettingsOutcome::Tested {
                response_model,
                latency_ms,
            } => {
                self.mode = SettingsMode::List;
                self.status = Some(format!(
                    "连接成功 · {} · {} ms",
                    safe_label(&response_model),
                    latency_ms
                ));
            }
        }
        true
    }

    /// 把权威读取解包为用户层基线；刷新时只换基线，已有本地草稿仍保持原样。
    fn accept_configuration(&mut self, configuration: Value, preserve_draft: bool) {
        let mut document = user_document(&configuration).unwrap_or_else(default_document);
        let local_patch = if preserve_draft {
            self.baseline
                .as_ref()
                .zip(self.draft.as_ref())
                .map(|(baseline, draft)| configuration_patch(baseline, draft))
        } else {
            None
        };
        self.server_configuration = Some(configuration);
        self.baseline = Some(document.clone());
        if let Some(patch) = local_patch.filter(|patch| patch != &json!({})) {
            apply_user_patch(&mut document, &patch);
        }
        self.draft = Some(document);
        if !preserve_draft {
            self.mode = SettingsMode::List;
        }
    }

    /// F5 核实凭据事务使用 Verify；普通浏览使用 Read，均不隐式重复写或发起模型请求。
    pub(super) fn refresh(&mut self) -> ModelSettingsEffect {
        if self.pending_operation_id.is_some() {
            self.status = Some("操作仍在处理中，请稍候".to_owned());
            return ModelSettingsEffect::None;
        }
        if self.pending_kind == Some(PendingKind::Verify) {
            return self.request(
                PendingKind::Verify,
                ModelSettingsRequest::Verify {
                    operation_id: String::new(),
                },
            );
        }
        self.request(
            PendingKind::Read,
            ModelSettingsRequest::Read {
                operation_id: String::new(),
            },
        )
    }

    /// 把键盘输入限制在设置面板内；进行中的写入只接受 F5 核实或终端级退出键。
    pub(super) fn handle_key(&mut self, key: KeyEvent, has_thread: bool) -> ModelSettingsEffect {
        if key.kind != crossterm::event::KeyEventKind::Press {
            return ModelSettingsEffect::None;
        }
        if key.code == KeyCode::F(5) {
            return self.refresh();
        }
        if let SettingsMode::TrustConfirm {
            cwd,
            selected,
            return_to_settings,
        } = &mut self.mode
        {
            return trust_key(key, cwd, selected, *return_to_settings);
        }
        let in_leave_confirmation = matches!(self.mode, SettingsMode::LeaveConfirm { .. });
        if self.pending_operation_id.is_some() && !in_leave_confirmation {
            self.status = Some("操作仍在处理中；字段已锁定".to_owned());
            return ModelSettingsEffect::None;
        }
        if self.verification_pending() && !in_leave_confirmation {
            if key.code == KeyCode::Esc {
                return self.confirm_abandon_unverified();
            }
            self.status = Some("写入结果待核实；按 F5 核实，Esc 确认放弃".to_owned());
            return ModelSettingsEffect::None;
        }
        let mode = std::mem::replace(&mut self.mode, SettingsMode::List);
        match mode {
            SettingsMode::List => self.list_key(key, has_thread),
            SettingsMode::Form(form) => self.form_key(key, form),
            SettingsMode::LeaveConfirm {
                mut selected,
                form,
                start_conversation,
            } => match confirmation_key(key, &mut selected) {
                ConfirmAction::Stay => {
                    self.mode = SettingsMode::LeaveConfirm {
                        selected,
                        form,
                        start_conversation,
                    };
                    ModelSettingsEffect::None
                }
                ConfirmAction::Cancel => {
                    self.mode = form.map(SettingsMode::Form).unwrap_or(SettingsMode::List);
                    ModelSettingsEffect::None
                }
                ConfirmAction::Confirm => {
                    self.draft = self.baseline.clone();
                    if let Some(mut form) = form {
                        clear_form_secret(&mut form);
                    }
                    ModelSettingsEffect::Close { start_conversation }
                }
            },
            SettingsMode::DeleteConfirm {
                target,
                mut selected,
                default_replacements,
                clears_default,
                clears_subagents,
            } => self.delete_key(
                key,
                target,
                &mut selected,
                default_replacements,
                clears_default,
                clears_subagents,
            ),
            SettingsMode::TestConfirm {
                provider_id,
                model_id,
                mut selected,
            } => self.test_confirm_key(key, provider_id, model_id, &mut selected),
            SettingsMode::RepairConfirm { mut selected } => {
                self.repair_confirm_key(key, &mut selected)
            }
            SettingsMode::TrustConfirm {
                cwd,
                mut selected,
                return_to_settings,
            } => {
                let effect = trust_key(key, &cwd, &mut selected, return_to_settings);
                if matches!(effect, ModelSettingsEffect::None) {
                    self.mode = SettingsMode::TrustConfirm {
                        cwd,
                        selected,
                        return_to_settings,
                    };
                } else if matches!(effect, ModelSettingsEffect::Trust { trusted: false })
                    && return_to_settings
                {
                    self.mode = SettingsMode::List;
                    self.status = Some("工作区未获信任；设置与历史保持可用".to_owned());
                }
                effect
            }
        }
    }

    /// Paste 仅进入显式聚焦的设置字段；密码粘贴不会经过 Composer 的消息或 scrollback。
    pub(super) fn handle_paste(&mut self, text: &str) {
        if self.pending_operation_id.is_some()
            || self.verification_pending()
            || self.storage_unavailable()
        {
            return;
        }
        if let SettingsMode::Form(form) = &mut self.mode
            && let Some(field) = form.fields.get_mut(form.focus)
            && matches!(
                field.kind,
                FieldKind::Secret
                    | FieldKind::Text
                    | FieldKind::Number
                    | FieldKind::ReasoningMap
                    | FieldKind::ReasoningDefault
            )
        {
            let current = field.value.graphemes(true).count();
            let inserted = text.graphemes(true).count();
            if current.saturating_add(inserted) <= MAX_FIELD_GRAPHEMES
                && !text.chars().any(char::is_control)
            {
                insert_at_cursor(&mut field.value, &mut form.cursor, text);
                self.status = None;
                self.error = None;
            } else {
                self.error = Some("输入过长或含控制字符，当前字段未改变".to_owned());
            }
        }
    }

    /// Controller 告知 Workspace 尚未信任；确认视图明确展示对象与影响，不在 UI 绕过服务端授权。
    pub(super) fn require_trust(&mut self, cwd: String) {
        let return_to_settings = self.baseline.is_some();
        self.mode = SettingsMode::TrustConfirm {
            cwd,
            selected: 0,
            return_to_settings,
        };
        self.status = None;
        self.error = None;
    }

    /// 返回 renderer 所需安全投影，密钥内容始终以固定掩码显示。
    pub(super) fn view(&self) -> ModelSettingsView {
        match &self.mode {
            SettingsMode::TrustConfirm { cwd, selected, .. } => ModelSettingsView {
                title: "开始对话前确认工作区".to_owned(),
                subtitle: format!("工作区：{}", safe_label(cwd)),
                rows: vec![
                    (
                        "信任此工作区并继续".to_owned(),
                        "允许使用该项目的本地配置与能力".to_owned(),
                        *selected == 0,
                    ),
                    (
                        "保持未信任并返回".to_owned(),
                        "不修改信任状态，回到设置或历史".to_owned(),
                        *selected == 1,
                    ),
                ],
                fields: Vec::new(),
                selected: *selected,
                scroll: 0,
                status: self.status.clone(),
                error: self.error.clone(),
                footer: "↑/↓ 选择 · Enter 确认 · Esc 返回未信任状态\nCtrl+D 退出".to_owned(),
                form: false,
                wrap_rows: false,
                loading: false,
            },
            SettingsMode::Form(form) => {
                let visible = form
                    .fields
                    .iter()
                    .filter(|field| !field.advanced || form.advanced)
                    .collect::<Vec<_>>();
                ModelSettingsView {
                    title: match form.target {
                        FormTarget::Provider | FormTarget::NewProvider => "供应商设置".to_owned(),
                        FormTarget::Model | FormTarget::NewModel => "模型设置".to_owned(),
                    },
                    subtitle: if self.storage_unavailable() {
                        "存储不可用 · 按 F5 重读".to_owned()
                    } else if self.verification_pending() {
                        "写入未核实 · F5 核实或 Esc 放弃".to_owned()
                    } else if form.advanced {
                        "基础与高级参数 · ID 在保存重试期间保持稳定".to_owned()
                    } else {
                        "Tab 切换字段 · F2 展开高级设置".to_owned()
                    },
                    rows: Vec::new(),
                    fields: visible
                        .iter()
                        .enumerate()
                        .map(|(index, field)| {
                            let visible_focus = form.visible_field_index(form.focus);
                            ModelSettingsFieldView {
                                label: field.label.to_owned(),
                                value: if matches!(field.kind, FieldKind::Secret) {
                                    if field.value.is_empty() {
                                        "未更换".to_owned()
                                    } else {
                                        "••••••••".to_owned()
                                    }
                                } else {
                                    field.value.clone()
                                },
                                selected: visible_focus == Some(index),
                                secret: matches!(field.kind, FieldKind::Secret),
                                cursor: if visible_focus == Some(index)
                                    && matches!(
                                        field.kind,
                                        FieldKind::Text
                                            | FieldKind::Secret
                                            | FieldKind::Number
                                            | FieldKind::ReasoningMap
                                            | FieldKind::ReasoningDefault
                                    ) {
                                    Some(if matches!(field.kind, FieldKind::Secret) {
                                        form.cursor.min(8)
                                    } else {
                                        form.cursor
                                    })
                                } else {
                                    None
                                },
                            }
                        })
                        .collect(),
                    selected: form.visible_field_index(form.focus).unwrap_or(0),
                    scroll: form
                        .visible_field_index(form.focus)
                        .unwrap_or(0)
                        .saturating_sub(3),
                    status: self.status.clone(),
                    error: self.error.clone(),
                    footer: if self.storage_unavailable() {
                        "存储不可用 · F5重读\nEsc返回 Ctrl+D退出".to_owned()
                    } else if self.verification_pending() {
                        "F5核实 Esc离开\nCtrl+D退出".to_owned()
                    } else if self.pending_operation_id.is_some() {
                        "操作处理中 · 请稍候\nCtrl+D退出".to_owned()
                    } else {
                        "Tab切换 Ctrl+S保存 F2高级\nEsc返回 Ctrl+D退出".to_owned()
                    },
                    form: true,
                    wrap_rows: false,
                    loading: self.pending_operation_id.is_some(),
                }
            }
            SettingsMode::LeaveConfirm { selected, .. } => {
                let unverified = self.verification_pending();
                ModelSettingsView {
                    title: if unverified {
                        "写入结果未核实".to_owned()
                    } else {
                        "有未保存的修改".to_owned()
                    },
                    subtitle: if unverified {
                        "写入未核实；离开后密钥需重输".to_owned()
                    } else {
                        "离开前选择是否放弃当前草稿。".to_owned()
                    },
                    rows: if unverified {
                        vec![
                            (
                                "放弃草稿并返回".to_owned(),
                                "之后核实密钥需重输".to_owned(),
                                *selected == 0,
                            ),
                            (
                                "继续留在设置".to_owned(),
                                "按 F5 核实后才可编辑".to_owned(),
                                *selected == 1,
                            ),
                        ]
                    } else {
                        vec![
                            (
                                "放弃修改并返回".to_owned(),
                                "包括未保存的表单与密钥".to_owned(),
                                *selected == 0,
                            ),
                            (
                                "继续编辑".to_owned(),
                                "保留当前字段、光标和草稿".to_owned(),
                                *selected == 1,
                            ),
                        ]
                    },
                    fields: Vec::new(),
                    selected: *selected,
                    scroll: 0,
                    status: self.status.clone(),
                    error: self.error.clone(),
                    footer: "↑/↓选择 · Enter确认 · Esc返回\nCtrl+D退出".to_owned(),
                    form: false,
                    wrap_rows: false,
                    loading: false,
                }
            }
            SettingsMode::DeleteConfirm {
                target,
                selected,
                default_replacements,
                clears_default,
                clears_subagents,
            } => {
                let mut rows = Vec::new();
                if *clears_default {
                    let delete_target = delete_label(target, self.draft.as_ref());
                    for replacement in default_replacements {
                        rows.push((
                            format!(
                                "{} 并改用 {}",
                                delete_target,
                                model_label(self.draft.as_ref(), replacement)
                            ),
                            delete_impact("设为全局默认模型", *clears_subagents),
                            *selected == rows.len(),
                        ));
                    }
                    rows.push((
                        format!("{} 并清除全局默认模型", delete_target),
                        delete_impact("之后需显式选择模型", *clears_subagents),
                        *selected == rows.len(),
                    ));
                } else {
                    rows.push((
                        delete_label(target, self.draft.as_ref()),
                        delete_impact("", *clears_subagents),
                        *selected == rows.len(),
                    ));
                }
                rows.push((
                    "取消".to_owned(),
                    "保留模型与供应商".to_owned(),
                    *selected == rows.len(),
                ));
                ModelSettingsView {
                    title: "确认删除".to_owned(),
                    subtitle: "删除将在 Ctrl+S 后一次性保存；正在运行的 Turn 保留冻结配置。"
                        .to_owned(),
                    rows,
                    fields: Vec::new(),
                    selected: *selected,
                    scroll: self.scroll,
                    status: self.status.clone(),
                    error: self.error.clone(),
                    footer: "↑↓选择 · PgUp/Dn滚动 · Enter确认\nEsc取消 · Ctrl+D退出".to_owned(),
                    form: false,
                    wrap_rows: true,
                    loading: false,
                }
            }
            SettingsMode::TestConfirm {
                provider_id: _,
                model_id,
                selected,
            } => ModelSettingsView {
                title: "验证模型连接".to_owned(),
                subtitle: format!(
                    "将向 {} 发送一次真实请求，可能产生费用。",
                    safe_label(model_id)
                ),
                rows: vec![
                    (
                        "发送验证请求".to_owned(),
                        "仅在你确认后调用模型".to_owned(),
                        *selected == 0,
                    ),
                    (
                        "返回模型列表".to_owned(),
                        "不发送请求".to_owned(),
                        *selected == 1,
                    ),
                ],
                fields: Vec::new(),
                selected: *selected,
                scroll: 0,
                status: self.status.clone(),
                error: self.error.clone(),
                footer: "↑/↓ 选择 · Enter 确认 · Esc 返回\nCtrl+D 退出".to_owned(),
                form: false,
                wrap_rows: false,
                loading: false,
            },
            SettingsMode::RepairConfirm { selected } => ModelSettingsView {
                title: "备份并修复配置".to_owned(),
                subtitle: "先备份用户配置，再替换为可用内容".to_owned(),
                rows: vec![
                    (
                        "备份并替换为可用配置".to_owned(),
                        "未显示的坏条目与未知字段可能舍弃".to_owned(),
                        *selected == 0,
                    ),
                    ("取消".to_owned(), "不写入配置".to_owned(), *selected == 1),
                ],
                fields: Vec::new(),
                selected: *selected,
                scroll: 0,
                status: self.status.clone(),
                error: self.error.clone(),
                footer: "↑↓选择 Enter确认 Esc取消 Ctrl+D退出".to_owned(),
                form: false,
                wrap_rows: true,
                loading: false,
            },
            SettingsMode::List => self.list_view(),
        }
    }

    /// 清除 UI 草稿中的密钥正文；调用方只在确认退出或写入完成后调用。
    fn clear_form_secret(&mut self) {
        let form = match &mut self.mode {
            SettingsMode::Form(form) => Some(form),
            SettingsMode::LeaveConfirm { form, .. } => form.as_mut(),
            _ => None,
        };
        if let Some(form) = form {
            clear_form_secret(form);
        }
    }

    /// 远端错误可能回显请求值，先替换表单中暂存的 Secret 再交给可见状态。
    fn safe_message(&self, message: &str) -> String {
        let secret = match &self.mode {
            SettingsMode::Form(form) => form
                .fields
                .iter()
                .find(|field| matches!(field.kind, FieldKind::Secret))
                .map(|field| field.value.as_str()),
            SettingsMode::LeaveConfirm { form, .. } => form.as_ref().and_then(|form| {
                form.fields
                    .iter()
                    .find(|field| matches!(field.kind, FieldKind::Secret))
                    .map(|field| field.value.as_str())
            }),
            _ => None,
        };
        match secret.filter(|value| !value.is_empty()) {
            Some(secret) => safe_message(&message.replace(secret, "[REDACTED]")),
            None => safe_message(message),
        }
    }

    /// 信任被拒绝后回到已读取的设置列表，避免拒绝动作丢失当前可管理状态。
    pub(super) fn trust_denied(&mut self) -> bool {
        if self.baseline.is_some() {
            self.mode = SettingsMode::List;
            self.status = Some("未修改工作区信任；设置和历史仍可使用".to_owned());
            true
        } else {
            false
        }
    }

    /// 对话创建期间保留设置面板，等到权威 Thread 快照到达后再由 reducer 关闭。
    pub(super) fn starting_conversation(&mut self) {
        self.status = Some("正在开始对话…".to_owned());
    }

    /// 退出面板前清除表单中仅用于本次凭据写入的 Secret。
    pub(super) fn clear_secrets(&mut self) {
        self.clear_form_secret();
    }

    /// 判定会话是否有待保存差量或仅驻留内存的凭据。
    fn is_dirty(&self) -> bool {
        let document_dirty = self
            .baseline
            .as_ref()
            .zip(self.draft.as_ref())
            .is_some_and(|(baseline, draft)| configuration_patch(baseline, draft) != json!({}));
        let form_dirty = match &self.mode {
            SettingsMode::Form(form) => form.is_dirty(),
            SettingsMode::LeaveConfirm {
                form: Some(form), ..
            } => form.is_dirty(),
            _ => false,
        };
        document_dirty || form_dirty
    }

    /// 为每类异步副作用分配唯一 ID，UI 状态只接受最后一个仍然 pending 的结果。
    fn request(
        &mut self,
        kind: PendingKind,
        mut request: ModelSettingsRequest,
    ) -> ModelSettingsEffect {
        let operation_id = Uuid::new_v4().to_string();
        match &mut request {
            ModelSettingsRequest::Read { operation_id: id }
            | ModelSettingsRequest::Verify { operation_id: id }
            | ModelSettingsRequest::Test {
                operation_id: id, ..
            }
            | ModelSettingsRequest::Repair {
                operation_id: id, ..
            }
            | ModelSettingsRequest::Save {
                operation_id: id, ..
            } => *id = operation_id.clone(),
        }
        self.pending_operation_id = Some(operation_id);
        self.pending_kind = Some(kind);
        self.status = Some(match kind {
            PendingKind::Read => "正在读取配置…".to_owned(),
            PendingKind::Save => "正在保存并回读…".to_owned(),
            PendingKind::Verify => "正在核实原操作结果…".to_owned(),
            PendingKind::Test => "正在验证模型连接…".to_owned(),
            PendingKind::Repair => "正在备份并修复…".to_owned(),
        });
        self.error = None;
        ModelSettingsEffect::Request(request)
    }

    /// 供应商名称只放在分组标签，详情保留协议、凭据与问题；模型标签始终使用真实上游标识。
    fn list_view(&self) -> ModelSettingsView {
        let document = self.draft.as_ref();
        let providers = providers(document);
        let mut alias_counts = BTreeMap::<String, usize>::new();
        for model in providers.iter().flat_map(|provider| models(provider)) {
            let alias = text(model.get("name"));
            if !alias.is_empty() {
                *alias_counts.entry(alias).or_default() += 1;
            }
        }
        let default_provider = document
            .and_then(|value| value.get("default_provider_id"))
            .and_then(Value::as_str);
        let default_model = document
            .and_then(|value| value.get("default_model_id"))
            .and_then(Value::as_str);
        let mut rows = Vec::new();
        for provider in providers {
            let provider_id = text(provider.get("provider_id"));
            let provider_name = text(provider.get("name"));
            let credential_id = text(provider.get("credential_id"));
            let configured =
                credential_configured(self.server_configuration.as_ref(), &credential_id);
            let provider_issue_count =
                issue_count(self.server_configuration.as_ref(), &provider_id);
            let mut detail = text(provider.get("api"));
            if configured {
                detail.push_str(" · 密钥已配置");
            } else {
                detail.push_str(" · 密钥待补齐");
            }
            if provider_issue_count > 0 {
                detail.push_str(&format!(" · {} 项问题", provider_issue_count));
            }
            rows.push((format!("▸ {}", provider_name), detail, false));
            for model in models(provider) {
                let model_id = text(model.get("model_id"));
                let is_default = default_provider == Some(provider_id.as_str())
                    && default_model == Some(model_id.as_str());
                let alias = text(model.get("name"));
                let upstream = text(model.get("model"));
                let alias = if alias_counts.get(&alias).copied().unwrap_or_default() > 1 {
                    String::new()
                } else {
                    alias
                };
                let upstream = if upstream.is_empty() {
                    model_id.clone()
                } else {
                    upstream
                };
                let mut detail = if alias.is_empty() || alias == upstream {
                    String::new()
                } else {
                    alias
                };
                if is_default {
                    push_detail(&mut detail, "全局默认");
                }
                if !configured {
                    push_detail(&mut detail, "密钥待补齐");
                }
                let model_issue_count = issue_count(self.server_configuration.as_ref(), &model_id);
                if model_issue_count > 0 {
                    push_detail(&mut detail, &format!("{} 项问题", model_issue_count));
                }
                rows.push((upstream, detail, false));
            }
        }
        if rows.is_empty() {
            rows.push((
                "尚未配置模型".to_owned(),
                "按 A 新增供应商和首个模型".to_owned(),
                false,
            ));
        }
        for (index, row) in rows.iter_mut().enumerate() {
            row.2 = index == self.selected;
        }
        let (title, subtitle) = if self.storage_unavailable() {
            (
                "全局模型设置 · 存储不可用".to_owned(),
                "存储不可用 · 按 F5 重读".to_owned(),
            )
        } else if self.corrupt_or_unsupported() {
            (
                "全局模型设置 · 配置需要修复".to_owned(),
                "普通保存已停用；按 R 查看备份修复说明。".to_owned(),
            )
        } else if self.draft.is_none() {
            (
                "全局模型设置".to_owned(),
                "正在从 App Server 读取供应商与模型。".to_owned(),
            )
        } else {
            (
                "全局模型设置".to_owned(),
                "模型按供应商分组 · 主标签为真实上游模型标识".to_owned(),
            )
        };
        let dirty = self.is_dirty();
        let footer = if self.storage_unavailable() {
            "F5重读 · Esc返回\nCtrl+D退出"
        } else if self.pending_operation_id.is_some() {
            "操作处理中 · 请稍候\nCtrl+D退出"
        } else if self.pending_kind == Some(PendingKind::Verify) {
            "F5核实 · Esc放弃\nCtrl+D退出"
        } else if dirty {
            "↑↓选 Enter开 A添 M添 D默认 T验证\nDel删 Ctrl+S保存 Esc返回 Ctrl+D退出"
        } else {
            if self.standalone {
                "↑↓选 Enter开 A添 M添 D默认 T验证 Del删\nCtrl+S保存 Ctrl+↵对话 Esc返回 Ctrl+D退出"
            } else {
                "↑↓选 Enter开 A添 M添 D默认 T验证 Del删\nCtrl+S保存 F5刷新 Esc返回 Ctrl+D退出"
            }
        };
        ModelSettingsView {
            title,
            subtitle,
            rows,
            fields: Vec::new(),
            selected: self.selected,
            scroll: self.scroll,
            status: self.status.clone(),
            error: self.error.clone(),
            footer: footer.to_owned(),
            form: false,
            wrap_rows: false,
            loading: self.pending_operation_id.is_some(),
        }
    }

    /// 语法恢复出的 partial 文档只含可读前缀，须与损坏文档一样显式备份修复；
    /// I/O 故障无法确认原始字节，必须留给只读重读而非替换路径处理。
    fn corrupt_or_unsupported(&self) -> bool {
        if self.storage_unavailable() {
            return false;
        }
        self.server_configuration
            .as_ref()
            .and_then(|configuration| configuration.get("user"))
            .and_then(|layer| layer.get("status"))
            .and_then(Value::as_str)
            .is_some_and(|status| matches!(status, "partial" | "corrupt"))
            || self
                .server_configuration
                .as_ref()
                .and_then(user_document)
                .and_then(|document| document.get("schema_version").and_then(Value::as_u64))
                .is_some_and(|version| version != 2)
    }

    /// I/O 故障只允许重新读取；不可读不等同于可安全备份的损坏文档。
    fn storage_unavailable(&self) -> bool {
        self.server_configuration
            .as_ref()
            .and_then(|configuration| configuration.get("user"))
            .and_then(|layer| layer.get("status"))
            .and_then(Value::as_str)
            == Some("io_error")
    }

    /// 未知写入结果时锁住本地编辑，只允许原操作核实或经确认离开。
    fn verification_pending(&self) -> bool {
        self.pending_kind == Some(PendingKind::Verify) && self.pending_operation_id.is_none()
    }

    /// 保留待核实表单到确认页，取消仍可恢复原字段，确认离开才清除密钥。
    fn confirm_abandon_unverified(&mut self) -> ModelSettingsEffect {
        let mode = std::mem::replace(&mut self.mode, SettingsMode::List);
        let form = match mode {
            SettingsMode::Form(form) => Some(form),
            SettingsMode::LeaveConfirm {
                form,
                start_conversation,
                ..
            } => {
                self.mode = SettingsMode::LeaveConfirm {
                    selected: 1,
                    form,
                    start_conversation,
                };
                return ModelSettingsEffect::None;
            }
            _ => None,
        };
        self.mode = SettingsMode::LeaveConfirm {
            selected: 1,
            form,
            start_conversation: false,
        };
        self.status = None;
        self.error = None;
        ModelSettingsEffect::None
    }

    /// 将列表选择映射到唯一实体操作；危险动作只进入确认态，保存仍由 CAS patch 完成。
    fn list_key(&mut self, key: KeyEvent, has_thread: bool) -> ModelSettingsEffect {
        if key.code == KeyCode::Esc {
            return self.close(false);
        }
        if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Enter {
            return self.close(!has_thread);
        }
        if self.storage_unavailable() {
            self.status = Some("存储不可用；按 F5 重读后再编辑".to_owned());
            return ModelSettingsEffect::None;
        }
        if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('s') {
            return self.save_draft();
        }
        let rows = row_targets(self.draft.as_ref());
        match key.code {
            KeyCode::Up => self.selected = self.selected.saturating_sub(1),
            KeyCode::Down => self.selected = (self.selected + 1).min(rows.len().saturating_sub(1)),
            KeyCode::Enter | KeyCode::Char('e') => self.edit_selected(&rows),
            KeyCode::Char('a') | KeyCode::Char('A') => self.new_provider(),
            KeyCode::Char('m') | KeyCode::Char('M') => self.new_model(&rows),
            KeyCode::Char('d') | KeyCode::Char('D') => {
                if let Some(target) = rows.get(self.selected).and_then(Option::as_ref) {
                    self.set_default(target);
                }
            }
            KeyCode::Delete | KeyCode::Backspace => {
                if let Some(target) = rows.get(self.selected).and_then(Clone::clone) {
                    self.confirm_delete(target);
                }
            }
            KeyCode::Char('t') | KeyCode::Char('T') => {
                if let Some((provider_id, model_id)) = selected_model(&rows, self.selected) {
                    self.mode = SettingsMode::TestConfirm {
                        provider_id,
                        model_id,
                        selected: 0,
                    };
                } else {
                    self.status = Some("请先选择一个模型".to_owned());
                }
            }
            KeyCode::Char('r') | KeyCode::Char('R') => {
                if self.corrupt_or_unsupported() {
                    self.mode = SettingsMode::RepairConfirm { selected: 0 };
                } else {
                    self.status = Some("当前配置可直接编辑，无需修复".to_owned());
                }
            }
            _ => {}
        }
        ModelSettingsEffect::None
    }

    /// 打开所选供应商或模型的局部表单；缺失条目只允许刷新，避免编辑旧快照。
    fn edit_selected(&mut self, rows: &[Option<SettingsRowTarget>]) {
        let Some(Some(target)) = rows.get(self.selected) else {
            return;
        };
        self.mode = match target {
            SettingsRowTarget::Provider(provider_id) => {
                self.provider_form(provider_id.clone(), false)
            }
            SettingsRowTarget::Model(reference) => self.model_form(
                reference.provider_id.clone(),
                reference.model_id.clone(),
                false,
            ),
        };
        self.status = None;
        self.error = None;
    }

    /// 新供应商必须同时配置首个模型，稳定身份在重试期间保持不变。
    fn new_provider(&mut self) {
        let provider_id = generated_id("provider_");
        let model_id = generated_id("model_");
        let credential_id = generated_id("cred_");
        self.mode = self.provider_form_with_ids(provider_id, credential_id, Some(model_id), true);
        self.status = None;
        self.error = None;
    }

    /// 新模型归属当前所选供应商；无可用供应商时回到必须成组创建的流程。
    fn new_model(&mut self, rows: &[Option<SettingsRowTarget>]) {
        let provider_id =
            rows.get(self.selected)
                .and_then(Option::as_ref)
                .map(|target| match target {
                    SettingsRowTarget::Provider(provider_id) => provider_id.clone(),
                    SettingsRowTarget::Model(reference) => reference.provider_id.clone(),
                });
        let Some(provider_id) = provider_id else {
            self.new_provider();
            return;
        };
        let model_id = generated_id("model_");
        self.mode = self.model_form(provider_id, model_id, true);
        self.status = None;
        self.error = None;
    }

    /// 当前模型被设为默认时同步默认推理档位，防止根偏好指向已不支持的等级。
    fn set_default(&mut self, target: &SettingsRowTarget) {
        let Some(document) = self.draft.as_mut() else {
            return;
        };
        let reference = match target {
            SettingsRowTarget::Model(reference) => Some(reference.clone()),
            SettingsRowTarget::Provider(provider_id) => providers(Some(document))
                .into_iter()
                .find(|provider| text(provider.get("provider_id")) == *provider_id)
                .and_then(|provider| {
                    models(provider).first().map(|model| ProviderModelRef {
                        provider_id: provider_id.clone(),
                        model_id: text(model.get("model_id")),
                    })
                }),
        };
        if let Some(reference) = reference {
            let reasoning_level =
                find_model(Some(document), &reference.provider_id, &reference.model_id)
                    .and_then(|model| model.get("default_reasoning_level"))
                    .cloned()
                    .unwrap_or(Value::Null);
            document["default_provider_id"] = Value::String(reference.provider_id);
            document["default_model_id"] = Value::String(reference.model_id);
            document["default_reasoning_level"] = reasoning_level;
            self.status = Some("已更新默认模型草稿；按 Ctrl+S 保存".to_owned());
        }
    }

    /// 删除预览先计算默认替代项和全局子智能体引用，确认后再同时修改关联字段。
    fn confirm_delete(&mut self, target: SettingsRowTarget) {
        let Some(document) = self.draft.as_ref() else {
            return;
        };
        let target = match target {
            SettingsRowTarget::Model(reference)
                if find_provider(Some(document), &reference.provider_id).is_some_and(
                    |provider| {
                        let provider_models = models(provider);
                        provider_models.len() == 1
                            && text(provider_models[0].get("model_id")) == reference.model_id
                    },
                ) =>
            {
                SettingsRowTarget::Provider(reference.provider_id)
            }
            target => target,
        };
        let references = target_model_refs(document, &target);
        let is_default = references.iter().any(|reference| {
            document.get("default_provider_id").and_then(Value::as_str)
                == Some(reference.provider_id.as_str())
                && document.get("default_model_id").and_then(Value::as_str)
                    == Some(reference.model_id.as_str())
        });
        let default_replacements = if is_default {
            all_model_refs(document)
                .into_iter()
                .filter(|reference| {
                    !references.iter().any(|deleted| {
                        deleted.provider_id == reference.provider_id
                            && deleted.model_id == reference.model_id
                    })
                })
                .collect()
        } else {
            Vec::new()
        };
        let clears_subagents = target_referenced_by_subagents(document, &target);
        let clears_default = is_default;
        let selected = 0;
        self.scroll = 0;
        self.mode = SettingsMode::DeleteConfirm {
            target,
            selected,
            default_replacements,
            clears_default,
            clears_subagents,
        };
    }

    /// 删除确认结果保留稳定顺序，并要求用户为默认引用选择替代项或明确清空。
    fn delete_key(
        &mut self,
        key: KeyEvent,
        target: SettingsRowTarget,
        selected: &mut usize,
        default_replacements: Vec<ProviderModelRef>,
        clears_default: bool,
        clears_subagents: bool,
    ) -> ModelSettingsEffect {
        let action_count = if clears_default {
            default_replacements.len() + 1
        } else {
            1
        };
        match key.code {
            KeyCode::Esc => {
                self.mode = SettingsMode::List;
            }
            KeyCode::Up => {
                *selected = selected.saturating_sub(1);
                self.scroll = 0;
            }
            KeyCode::Down => {
                *selected = (*selected + 1).min(action_count);
                self.scroll = 0;
            }
            KeyCode::PageUp => self.scroll = self.scroll.saturating_sub(3),
            KeyCode::PageDown => self.scroll = self.scroll.saturating_add(3),
            KeyCode::Enter => {
                if *selected == action_count {
                    self.mode = SettingsMode::List;
                    return ModelSettingsEffect::None;
                }
                let replacement = if clears_default && *selected < default_replacements.len() {
                    default_replacements.get(*selected).cloned()
                } else {
                    None
                };
                let clear_subagents = clears_subagents;
                self.apply_delete(target.clone(), replacement, clear_subagents);
                self.mode = SettingsMode::List;
            }
            _ => {}
        }
        if !matches!(key.code, KeyCode::Esc | KeyCode::Enter) {
            self.mode = SettingsMode::DeleteConfirm {
                target,
                selected: *selected,
                default_replacements,
                clears_default,
                clears_subagents,
            };
        }
        ModelSettingsEffect::None
    }

    /// 关联解除和实体删除仅落到本地草稿，之后与其它改动在一笔 CAS 中保存。
    fn apply_delete(
        &mut self,
        target: SettingsRowTarget,
        replacement: Option<ProviderModelRef>,
        clear_subagents: bool,
    ) {
        let Some(document) = self.draft.as_mut() else {
            return;
        };
        let removed_models = target_model_refs(document, &target);
        match target {
            SettingsRowTarget::Provider(provider_id) => {
                if let Some(array) = document.get_mut("providers").and_then(Value::as_array_mut) {
                    array.retain(|provider| text(provider.get("provider_id")) != provider_id);
                }
            }
            SettingsRowTarget::Model(reference) => {
                if let Some(array) = document.get_mut("providers").and_then(Value::as_array_mut)
                    && let Some(provider) = array
                        .iter_mut()
                        .find(|provider| text(provider.get("provider_id")) == reference.provider_id)
                    && let Some(models) = provider.get_mut("models").and_then(Value::as_array_mut)
                {
                    models.retain(|model| text(model.get("model_id")) != reference.model_id);
                }
            }
        }
        if let Some(replacement) = replacement {
            let reasoning_level = find_model(
                Some(document),
                &replacement.provider_id,
                &replacement.model_id,
            )
            .and_then(|model| model.get("default_reasoning_level"))
            .cloned()
            .unwrap_or(Value::Null);
            document["default_provider_id"] = Value::String(replacement.provider_id);
            document["default_model_id"] = Value::String(replacement.model_id);
            document["default_reasoning_level"] = reasoning_level;
        } else if removed_models.iter().any(|reference| {
            document.get("default_provider_id").and_then(Value::as_str)
                == Some(reference.provider_id.as_str())
                && document.get("default_model_id").and_then(Value::as_str)
                    == Some(reference.model_id.as_str())
        }) {
            document["default_provider_id"] = Value::Null;
            document["default_model_id"] = Value::Null;
            document["default_reasoning_level"] = Value::Null;
        }
        if clear_subagents
            && let Some(subagents) = document.get_mut("subagents").and_then(Value::as_object_mut)
        {
            subagents.insert("provider_id".to_owned(), Value::Null);
            subagents.insert("model_id".to_owned(), Value::Null);
            subagents.insert("reasoning_level".to_owned(), Value::Null);
        }
        self.status = Some("删除和引用调整已加入草稿；按 Ctrl+S 一次保存".to_owned());
        self.selected = self
            .selected
            .min(row_targets(self.draft.as_ref()).len().saturating_sub(1));
    }

    /// 测试请求前必须确认可能产生费用；取消时不调用 controller。
    fn test_confirm_key(
        &mut self,
        key: KeyEvent,
        provider_id: String,
        model_id: String,
        selected: &mut usize,
    ) -> ModelSettingsEffect {
        match key.code {
            KeyCode::Esc => self.mode = SettingsMode::List,
            KeyCode::Up | KeyCode::Down => *selected = 1 - *selected,
            KeyCode::Enter if *selected == 1 => self.mode = SettingsMode::List,
            KeyCode::Enter => {
                self.mode = SettingsMode::List;
                return self.request(
                    PendingKind::Test,
                    ModelSettingsRequest::Test {
                        operation_id: String::new(),
                        provider_id,
                        model_id,
                    },
                );
            }
            _ => {}
        }
        if !matches!(key.code, KeyCode::Esc | KeyCode::Enter) {
            self.mode = SettingsMode::TestConfirm {
                provider_id,
                model_id,
                selected: *selected,
            };
        }
        ModelSettingsEffect::None
    }

    /// 只有明确选择备份修复才发送 replace 文档，并绑定读取到的用户 CAS 版本。
    fn repair_confirm_key(&mut self, key: KeyEvent, selected: &mut usize) -> ModelSettingsEffect {
        match key.code {
            KeyCode::Esc => self.mode = SettingsMode::List,
            KeyCode::Up | KeyCode::Down => *selected = 1 - *selected,
            KeyCode::Enter if *selected == 1 => self.mode = SettingsMode::List,
            KeyCode::Enter => {
                self.mode = SettingsMode::List;
                let Some(expected_version) =
                    cas_version(self.server_configuration.as_ref(), "userVersion")
                else {
                    self.error =
                        Some("缺少有效配置版本，不能执行修复；请按 F5 重新读取".to_owned());
                    return ModelSettingsEffect::None;
                };
                let document = self.draft.clone().unwrap_or_else(default_document);
                return self.request(
                    PendingKind::Repair,
                    ModelSettingsRequest::Repair {
                        operation_id: String::new(),
                        expected_version,
                        document,
                    },
                );
            }
            _ => {}
        }
        if !matches!(key.code, KeyCode::Esc | KeyCode::Enter) {
            self.mode = SettingsMode::RepairConfirm {
                selected: *selected,
            };
        }
        ModelSettingsEffect::None
    }

    /// 表单输入只作用于当前字段；高级项折叠时 Tab 不会进入不可见控件。
    fn form_key(&mut self, key: KeyEvent, mut form: SettingsForm) -> ModelSettingsEffect {
        let control = key.modifiers.contains(KeyModifiers::CONTROL);
        if self.storage_unavailable() && key.code != KeyCode::Esc {
            self.mode = SettingsMode::Form(form);
            self.status = Some("存储不可用；按 F5 重读后再编辑".to_owned());
            return ModelSettingsEffect::None;
        }
        if key.code == KeyCode::F(2) {
            form.advanced = !form.advanced;
            self.status = Some(
                if form.advanced {
                    "高级参数已展开"
                } else {
                    "高级参数已折叠"
                }
                .to_owned(),
            );
            self.mode = SettingsMode::Form(form);
            return ModelSettingsEffect::None;
        }
        if control && key.code == KeyCode::Char('s') {
            return self.commit_form_and_save(form);
        }
        match key.code {
            KeyCode::Esc => {
                if form.is_dirty() {
                    self.mode = SettingsMode::LeaveConfirm {
                        selected: 1,
                        form: Some(form),
                        start_conversation: false,
                    };
                } else {
                    self.mode = SettingsMode::List;
                }
                return ModelSettingsEffect::None;
            }
            KeyCode::Tab | KeyCode::BackTab => form.move_focus(key.code == KeyCode::BackTab),
            KeyCode::Enter => {
                if form.focus + 1 < form.fields.len() {
                    form.move_focus(false);
                } else {
                    return self.commit_form_and_save(form);
                }
            }
            KeyCode::Left | KeyCode::Right => form.cycle_focused(key.code == KeyCode::Right),
            KeyCode::Char(' ')
                if form
                    .fields
                    .get(form.focus)
                    .is_some_and(|field| matches!(field.kind, FieldKind::Boolean)) =>
            {
                form.toggle_boolean()
            }
            KeyCode::Backspace => form.backspace(),
            KeyCode::Delete => form.delete(),
            KeyCode::Home => form.cursor = 0,
            KeyCode::End => {
                form.cursor = form
                    .fields
                    .get(form.focus)
                    .map_or(0, |field| field.value.graphemes(true).count())
            }
            KeyCode::Char(ch)
                if !control
                    && !key
                        .modifiers
                        .intersects(KeyModifiers::ALT | KeyModifiers::SUPER) =>
            {
                form.insert_char(ch)
            }
            _ => {}
        }
        self.error = None;
        self.mode = SettingsMode::Form(form);
        ModelSettingsEffect::None
    }

    /// 将当前表单差量并入本地文档后提交 CAS patch；新凭据始终走独立 credential 字段。
    fn commit_form_and_save(&mut self, mut form: SettingsForm) -> ModelSettingsEffect {
        if let Err(validation) = form.validate() {
            form.focus_field(validation.field_key);
            self.mode = SettingsMode::Form(form);
            self.error = Some(validation.message);
            return ModelSettingsEffect::None;
        }
        let Some(document) = self.draft.as_mut() else {
            self.mode = SettingsMode::Form(form);
            self.error = Some("配置尚未读取，不能保存".to_owned());
            return ModelSettingsEffect::None;
        };
        if let Some(field) = form.fields.iter().find(|field| field.key == "make_default") {
            form.make_default = field.value == "true";
        }
        form.apply_to(document);
        if form.make_default
            && let Some(model_id) = form.model_id.as_deref()
        {
            let reasoning_level = find_model(Some(document), &form.provider_id, model_id)
                .and_then(|model| model.get("default_reasoning_level"))
                .cloned()
                .unwrap_or(Value::Null);
            document["default_provider_id"] = Value::String(form.provider_id.clone());
            document["default_model_id"] = Value::String(model_id.to_owned());
            document["default_reasoning_level"] = reasoning_level;
        }
        let credential = form
            .fields
            .iter()
            .find(|field| matches!(field.kind, FieldKind::Secret))
            .filter(|field| !field.value.is_empty())
            .map(|field| ModelSettingsCredential {
                provider_id: form.provider_id.clone(),
                credential_id: form.credential_id.clone(),
                secret: field.value.clone(),
            });
        self.mode = SettingsMode::Form(form);
        self.save_draft_with_credential(credential)
    }

    /// 只允许含有真实差量或凭据的保存；CAS 版本缺失时失败关闭而不构造空版本。
    fn save_draft(&mut self) -> ModelSettingsEffect {
        self.save_draft_with_credential(None)
    }

    /// 保存链先 patch 再写凭据由 controller 编排；本地保留原 Secret 直到权威结果确认。
    fn save_draft_with_credential(
        &mut self,
        credential: Option<ModelSettingsCredential>,
    ) -> ModelSettingsEffect {
        let (Some(baseline), Some(draft)) = (self.baseline.as_ref(), self.draft.as_ref()) else {
            self.error = Some("配置尚未读取；按 F5 重试读取".to_owned());
            return ModelSettingsEffect::None;
        };
        if self.corrupt_or_unsupported() {
            self.error = Some("配置损坏或版本不受支持；请使用 R 进入备份修复".to_owned());
            return ModelSettingsEffect::None;
        }
        let patch = configuration_patch(baseline, draft);
        if patch == json!({}) && credential.is_none() {
            self.mode = SettingsMode::List;
            self.status = Some("没有待保存的修改".to_owned());
            return ModelSettingsEffect::None;
        }
        let Some(expected_version) = cas_version(self.server_configuration.as_ref(), "userVersion")
        else {
            self.error = Some("缺少配置 CAS 版本；按 F5 重新读取".to_owned());
            return ModelSettingsEffect::None;
        };
        let Some(expected_credential_version) =
            cas_version(self.server_configuration.as_ref(), "credentialVersion")
        else {
            self.error = Some("缺少凭据 CAS 版本；按 F5 重新读取".to_owned());
            return ModelSettingsEffect::None;
        };
        self.request(
            PendingKind::Save,
            ModelSettingsRequest::Save {
                operation_id: String::new(),
                expected_version,
                expected_credential_version,
                patch,
                credential,
            },
        )
    }

    /// 退出前按 dirty 状态确认；controller 只负责新 Thread 动作，不重建当前 Composer。
    fn close(&mut self, start_conversation: bool) -> ModelSettingsEffect {
        if self.is_dirty() {
            self.mode = SettingsMode::LeaveConfirm {
                selected: 1,
                form: None,
                start_conversation,
            };
            self.status = None;
            return ModelSettingsEffect::None;
        }
        self.clear_form_secret();
        ModelSettingsEffect::Close { start_conversation }
    }

    /// 当前状态暴露给 renderer 的纯展示数据。
    pub(super) fn view_for_render(&self) -> ModelSettingsView {
        self.view()
    }

    /// 通过局部表单修改已有供应商连接参数。
    fn provider_form(&self, provider_id: String, is_new: bool) -> SettingsMode {
        let provider = find_provider(self.draft.as_ref(), &provider_id);
        let credential_id = provider
            .map(|value| text(value.get("credential_id")))
            .filter(|value| !value.is_empty())
            .unwrap_or_else(|| generated_id("cred_"));
        self.provider_form_with_ids(provider_id, credential_id, None, is_new)
    }

    /// 构造供应商连接表单并只在创建时追加首个模型字段。
    fn provider_form_with_ids(
        &self,
        provider_id: String,
        credential_id: String,
        initial_model_id: Option<String>,
        is_new: bool,
    ) -> SettingsMode {
        let provider = find_provider(self.draft.as_ref(), &provider_id);
        let mut fields = vec![
            text_field(
                "name",
                "供应商名称",
                provider
                    .and_then(|value| value.get("name"))
                    .map(value_string)
                    .unwrap_or_else(|| {
                        if is_new {
                            "新供应商".to_owned()
                        } else {
                            String::new()
                        }
                    }),
                false,
            ),
            select_field(
                "api",
                "API 协议",
                provider
                    .and_then(|value| value.get("api"))
                    .and_then(Value::as_str)
                    .unwrap_or(API_SPECS[0]),
                FieldKind::Protocol,
                false,
            ),
            text_field(
                "base_url",
                "Base URL",
                provider
                    .and_then(|value| value.get("base_url"))
                    .map(value_string)
                    .unwrap_or_default(),
                false,
            ),
            FormField {
                key: "credential",
                label: if credential_configured(self.server_configuration.as_ref(), &credential_id)
                {
                    "更换 API Key"
                } else {
                    "API Key"
                },
                value: String::new(),
                kind: FieldKind::Secret,
                advanced: false,
            },
            number_field(
                "connect_timeout_ms",
                "连接超时 (ms)",
                provider
                    .and_then(|value| value.pointer("/network_timeouts/connect_timeout_ms"))
                    .and_then(Value::as_u64)
                    .unwrap_or(DEFAULT_CONNECT_TIMEOUT_MS),
                true,
            ),
            number_field(
                "request_timeout_ms",
                "请求超时 (ms)",
                provider
                    .and_then(|value| value.pointer("/network_timeouts/request_timeout_ms"))
                    .and_then(Value::as_u64)
                    .unwrap_or(DEFAULT_REQUEST_TIMEOUT_MS),
                true,
            ),
            boolean_field(
                "auto_compact",
                "自动压缩上下文",
                provider
                    .and_then(|value| value.pointer("/agent_defaults/context/auto_compact"))
                    .and_then(Value::as_bool)
                    .unwrap_or(true),
                true,
            ),
        ];
        let model_id = initial_model_id.clone();
        if let Some(model_id) = initial_model_id.as_deref() {
            fields.extend(new_model_fields(None, model_id));
            let make_default = self.draft.as_ref().is_some_and(|document| {
                document
                    .get("default_provider_id")
                    .is_none_or(Value::is_null)
            });
            fields.push(boolean_field(
                "make_default",
                "设为全局默认模型",
                make_default,
                false,
            ));
        }
        let default_missing = self.draft.as_ref().is_some_and(|document| {
            document
                .get("default_provider_id")
                .is_none_or(Value::is_null)
        });
        SettingsMode::Form(SettingsForm::new(
            provider_id,
            credential_id,
            model_id,
            if is_new {
                FormTarget::NewProvider
            } else {
                FormTarget::Provider
            },
            fields,
            default_missing,
        ))
    }

    /// 构造模型字段表单；字段能力和推理映射只展示用户明确保存的值。
    fn model_form(&self, provider_id: String, model_id: String, is_new: bool) -> SettingsMode {
        let existing = find_model(self.draft.as_ref(), &provider_id, &model_id);
        let mut fields = new_model_fields(existing, &model_id);
        let is_current_default = self.draft.as_ref().is_some_and(|document| {
            document.get("default_provider_id").and_then(Value::as_str)
                == Some(provider_id.as_str())
                && document.get("default_model_id").and_then(Value::as_str)
                    == Some(model_id.as_str())
        });
        let default_missing = self.draft.as_ref().is_some_and(|document| {
            document
                .get("default_provider_id")
                .is_none_or(Value::is_null)
        });
        let make_default = is_current_default || (is_new && default_missing);
        fields.push(boolean_field(
            "make_default",
            "设为全局默认模型",
            make_default,
            false,
        ));
        let credential_id = find_provider(self.draft.as_ref(), &provider_id)
            .map(|provider| text(provider.get("credential_id")).to_owned())
            .unwrap_or_default();
        SettingsMode::Form(SettingsForm::new(
            provider_id,
            credential_id,
            Some(model_id),
            if is_new {
                FormTarget::NewModel
            } else {
                FormTarget::Model
            },
            fields,
            make_default,
        ))
    }
}

impl SettingsForm {
    /// 创建可编辑字段快照，新增实体的 ID 和默认选择在保存前后保持稳定。
    fn new(
        provider_id: String,
        credential_id: String,
        model_id: Option<String>,
        target: FormTarget,
        fields: Vec<FormField>,
        make_default: bool,
    ) -> Self {
        let initial_fields = fields.iter().map(|field| field.value.clone()).collect();
        Self {
            provider_id,
            credential_id,
            model_id,
            target,
            fields,
            focus: 0,
            cursor: 0,
            advanced: false,
            make_default,
            initial_default: make_default,
            initial_fields,
        }
    }

    /// 仅表单相对打开时的字段或密钥改变才触发离开确认。
    fn is_dirty(&self) -> bool {
        self.make_default != self.initial_default
            || self
                .fields
                .iter()
                .zip(&self.initial_fields)
                .any(|(field, initial)| field.value != *initial)
    }

    /// 隐藏高级组时跳过不可见字段，并将字素光标放到新焦点末尾。
    fn move_focus(&mut self, backwards: bool) {
        let count = self.fields.len();
        if count == 0 {
            return;
        }
        for _ in 0..count {
            self.focus = if backwards {
                self.focus.checked_sub(1).unwrap_or(count - 1)
            } else {
                (self.focus + 1) % count
            };
            if self.advanced || !self.fields[self.focus].advanced {
                break;
            }
        }
        self.cursor = self
            .fields
            .get(self.focus)
            .map_or(0, |field| field.value.graphemes(true).count());
    }

    /// 校验失败后聚焦首个错误字段；高级字段先展开，光标定位到原值末端便于修正。
    fn focus_field(&mut self, field_key: &'static str) {
        let Some(index) = self.fields.iter().position(|field| field.key == field_key) else {
            return;
        };
        self.advanced |= self.fields[index].advanced;
        self.focus = index;
        self.cursor = self.fields[index].value.graphemes(true).count();
    }

    /// 协议、布尔值与推理等级通过显式闭集循环，文本项则保留手动编辑语义。
    fn cycle_focused(&mut self, forward: bool) {
        let Some(field) = self.fields.get_mut(self.focus) else {
            return;
        };
        match field.kind {
            FieldKind::Protocol => {
                let current = API_SPECS
                    .iter()
                    .position(|value| *value == field.value)
                    .unwrap_or(0);
                field.value = API_SPECS[cycle_index(current, API_SPECS.len(), forward)].to_owned();
            }
            FieldKind::Boolean => {
                field.value = if field.value == "true" {
                    "false"
                } else {
                    "true"
                }
                .to_owned();
            }
            FieldKind::ReasoningDefault => {
                let mut options = vec!["模型默认"];
                options.extend(REASONING_LEVELS);
                let current = options
                    .iter()
                    .position(|value| *value == field.value)
                    .unwrap_or(0);
                field.value = options[cycle_index(current, options.len(), forward)].to_owned();
            }
            _ => {
                if forward {
                    self.cursor = self
                        .cursor
                        .saturating_add(1)
                        .min(field.value.graphemes(true).count());
                } else {
                    self.cursor = self.cursor.saturating_sub(1);
                }
            }
        }
    }

    /// 通过空格切换布尔项，避免文本输入期间误判普通字母作为开关。
    fn toggle_boolean(&mut self) {
        if let Some(field) = self.fields.get_mut(self.focus)
            && matches!(field.kind, FieldKind::Boolean)
        {
            field.value = if field.value == "true" {
                "false"
            } else {
                "true"
            }
            .to_owned();
        }
    }

    /// 输入文本在字素光标处插入，限制单字段长度并拒绝控制字符污染终端行布局。
    fn insert_char(&mut self, ch: char) {
        if ch.is_control() {
            return;
        }
        if let Some(field) = self.fields.get_mut(self.focus)
            && matches!(
                field.kind,
                FieldKind::Text
                    | FieldKind::Secret
                    | FieldKind::Number
                    | FieldKind::ReasoningMap
                    | FieldKind::ReasoningDefault
            )
            && field.value.graphemes(true).count() < MAX_FIELD_GRAPHEMES
        {
            insert_at_cursor(&mut field.value, &mut self.cursor, &ch.to_string());
        }
    }

    /// 删除当前字素而不是 UTF-8 字节，组合字与 Emoji 不会被破坏。
    fn backspace(&mut self) {
        let Some(field) = self.fields.get_mut(self.focus) else {
            return;
        };
        if self.cursor == 0 {
            return;
        }
        let start = grapheme_byte_index(&field.value, self.cursor - 1);
        let end = grapheme_byte_index(&field.value, self.cursor);
        field.value.replace_range(start..end, "");
        self.cursor -= 1;
    }

    /// Delete 删除光标下一个完整字素；空字段保持不变。
    fn delete(&mut self) {
        let Some(field) = self.fields.get_mut(self.focus) else {
            return;
        };
        let length = field.value.graphemes(true).count();
        if self.cursor >= length {
            return;
        }
        let start = grapheme_byte_index(&field.value, self.cursor);
        let end = grapheme_byte_index(&field.value, self.cursor + 1);
        field.value.replace_range(start..end, "");
    }

    /// 验证只针对用户可编辑字段并给出就地纠正信息，默认值取当前桌面契约。
    fn validate(&self) -> Result<(), FormValidationError> {
        let get = |key| {
            self.fields
                .iter()
                .find(|field| field.key == key)
                .map(|field| field.value.trim())
        };
        if self.target == FormTarget::Provider || self.target == FormTarget::NewProvider {
            if get("name").is_none_or(str::is_empty) {
                return Err(validation_error("name", "供应商名称不能为空"));
            }
            let base_url = get("base_url").unwrap_or_default();
            if base_url.is_empty() || text_units(base_url) > 2_048 || !safe_http_url(base_url) {
                return Err(validation_error(
                    "base_url",
                    "Base URL 必须是有效的 HTTP 或 HTTPS 地址",
                ));
            }
            if get("name").is_some_and(|name| text_units(name) > MAX_TEXT_LENGTH) {
                return Err(validation_error("name", "供应商名称不能超过 512 个字符"));
            }
            let new_provider = self.target == FormTarget::NewProvider;
            let secret = self
                .fields
                .iter()
                .find(|field| field.key == "credential")
                .map(|field| field.value.as_str())
                .unwrap_or("");
            if new_provider && secret.trim().is_empty() {
                return Err(validation_error("credential", "新增供应商需要填写 API Key"));
            }
            if get("connect_timeout_ms").is_some_and(|value| {
                value
                    .parse::<u64>()
                    .map_or(true, |value| !(100..=120_000).contains(&value))
            }) {
                return Err(validation_error(
                    "connect_timeout_ms",
                    "连接超时必须在 100 到 120,000 毫秒之间",
                ));
            }
            if get("request_timeout_ms").is_some_and(|value| {
                value
                    .parse::<u64>()
                    .map_or(true, |value| !(1_000..=3_600_000).contains(&value))
            }) {
                return Err(validation_error(
                    "request_timeout_ms",
                    "请求超时必须在 1,000 到 3,600,000 毫秒之间",
                ));
            }
        }
        if self.model_id.is_some() {
            let upstream = get("model").unwrap_or_default();
            if upstream.trim().is_empty() {
                return Err(validation_error("model", "真实上游模型标识不能为空"));
            }
            if text_units(upstream.trim()) > MAX_TEXT_LENGTH {
                return Err(validation_error(
                    "model",
                    "真实上游模型标识不能超过 512 个字符",
                ));
            }
            if get("model_name")
                .is_some_and(|name| !name.is_empty() && text_units(name) > MAX_TEXT_LENGTH)
            {
                return Err(validation_error(
                    "model_name",
                    "模型别名不能超过 512 个字符",
                ));
            }
            let context = get("context_window_tokens")
                .and_then(|value| value.parse::<u64>().ok())
                .unwrap_or_default();
            let output = get("max_output_tokens")
                .and_then(|value| value.parse::<u64>().ok())
                .unwrap_or_default();
            if !(4_096..=4_000_000).contains(&context) {
                return Err(validation_error(
                    "context_window_tokens",
                    "上下文 Tokens 必须在 4,096 到 4,000,000 之间",
                ));
            }
            if !(1..=1_000_000).contains(&output) || output >= context {
                return Err(validation_error(
                    "max_output_tokens",
                    "单次输出 Tokens 必须在 1 到 1,000,000 之间且小于上下文上限",
                ));
            }
            if let Some(value) = get("default_reasoning_level")
                && !value.is_empty()
                && value != "模型默认"
                && !REASONING_LEVELS.contains(&value)
            {
                return Err(validation_error(
                    "default_reasoning_level",
                    "默认推理等级不受支持",
                ));
            }
            let map = get("reasoning_level_map").unwrap_or_default();
            let parsed = parse_reasoning_map(map).map_err(|message| FormValidationError {
                field_key: "reasoning_level_map",
                message,
            })?;
            if let Some(default) = get("default_reasoning_level")
                && !default.is_empty()
                && default != "模型默认"
                && !parsed.contains_key(default)
            {
                return Err(validation_error(
                    "default_reasoning_level",
                    "默认推理等级必须属于当前模型已配置的等级",
                ));
            }
        }
        Ok(())
    }

    /// 将字段提交回本地文档；身份字段永远只作为 map key，不重复写进实体 patch value。
    fn apply_to(&self, document: &mut Value) {
        let provider_was_new = find_provider(Some(document), &self.provider_id).is_none();
        let model_was_new = self.model_id.as_deref().is_some_and(|model_id| {
            find_model(Some(document), &self.provider_id, model_id).is_none()
        });
        if self.target == FormTarget::NewProvider && provider_was_new {
            let mut provider = default_provider(&self.provider_id, &self.credential_id);
            if let Some(model_id) = self.model_id.as_deref() {
                provider["models"] = json!([default_model(model_id)]);
            }
            if !document.get("providers").is_some_and(Value::is_array) {
                document["providers"] = Value::Array(Vec::new());
            }
            if let Some(providers) = document.get_mut("providers").and_then(Value::as_array_mut) {
                providers.push(provider);
            }
        }
        if self.target == FormTarget::NewModel
            && model_was_new
            && let (Some(model_id), Some(provider)) = (
                self.model_id.as_deref(),
                find_provider_mut(document, &self.provider_id),
            )
            && let Some(models) = provider.get_mut("models").and_then(Value::as_array_mut)
        {
            models.push(default_model(model_id));
        }
        let provider_fields =
            self.target == FormTarget::Provider || self.target == FormTarget::NewProvider;
        if provider_fields && let Some(provider) = find_provider_mut(document, &self.provider_id) {
            for (index, field) in self.fields.iter().enumerate() {
                if !provider_was_new
                    && self
                        .initial_fields
                        .get(index)
                        .is_some_and(|initial| initial == &field.value)
                {
                    continue;
                }
                match field.key {
                    "name" => provider["name"] = Value::String(field.value.trim().to_owned()),
                    "api" => provider["api"] = Value::String(field.value.clone()),
                    "base_url" => {
                        provider["base_url"] = Value::String(field.value.trim().to_owned())
                    }
                    "credential" if !field.value.is_empty() => {
                        provider["credential_id"] = Value::String(self.credential_id.clone())
                    }
                    "connect_timeout_ms" => {
                        provider["network_timeouts"]["connect_timeout_ms"] =
                            number_value(&field.value, DEFAULT_CONNECT_TIMEOUT_MS)
                    }
                    "request_timeout_ms" => {
                        provider["network_timeouts"]["request_timeout_ms"] =
                            number_value(&field.value, DEFAULT_REQUEST_TIMEOUT_MS)
                    }
                    "auto_compact" => {
                        provider["agent_defaults"]["context"]["auto_compact"] =
                            Value::Bool(field.value == "true")
                    }
                    _ => {}
                }
            }
        }
        if let Some(model_id) = self.model_id.as_deref()
            && let Some(model) = find_model_mut(document, &self.provider_id, model_id)
        {
            for (index, field) in self.fields.iter().enumerate() {
                if !model_was_new
                    && self
                        .initial_fields
                        .get(index)
                        .is_some_and(|initial| initial == &field.value)
                {
                    continue;
                }
                match field.key {
                    "model" => model["model"] = Value::String(field.value.trim().to_owned()),
                    "model_name" => model["name"] = Value::String(field.value.trim().to_owned()),
                    "context_window_tokens" => {
                        model["capabilities"]["context_window_tokens"] =
                            number_value(&field.value, DEFAULT_CONTEXT_WINDOW)
                    }
                    "max_output_tokens" => {
                        model["capabilities"]["max_output_tokens"] =
                            number_value(&field.value, DEFAULT_MAX_OUTPUT)
                    }
                    "supports_output_schema" => {
                        model["capabilities"]["supports_output_schema"] =
                            Value::Bool(field.value == "true")
                    }
                    "reasoning_level_map" => {
                        model["reasoning_level_map"] = serde_json::to_value(
                            parse_reasoning_map(&field.value).unwrap_or_default(),
                        )
                        .unwrap_or_else(|_| json!({}))
                    }
                    "default_reasoning_level" => {
                        model["default_reasoning_level"] =
                            if field.value.is_empty() || field.value == "模型默认" {
                                Value::Null
                            } else {
                                Value::String(field.value.clone())
                            }
                    }
                    _ => {}
                }
            }
            let model_name_index = self
                .fields
                .iter()
                .position(|field| field.key == "model_name");
            if model_was_new
                || model_name_index.is_some_and(|index| {
                    self.initial_fields.get(index)
                        != self.fields.get(index).map(|field| &field.value)
                })
            {
                let model_name = self
                    .fields
                    .iter()
                    .find(|field| field.key == "model_name")
                    .map(|field| field.value.trim())
                    .unwrap_or("");
                let upstream = self
                    .fields
                    .iter()
                    .find(|field| field.key == "model")
                    .map(|field| field.value.trim())
                    .unwrap_or("");
                model["name"] = Value::String(
                    if model_name.is_empty() {
                        upstream
                    } else {
                        model_name
                    }
                    .to_owned(),
                );
            }
        }
    }

    /// 表单焦点映射到折叠后的可见索引，scroll 只按可见字段计算。
    fn visible_field_index(&self, index: usize) -> Option<usize> {
        self.fields
            .iter()
            .take(index + 1)
            .filter(|field| self.advanced || !field.advanced)
            .count()
            .checked_sub(1)
    }
}

/// 将字段级校验信息绑定到稳定字段键，使错误提示与键盘焦点保持一致。
fn validation_error(field_key: &'static str, message: &str) -> FormValidationError {
    FormValidationError {
        field_key,
        message: message.to_owned(),
    }
}

impl Drop for ModelSettingsState {
    /// 任何关闭路径都清空仍在表单或离开确认中的 Secret，避免依赖调用方记住清理顺序。
    fn drop(&mut self) {
        self.clear_form_secret();
    }
}

enum ConfirmAction {
    Stay,
    Cancel,
    Confirm,
}

/// 两项确认列表共用同一键盘语义，Esc 始终走安全取消分支。
fn confirmation_key(key: KeyEvent, selected: &mut usize) -> ConfirmAction {
    match key.code {
        KeyCode::Up | KeyCode::Down => *selected = 1 - *selected,
        KeyCode::Enter if *selected == 0 => return ConfirmAction::Confirm,
        KeyCode::Enter => return ConfirmAction::Cancel,
        KeyCode::Esc => return ConfirmAction::Cancel,
        _ => {}
    }
    ConfirmAction::Stay
}

/// 只清理 Secret 字段；其它草稿值保留给界面返回路径继续编辑。
fn clear_form_secret(form: &mut SettingsForm) {
    for (index, field) in form.fields.iter_mut().enumerate() {
        if matches!(field.kind, FieldKind::Secret) {
            wipe_secret(&mut field.value);
            if let Some(initial) = form.initial_fields.get_mut(index) {
                wipe_secret(initial);
            }
        }
    }
}

/// 退出表单或凭据请求后覆盖当前 Secret 字节并释放缓冲，不保留已清空字符串的容量内容。
fn wipe_secret(value: &mut String) {
    let mut bytes = std::mem::take(value).into_bytes();
    bytes.fill(0);
}

/// 信任确认的 Esc 与拒绝选项都会返回 false，不触碰 App Server 的信任存储。
fn trust_key(
    key: KeyEvent,
    cwd: &str,
    selected: &mut usize,
    _return_to_settings: bool,
) -> ModelSettingsEffect {
    match key.code {
        KeyCode::Up | KeyCode::Down => {
            *selected = 1 - *selected;
            ModelSettingsEffect::None
        }
        KeyCode::Enter => ModelSettingsEffect::Trust {
            trusted: *selected == 0,
        },
        KeyCode::Esc => ModelSettingsEffect::Trust { trusted: false },
        _ => {
            let _ = cwd;
            ModelSettingsEffect::None
        }
    }
}

/// 根据窗口宽高绘制限域滚动面板；关键操作保留在最末行，Secret 只绘制固定掩码。
pub(super) fn render(frame: &mut Frame<'_>, area: Rect, view: &ModelSettingsView) {
    if area.width == 0 || area.height == 0 {
        return;
    }
    frame.render_widget(Clear, area);
    let outer = Block::default()
        .borders(Borders::ALL)
        .title(view.title.as_str())
        .border_style(Style::default().fg(Color::LightBlue));
    let inner = outer.inner(area);
    frame.render_widget(outer, area);
    if inner.width == 0 || inner.height == 0 {
        return;
    }
    let requested_footer = view.footer.lines().count().clamp(1, 2) as u16;
    let footer_height = if inner.height >= 2 {
        requested_footer.min(inner.height.saturating_sub(1))
    } else {
        0
    };
    let header_height = u16::from(inner.height >= 3);
    let has_message = view.error.is_some() || view.status.is_some();
    let message_height = u16::from(
        has_message
            && inner.height
                >= header_height
                    .saturating_add(footer_height)
                    .saturating_add(2),
    );
    let chunks = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(header_height),
            Constraint::Length(message_height),
            Constraint::Min(0),
            Constraint::Length(footer_height),
        ])
        .split(inner);
    if header_height > 0 {
        frame.render_widget(
            Paragraph::new(truncate(&view.subtitle, chunks[0].width as usize))
                .style(Style::default().fg(Color::DarkGray)),
            chunks[0],
        );
    }
    if message_height > 0
        && let Some(message) = view.error.as_deref().or(view.status.as_deref())
    {
        let style = if view.error.is_some() {
            Style::default().fg(Color::LightRed)
        } else {
            Style::default().fg(Color::DarkGray)
        };
        frame.render_widget(
            Paragraph::new(truncate(message, chunks[1].width as usize)).style(style),
            chunks[1],
        );
    }
    let body = chunks[2];
    if view.form {
        render_form(frame, body, view);
    } else {
        render_rows(frame, body, view);
    }
    if footer_height > 0 {
        let lines = view
            .footer
            .lines()
            .take(footer_height as usize)
            .map(|line| Line::from(truncate(line, chunks[3].width as usize)))
            .collect::<Vec<_>>();
        frame.render_widget(
            Paragraph::new(Text::from(lines)).style(Style::default().fg(Color::LightBlue)),
            chunks[3],
        );
    }
}

/// 设置行根据目标窗口滚动，选中行突出显示而次级说明使用弱化色。
fn render_rows(frame: &mut Frame<'_>, area: Rect, view: &ModelSettingsView) {
    if area.height == 0 {
        return;
    }
    if view.rows.is_empty() {
        frame.render_widget(
            Paragraph::new(if view.loading {
                "读取中…"
            } else {
                "暂无可显示配置"
            })
            .style(Style::default().fg(Color::DarkGray)),
            area,
        );
        return;
    }
    if view.wrap_rows {
        render_wrapped_confirmation_rows(frame, area, view);
        return;
    }
    let row_height = usize::from(area.height.max(1));
    let start = view
        .selected
        .saturating_sub(row_height.saturating_sub(1))
        .max(view.scroll)
        .min(view.rows.len().saturating_sub(row_height));
    let lines = view
        .rows
        .iter()
        .enumerate()
        .skip(start)
        .take(row_height)
        .map(|(index, (label, detail, selected))| {
            let marker = if *selected { "› " } else { "  " };
            let available = usize::from(area.width).saturating_sub(2);
            let label_width =
                UnicodeWidthStr::width(label.as_str()).min(available.saturating_mul(2) / 3);
            let mut spans = vec![Span::styled(marker, Style::default().fg(Color::LightBlue))];
            spans.push(Span::styled(
                truncate(label, label_width),
                if *selected {
                    Style::default()
                        .fg(Color::LightBlue)
                        .add_modifier(Modifier::BOLD)
                } else {
                    Style::default()
                },
            ));
            if !detail.is_empty() && available > label_width + 2 {
                spans.push(Span::raw("  "));
                spans.push(Span::styled(
                    truncate(detail, available.saturating_sub(label_width + 2)),
                    Style::default().fg(Color::DarkGray),
                ));
            }
            let _ = index;
            Line::from(spans)
        })
        .collect::<Vec<_>>();
    frame.render_widget(
        Paragraph::new(Text::from(lines)).wrap(Wrap { trim: true }),
        area,
    );
}

/// 确认页按终端列宽逐行折叠目标与影响，并以视觉行滚动避免窄窗截掉危险操作说明。
fn render_wrapped_confirmation_rows(frame: &mut Frame<'_>, area: Rect, view: &ModelSettingsView) {
    if area.height == 0 || area.width == 0 {
        return;
    }
    let width = usize::from(area.width).saturating_sub(2).max(1);
    let mut lines = Vec::new();
    let mut group_starts = Vec::with_capacity(view.rows.len());
    for (label, detail, selected) in &view.rows {
        group_starts.push(lines.len());
        let label_style = if *selected {
            Style::default()
                .fg(Color::LightBlue)
                .add_modifier(Modifier::BOLD)
        } else {
            Style::default()
        };
        let label_lines = wrap_display_text(label, width);
        for (index, line) in label_lines.iter().enumerate() {
            let marker = if index == 0 && *selected {
                "› "
            } else {
                "  "
            };
            lines.push(Line::from(vec![
                Span::styled(marker, Style::default().fg(Color::LightBlue)),
                Span::styled(line.clone(), label_style),
            ]));
        }
        if !detail.is_empty() {
            for line in wrap_display_text(detail, width) {
                lines.push(Line::from(vec![
                    Span::raw("  "),
                    Span::styled(line, Style::default().fg(Color::DarkGray)),
                ]));
            }
        }
    }
    let height = usize::from(area.height);
    let selected_start = group_starts.get(view.selected).copied().unwrap_or_default();
    let automatic_start = selected_start.saturating_sub(height.saturating_sub(1));
    let start = view
        .scroll
        .max(automatic_start)
        .min(lines.len().saturating_sub(height));
    frame.render_widget(
        Paragraph::new(Text::from(
            lines
                .into_iter()
                .skip(start)
                .take(height)
                .collect::<Vec<_>>(),
        )),
        area,
    );
}

/// 按 Unicode 显示列折行，宽字符与组合字素不会把后续字段挤出终端边界。
fn wrap_display_text(text: &str, width: usize) -> Vec<String> {
    let width = width.max(1);
    let mut lines = Vec::new();
    let mut line = String::new();
    let mut used = 0usize;
    for paragraph in text.split('\n') {
        for word in paragraph.split_whitespace() {
            let word_width = UnicodeWidthStr::width(word);
            let separator_width = usize::from(used > 0);
            if used > 0
                && used
                    .saturating_add(separator_width)
                    .saturating_add(word_width)
                    > width
            {
                lines.push(std::mem::take(&mut line));
                used = 0;
            }
            if word_width <= width {
                if used > 0 {
                    line.push(' ');
                    used += 1;
                }
                line.push_str(word);
                used += word_width;
                continue;
            }
            if used > 0 {
                lines.push(std::mem::take(&mut line));
                used = 0;
            }
            for grapheme in word.graphemes(true) {
                let grapheme_width = UnicodeWidthStr::width(grapheme);
                if used > 0 && used.saturating_add(grapheme_width) > width {
                    lines.push(std::mem::take(&mut line));
                    used = 0;
                }
                line.push_str(grapheme);
                used = used.saturating_add(grapheme_width);
            }
        }
        lines.push(std::mem::take(&mut line));
        used = 0;
    }
    if lines.last().is_some_and(String::is_empty) && lines.len() > 1 {
        lines.pop();
    }
    if lines.is_empty() {
        lines.push(String::new());
    }
    lines
}

/// 表单只绘制当前可见字段，窄窗保留末行操作提示并用箭头标出键盘焦点。
fn render_form(frame: &mut Frame<'_>, area: Rect, view: &ModelSettingsView) {
    if area.height == 0 {
        return;
    }
    if view.fields.is_empty() {
        frame.render_widget(
            Paragraph::new("没有可编辑字段").style(Style::default().fg(Color::DarkGray)),
            area,
        );
        return;
    }
    let height = area.height as usize;
    let start = view
        .selected
        .saturating_sub(height.saturating_sub(1))
        .max(view.scroll)
        .min(view.fields.len().saturating_sub(height));
    let mut cursor_position = None;
    let lines = view
        .fields
        .iter()
        .enumerate()
        .skip(start)
        .take(height)
        .map(|(index, field)| {
            let marker = if field.selected { "› " } else { "  " };
            let label_column = 18.min(area.width.saturating_sub(4) as usize);
            let label = truncate(&field.label, label_column);
            let label_width = UnicodeWidthStr::width(label.as_str());
            let value_room = usize::from(area.width).saturating_sub(label_column + 4);
            let value = if field.secret && field.value != "未更换" {
                "••••••••"
            } else {
                field.value.as_str()
            };
            let (shown_value, cursor_column) = if field.selected {
                input_window(value, value_room, field.cursor.unwrap_or_default())
            } else {
                (truncate(value, value_room), 0)
            };
            if field.selected && field.cursor.is_some() && area.width > 0 {
                let x = area
                    .x
                    .saturating_add(4)
                    .saturating_add(label_column.min(u16::MAX as usize) as u16)
                    .saturating_add(cursor_column.min(u16::MAX as usize) as u16)
                    .min(area.x.saturating_add(area.width.saturating_sub(1)));
                let y = area
                    .y
                    .saturating_add(index.saturating_sub(start).min(u16::MAX as usize) as u16);
                cursor_position = Some(Position::new(x, y));
            }
            let mut spans = vec![
                Span::styled(marker, Style::default().fg(Color::LightBlue)),
                Span::styled(label, Style::default().fg(Color::DarkGray)),
                Span::raw(format!(
                    "{}  ",
                    " ".repeat(label_column.saturating_sub(label_width))
                )),
            ];
            spans.push(Span::styled(
                shown_value,
                if field.selected {
                    Style::default()
                        .fg(Color::LightCyan)
                        .add_modifier(Modifier::BOLD)
                } else {
                    Style::default().fg(Color::Reset)
                },
            ));
            Line::from(spans)
        })
        .collect::<Vec<_>>();
    frame.render_widget(
        Paragraph::new(Text::from(lines)).wrap(Wrap { trim: false }),
        area,
    );
    if let Some(position) = cursor_position {
        frame.set_cursor_position(position);
    }
}

/// 选中字段按终端列宽横向滚动并始终让字素光标落在可见窗口内，长 URL 仍可编辑。
fn input_window(value: &str, width: usize, cursor: usize) -> (String, usize) {
    if width == 0 {
        return (String::new(), 0);
    }
    let graphemes = value.graphemes(true).collect::<Vec<_>>();
    let cursor = cursor.min(graphemes.len());
    let mut start = cursor;
    let mut before_width = 0usize;
    let mut budget = width.saturating_sub(1);
    while start > 0 {
        let next_width = UnicodeWidthStr::width(graphemes[start - 1]);
        if before_width.saturating_add(next_width) > budget {
            break;
        }
        before_width += next_width;
        start -= 1;
    }
    if start > 0 && width > 1 {
        budget = width.saturating_sub(2);
        while before_width > budget && start < cursor {
            before_width = before_width.saturating_sub(UnicodeWidthStr::width(graphemes[start]));
            start += 1;
        }
    }
    let left_marker = usize::from(start > 0 && width > 1);
    let mut output = String::new();
    if left_marker > 0 {
        output.push('…');
    }
    for grapheme in &graphemes[start..cursor] {
        output.push_str(grapheme);
    }
    let cursor_column = left_marker
        .saturating_add(before_width)
        .min(width.saturating_sub(1));
    let mut used = left_marker.saturating_add(before_width);
    let mut end = cursor;
    while end < graphemes.len() {
        let grapheme_width = UnicodeWidthStr::width(graphemes[end]);
        if used.saturating_add(grapheme_width) > width {
            break;
        }
        output.push_str(graphemes[end]);
        used += grapheme_width;
        end += 1;
    }
    if end < graphemes.len() && used < width {
        output.push('…');
    }
    (output, cursor_column)
}

/// 新增 Provider 使用当前配置默认值；能力默认保守且不根据模型名推断。
fn default_provider(provider_id: &str, credential_id: &str) -> Value {
    json!({
        "provider_id": provider_id,
        "name": "新供应商",
        "api": API_SPECS[0],
        "base_url": "",
        "credential_id": credential_id,
        "network_timeouts": {"connect_timeout_ms": DEFAULT_CONNECT_TIMEOUT_MS, "request_timeout_ms": DEFAULT_REQUEST_TIMEOUT_MS},
        "agent_defaults": {"context": {"auto_compact": true}},
        "models": []
    })
}

/// 新模型沿用 App Server 首次配置默认预算，能力仅按用户明确编辑的布尔值保存。
fn default_model(model_id: &str) -> Value {
    json!({
        "model_id": model_id,
        "name": "",
        "model": "",
        "capabilities": {
            "context_window_tokens": DEFAULT_CONTEXT_WINDOW,
            "max_output_tokens": DEFAULT_MAX_OUTPUT,
            "supports_output_schema": false
        },
        "reasoning_level_map": {},
        "default_reasoning_level": null
    })
}

/// 根据已有模型生成表单行；缺失能力声明保持 false，不从上游名称或协议推理。
fn new_model_fields(existing: Option<&Value>, _model_id: &str) -> Vec<FormField> {
    let map_text = existing
        .and_then(|model| model.get("reasoning_level_map"))
        .map(format_reasoning_map)
        .unwrap_or_default();
    vec![
        text_field(
            "model",
            "真实上游模型 ID",
            existing
                .and_then(|model| model.get("model"))
                .map(value_string)
                .unwrap_or_default(),
            false,
        ),
        text_field(
            "model_name",
            "显示别名（可选）",
            existing
                .and_then(|model| model.get("name"))
                .map(value_string)
                .unwrap_or_default(),
            false,
        ),
        number_field(
            "context_window_tokens",
            "上下文 Tokens",
            existing
                .and_then(|model| model.pointer("/capabilities/context_window_tokens"))
                .and_then(Value::as_u64)
                .unwrap_or(DEFAULT_CONTEXT_WINDOW),
            true,
        ),
        number_field(
            "max_output_tokens",
            "单次最大输出 Tokens",
            existing
                .and_then(|model| model.pointer("/capabilities/max_output_tokens"))
                .and_then(Value::as_u64)
                .unwrap_or(DEFAULT_MAX_OUTPUT),
            true,
        ),
        boolean_field(
            "supports_output_schema",
            "支持 JSON Schema 输出",
            existing
                .and_then(|model| model.pointer("/capabilities/supports_output_schema"))
                .and_then(Value::as_bool)
                .unwrap_or(false),
            true,
        ),
        FormField {
            key: "reasoning_level_map",
            label: "推理等级映射 (level=value, …)",
            value: map_text,
            kind: FieldKind::ReasoningMap,
            advanced: true,
        },
        FormField {
            key: "default_reasoning_level",
            label: "默认推理等级",
            value: existing
                .and_then(|model| model.get("default_reasoning_level"))
                .and_then(Value::as_str)
                .unwrap_or("模型默认")
                .to_owned(),
            kind: FieldKind::ReasoningDefault,
            advanced: true,
        },
    ]
}

/// 可空业务值使用 JA-RPC 哨兵显式保留 JSON null，避免 Merge Patch 将其解释为删键。
fn nullable_patch_value(value: &Value) -> Value {
    if value.is_null() {
        json!({"__ja_null": true})
    } else {
        value.clone()
    }
}

/// 冲突合并将 JA-RPC 的显式 null 哨兵还原为文档中的真实 null 值。
fn document_value_from_patch(value: &Value) -> Value {
    if value == &json!({"__ja_null": true}) {
        Value::Null
    } else {
        value.clone()
    }
}

/// RFC 7396 字段差量与稳定 ID entity map 一起生成，绝不替换完整 providers 数组。
fn configuration_patch(baseline: &Value, draft: &Value) -> Value {
    let mut patch = Map::new();
    for field in [
        "default_provider_id",
        "default_model_id",
        "default_reasoning_level",
    ] {
        if baseline.get(field) != draft.get(field) {
            patch.insert(
                field.to_owned(),
                nullable_patch_value(draft.get(field).unwrap_or(&Value::Null)),
            );
        }
    }
    let before = provider_map(baseline);
    let after = provider_map(draft);
    let mut provider_changes = Map::new();
    for provider_id in before.keys().chain(after.keys()).collect::<BTreeSet<_>>() {
        match (before.get(provider_id), after.get(provider_id)) {
            (Some(_), None) => {
                provider_changes.insert(provider_id.to_string(), Value::Null);
            }
            (None, Some(provider)) => {
                provider_changes.insert(
                    provider_id.to_string(),
                    provider_patch(None, Some(provider)),
                );
            }
            (Some(old), Some(new)) => {
                let changes = provider_patch(Some(old), Some(new));
                if changes.as_object().is_some_and(|object| !object.is_empty()) {
                    provider_changes.insert(provider_id.to_string(), changes);
                }
            }
            (None, None) => {}
        }
    }
    if !provider_changes.is_empty() {
        patch.insert("providers".to_owned(), Value::Object(provider_changes));
    }
    let before_order = provider_ids(baseline);
    let after_order = provider_ids(draft);
    if relative_order_changed(&before_order, &after_order) {
        patch.insert("provider_order".to_owned(), json!(after_order));
    }
    for provider_id in after_order {
        let old = before.get(&provider_id);
        let new = after.get(&provider_id);
        let old_order = old.map(|provider| model_ids(provider)).unwrap_or_default();
        let new_order = new.map(|provider| model_ids(provider)).unwrap_or_default();
        if relative_order_changed(&old_order, &new_order) {
            let providers_patch = patch
                .entry("providers")
                .or_insert_with(|| Value::Object(Map::new()));
            let Some(provider_patch) = providers_patch.as_object_mut() else {
                continue;
            };
            let provider_changes = provider_patch
                .entry(provider_id)
                .or_insert_with(|| Value::Object(Map::new()));
            if let Some(provider_changes) = provider_changes.as_object_mut() {
                provider_changes.insert("model_order".to_owned(), json!(new_order));
            }
        }
    }
    let before_subagents = baseline.get("subagents");
    let after_subagents = draft.get("subagents");
    let mut subagent_changes = Map::new();
    for field in ["provider_id", "model_id", "reasoning_level"] {
        if before_subagents.and_then(|value| value.get(field))
            != after_subagents.and_then(|value| value.get(field))
        {
            subagent_changes.insert(
                field.to_owned(),
                nullable_patch_value(
                    after_subagents
                        .and_then(|value| value.get(field))
                        .unwrap_or(&Value::Null),
                ),
            );
        }
    }
    if !subagent_changes.is_empty() {
        patch.insert("subagents".to_owned(), Value::Object(subagent_changes));
    }
    Value::Object(patch)
}

/// Provider patch 只包含变化字段，内层 models 始终使用 ID keyed 操作。
fn provider_patch(old: Option<&Value>, new: Option<&Value>) -> Value {
    let Some(new) = new else {
        return Value::Null;
    };
    let mut patch = Map::new();
    for field in ["name", "api", "base_url", "credential_id"] {
        if old.and_then(|provider| provider.get(field)) != new.get(field) {
            patch.insert(
                field.to_owned(),
                new.get(field).cloned().unwrap_or(Value::Null),
            );
        }
    }
    let mut timeout_changes = Map::new();
    for field in ["connect_timeout_ms", "request_timeout_ms"] {
        if old.and_then(|provider| provider.pointer(&format!("/network_timeouts/{field}")))
            != new.pointer(&format!("/network_timeouts/{field}"))
        {
            timeout_changes.insert(
                field.to_owned(),
                new.pointer(&format!("/network_timeouts/{field}"))
                    .cloned()
                    .unwrap_or(Value::Null),
            );
        }
    }
    if !timeout_changes.is_empty() {
        patch.insert(
            "network_timeouts".to_owned(),
            Value::Object(timeout_changes),
        );
    }
    if old.and_then(|provider| provider.pointer("/agent_defaults/context/auto_compact"))
        != new.pointer("/agent_defaults/context/auto_compact")
    {
        patch.insert("agent_defaults".to_owned(), json!({"context": {"auto_compact": new.pointer("/agent_defaults/context/auto_compact").cloned().unwrap_or(Value::Null)}}));
    }
    let old_models = old.map(model_map).unwrap_or_default();
    let new_models = model_map(new);
    let mut model_changes = Map::new();
    for model_id in old_models
        .keys()
        .chain(new_models.keys())
        .collect::<BTreeSet<_>>()
    {
        match (old_models.get(model_id), new_models.get(model_id)) {
            (Some(_), None) => {
                model_changes.insert(model_id.to_string(), Value::Null);
            }
            (None, Some(model)) => {
                model_changes.insert(model_id.to_string(), model_fields(None, Some(model)));
            }
            (Some(old), Some(model)) => {
                let changes = model_fields(Some(old), Some(model));
                if changes.as_object().is_some_and(|object| !object.is_empty()) {
                    model_changes.insert(model_id.to_string(), changes);
                }
            }
            (None, None) => {}
        }
    }
    if !model_changes.is_empty() {
        patch.insert("models".to_owned(), Value::Object(model_changes));
    }
    Value::Object(patch)
}

/// Model IDs are entity-map keys; patch values contain only editable model fields.
fn model_fields(old: Option<&Value>, new: Option<&Value>) -> Value {
    let Some(new) = new else {
        return Value::Null;
    };
    let mut patch = Map::new();
    for field in [
        "name",
        "model",
        "reasoning_level_map",
        "default_reasoning_level",
    ] {
        if old.and_then(|model| model.get(field)) != new.get(field) {
            patch.insert(
                field.to_owned(),
                if field == "default_reasoning_level" {
                    nullable_patch_value(new.get(field).unwrap_or(&Value::Null))
                } else {
                    new.get(field).cloned().unwrap_or(Value::Null)
                },
            );
        }
    }
    let mut capabilities = Map::new();
    for field in [
        "context_window_tokens",
        "max_output_tokens",
        "supports_output_schema",
    ] {
        let pointer = format!("/capabilities/{field}");
        if old.and_then(|model| model.pointer(&pointer)) != new.pointer(&pointer) {
            capabilities.insert(
                field.to_owned(),
                new.pointer(&pointer).cloned().unwrap_or(Value::Null),
            );
        }
    }
    if !capabilities.is_empty() {
        patch.insert("capabilities".to_owned(), Value::Object(capabilities));
    }
    Value::Object(patch)
}

/// 新增或删除实体不改变原有相对顺序，避免排序控制字段干扰未触及的配置项。
fn relative_order_changed(before: &[String], after: &[String]) -> bool {
    let before_common = before
        .iter()
        .filter(|id| after.contains(id))
        .collect::<Vec<_>>();
    let after_common = after
        .iter()
        .filter(|id| before.contains(id))
        .collect::<Vec<_>>();
    before_common != after_common
}

/// 冲突回读时把本地差量合并到最新文档，保留外部新增项和未编辑的未知字段。
fn apply_user_patch(document: &mut Value, patch: &Value) {
    let Some(changes) = patch.as_object() else {
        return;
    };
    for (key, value) in changes {
        match key.as_str() {
            "providers" => apply_provider_changes(document, value),
            "provider_order" => {
                if let Some(order) = string_list(value) {
                    reorder_entities(document, "providers", "provider_id", &order);
                }
            }
            "subagents" => assign_nullable_fields(
                document,
                "subagents",
                value,
                &["provider_id", "model_id", "reasoning_level"],
            ),
            "default_provider_id" | "default_model_id" | "default_reasoning_level" => {
                if let Some(object) = document.as_object_mut() {
                    object.insert(key.clone(), document_value_from_patch(value));
                }
            }
            _ => {}
        }
    }
}

/// keyed Provider 操作只触碰命中的 ID，null 删除、未知和畸形条目继续保留。
fn apply_provider_changes(document: &mut Value, changes: &Value) {
    let Some(changes) = changes.as_object() else {
        return;
    };
    if !document.get("providers").is_some_and(Value::is_array) {
        document["providers"] = Value::Array(Vec::new());
    }
    let Some(providers) = document.get_mut("providers").and_then(Value::as_array_mut) else {
        return;
    };
    for (provider_id, change) in changes {
        let index = providers
            .iter()
            .position(|provider| text(provider.get("provider_id")) == *provider_id);
        if change.is_null() {
            if let Some(index) = index {
                providers.remove(index);
            }
            continue;
        }
        let index = index.unwrap_or_else(|| {
            let mut provider = json!({"provider_id": provider_id, "models": []});
            if let Some(fields) = change.as_object() {
                for (field, value) in fields {
                    if !matches!(field.as_str(), "models" | "model_order") {
                        merge_value(&mut provider, field, value);
                    }
                }
            }
            providers.push(provider);
            providers.len() - 1
        });
        let Some(provider) = providers.get_mut(index) else {
            continue;
        };
        let Some(fields) = change.as_object() else {
            continue;
        };
        for (field, value) in fields {
            match field.as_str() {
                "models" => apply_model_changes(provider, value),
                "model_order" => {
                    if let Some(order) = string_list(value) {
                        reorder_entities(provider, "models", "model_id", &order);
                    }
                }
                _ => merge_value(provider, field, value),
            }
        }
    }
}

/// keyed Model 操作只改受影响模型字段，实体 ID 只写入最终用户文档而不进入 patch 值。
fn apply_model_changes(provider: &mut Value, changes: &Value) {
    let Some(changes) = changes.as_object() else {
        return;
    };
    if !provider.get("models").is_some_and(Value::is_array) {
        provider["models"] = Value::Array(Vec::new());
    }
    let Some(models) = provider.get_mut("models").and_then(Value::as_array_mut) else {
        return;
    };
    for (model_id, change) in changes {
        let index = models
            .iter()
            .position(|model| text(model.get("model_id")) == *model_id);
        if change.is_null() {
            if let Some(index) = index {
                models.remove(index);
            }
            continue;
        }
        let index = index.unwrap_or_else(|| {
            models.push(json!({"model_id": model_id}));
            models.len() - 1
        });
        if let Some(model) = models.get_mut(index) {
            apply_model_patch(model, change);
        }
    }
}

/// 可空引用的 null 是明确值，只有未出现在差量中的字段才保留原值。
fn assign_nullable_fields(document: &mut Value, key: &str, patch: &Value, fields: &[&str]) {
    let Some(patch) = patch.as_object() else {
        return;
    };
    if !document.get(key).is_some_and(Value::is_object) {
        document[key] = Value::Object(Map::new());
    }
    let Some(target) = document.get_mut(key).and_then(Value::as_object_mut) else {
        return;
    };
    for field in fields {
        if let Some(value) = patch.get(*field) {
            target.insert((*field).to_owned(), document_value_from_patch(value));
        }
    }
}

/// Nullable推理等级写回 null；可选输出能力的 null 则按可选键语义删除。
fn apply_model_patch(model: &mut Value, patch: &Value) {
    let Some(fields) = patch.as_object() else {
        merge_patch(model, patch);
        return;
    };
    for (field, value) in fields {
        match field.as_str() {
            "default_reasoning_level" => {
                if let Some(object) = model.as_object_mut() {
                    object.insert(field.clone(), document_value_from_patch(value));
                }
            }
            "capabilities" => {
                let Some(capabilities) =
                    model.get_mut("capabilities").and_then(Value::as_object_mut)
                else {
                    continue;
                };
                let Some(changes) = value.as_object() else {
                    continue;
                };
                for (key, value) in changes {
                    if value.is_null() {
                        capabilities.remove(key);
                    } else {
                        capabilities.insert(key.clone(), value.clone());
                    }
                }
            }
            _ => merge_value(model, field, value),
        }
    }
}

/// Merge Patch 式浅层递归保留嵌套对象中未触及字段，null 表示删除对应字段。
fn merge_patch(target: &mut Value, patch: &Value) {
    let Some(changes) = patch.as_object() else {
        *target = patch.clone();
        return;
    };
    if !target.is_object() {
        *target = Value::Object(Map::new());
    }
    let Some(target) = target.as_object_mut() else {
        return;
    };
    for (key, value) in changes {
        if value.is_null() {
            target.remove(key);
        } else if value.is_object() {
            merge_patch(
                target
                    .entry(key.clone())
                    .or_insert_with(|| Value::Object(Map::new())),
                value,
            );
        } else {
            target.insert(key.clone(), value.clone());
        }
    }
}

/// 将根或嵌套对象的单字段差量合并，不覆盖同对象里的未知键。
fn merge_value(target: &mut Value, key: &str, value: &Value) {
    if value.is_null() {
        if let Some(object) = target.as_object_mut() {
            object.remove(key);
        }
        return;
    }
    if !target.is_object() {
        *target = Value::Object(Map::new());
    }
    if let Some(object) = target.as_object_mut() {
        merge_patch(object.entry(key.to_owned()).or_insert(Value::Null), value);
    }
}

/// Provider 与 Model 顺序控制只重排明确列出的实体，未列出项继续占据原有相对位置。
fn reorder_entities(document: &mut Value, array_key: &str, id_key: &str, order: &[String]) {
    let Some(items) = document.get_mut(array_key).and_then(Value::as_array_mut) else {
        return;
    };
    let listed = order.iter().map(String::as_str).collect::<BTreeSet<_>>();
    let slots = items
        .iter()
        .enumerate()
        .filter_map(|(index, item)| {
            item.get(id_key)
                .and_then(Value::as_str)
                .filter(|id| listed.contains(id))
                .map(|_| index)
        })
        .collect::<Vec<_>>();
    let reordered = order
        .iter()
        .filter_map(|id| {
            items
                .iter()
                .find(|item| item.get(id_key).and_then(Value::as_str) == Some(id.as_str()))
                .cloned()
        })
        .collect::<Vec<_>>();
    for (slot, item) in slots.into_iter().zip(reordered) {
        items[slot] = item;
    }
}

/// 读取排序控制字段为 ID 列表，坏字段不参与本地重排。
fn string_list(value: &Value) -> Option<Vec<String>> {
    value.as_array().map(|items| {
        items
            .iter()
            .filter_map(Value::as_str)
            .map(str::to_owned)
            .collect()
    })
}

/// 从权威响应提取用户层文档；UI 只编辑 user document，不把项目 effective 投影写回。
fn user_document(configuration: &Value) -> Option<Value> {
    configuration
        .get("user")
        .and_then(|layer| layer.get("document"))
        .filter(|document| document.is_object())
        .cloned()
        .or_else(|| {
            configuration
                .get("schema_version")
                .and_then(Value::as_u64)
                .map(|_| configuration.clone())
        })
}

/// 构造只用于首次缺省展示/修复预览的完整 v2 用户文档。
fn default_document() -> Value {
    json!({
        "schema_version": 2, "config_revision": 0, "default_access_mode": "full_access",
        "interaction": {"clarification_enabled": true}, "default_provider_id": null,
        "default_model_id": null, "default_reasoning_level": null,
        "subagents": {"enabled": true, "provider_id": null, "model_id": null, "reasoning_level": null},
        "providers": [], "mcp_servers": [], "disabled_skills": []
    })
}

/// 按稳定身份索引有效 Provider；畸形条目不参与编辑或删除。
fn provider_map(document: &Value) -> BTreeMap<String, &Value> {
    providers(Some(document))
        .into_iter()
        .map(|provider| (text(provider.get("provider_id")), provider))
        .filter(|(id, _)| !id.is_empty())
        .collect()
}

/// 保留权威 provider 数组顺序供排序差量生成。
fn provider_ids(document: &Value) -> Vec<String> {
    providers(Some(document))
        .into_iter()
        .map(|provider| text(provider.get("provider_id")))
        .filter(|id| !id.is_empty())
        .collect()
}

/// 返回只包含 object 项的 Provider 视图，不替写入路径清除其它来源中的畸形内容。
fn providers(document: Option<&Value>) -> Vec<&Value> {
    document
        .and_then(|value| value.get("providers"))
        .and_then(Value::as_array)
        .map(|values| values.iter().filter(|value| value.is_object()).collect())
        .unwrap_or_default()
}

/// 返回 Provider 的有效模型列表。
fn models(provider: &Value) -> Vec<&Value> {
    provider
        .get("models")
        .and_then(Value::as_array)
        .map(|values| values.iter().filter(|value| value.is_object()).collect())
        .unwrap_or_default()
}

/// 按稳定 Model ID 建立 keyed patch 差量索引。
fn model_map(provider: &Value) -> BTreeMap<String, &Value> {
    models(provider)
        .into_iter()
        .map(|model| (text(model.get("model_id")), model))
        .filter(|(id, _)| !id.is_empty())
        .collect()
}

/// 返回 Provider 内模型顺序。
fn model_ids(provider: &Value) -> Vec<String> {
    models(provider)
        .into_iter()
        .map(|model| text(model.get("model_id")))
        .filter(|id| !id.is_empty())
        .collect()
}

/// 列表行 target 与显示顺序共用同一份模型快照，避免选择行和键盘动作错位。
fn row_targets(document: Option<&Value>) -> Vec<Option<SettingsRowTarget>> {
    let mut rows = Vec::new();
    for provider in providers(document) {
        let provider_id = text(provider.get("provider_id"));
        rows.push(Some(SettingsRowTarget::Provider(provider_id.clone())));
        for model in models(provider) {
            rows.push(Some(SettingsRowTarget::Model(ProviderModelRef {
                provider_id: provider_id.clone(),
                model_id: text(model.get("model_id")),
            })));
        }
    }
    if rows.is_empty() {
        rows.push(None);
    }
    rows
}

/// 根据实体 ID 查找模型，避免以显示别名作为保存身份。
fn find_model<'a>(
    document: Option<&'a Value>,
    provider_id: &str,
    model_id: &str,
) -> Option<&'a Value> {
    providers(document)
        .into_iter()
        .find(|provider| text(provider.get("provider_id")) == provider_id)
        .and_then(|provider| {
            models(provider)
                .into_iter()
                .find(|model| text(model.get("model_id")) == model_id)
        })
}

/// 根据稳定 Provider ID 查找表单源值。
fn find_provider<'a>(document: Option<&'a Value>, provider_id: &str) -> Option<&'a Value> {
    providers(document)
        .into_iter()
        .find(|provider| text(provider.get("provider_id")) == provider_id)
}

/// 仅在当前用户草稿上查找并修改 Provider。
fn find_provider_mut<'a>(document: &'a mut Value, provider_id: &str) -> Option<&'a mut Value> {
    document
        .get_mut("providers")?
        .as_array_mut()?
        .iter_mut()
        .find(|provider| text(provider.get("provider_id")) == provider_id)
}

/// 仅在当前用户草稿上查找并修改 Model。
fn find_model_mut<'a>(
    document: &'a mut Value,
    provider_id: &str,
    model_id: &str,
) -> Option<&'a mut Value> {
    find_provider_mut(document, provider_id)?
        .get_mut("models")?
        .as_array_mut()?
        .iter_mut()
        .find(|model| text(model.get("model_id")) == model_id)
}

/// 生成删除预览中的受影响模型身份。
fn target_model_refs(document: &Value, target: &SettingsRowTarget) -> Vec<ProviderModelRef> {
    match target {
        SettingsRowTarget::Model(reference) => vec![reference.clone()],
        SettingsRowTarget::Provider(provider_id) => find_provider(Some(document), provider_id)
            .map(|provider| {
                models(provider)
                    .into_iter()
                    .map(|model| ProviderModelRef {
                        provider_id: provider_id.clone(),
                        model_id: text(model.get("model_id")),
                    })
                    .collect()
            })
            .unwrap_or_default(),
    }
}

/// 以稳定模型身份枚举可替换默认项，不根据模型名称排序或推断优先级。
fn all_model_refs(document: &Value) -> Vec<ProviderModelRef> {
    providers(Some(document))
        .into_iter()
        .flat_map(|provider| {
            let provider_id = text(provider.get("provider_id"));
            models(provider)
                .into_iter()
                .map(move |model| ProviderModelRef {
                    provider_id: provider_id.clone(),
                    model_id: text(model.get("model_id")),
                })
        })
        .collect()
}

/// 删除确认显式检查全局子智能体模型引用。
fn target_referenced_by_subagents(document: &Value, target: &SettingsRowTarget) -> bool {
    let subagents = document.get("subagents");
    match target {
        SettingsRowTarget::Provider(provider_id) => {
            subagents
                .and_then(|value| value.get("provider_id"))
                .and_then(Value::as_str)
                == Some(provider_id)
        }
        SettingsRowTarget::Model(reference) => subagents.is_some_and(|value| {
            value.get("provider_id").and_then(Value::as_str) == Some(reference.provider_id.as_str())
                && value.get("model_id").and_then(Value::as_str)
                    == Some(reference.model_id.as_str())
        }),
    }
}

/// 当前行若为供应商则选择其首个模型用于连接验证；空供应商须先补充模型。
fn selected_model(rows: &[Option<SettingsRowTarget>], selected: usize) -> Option<(String, String)> {
    match rows.get(selected)?.as_ref()? {
        SettingsRowTarget::Model(reference) => {
            Some((reference.provider_id.clone(), reference.model_id.clone()))
        }
        SettingsRowTarget::Provider(_) => None,
    }
}

/// 从脱敏 credentials 映射读取 key 状态；未返回的 credential 一律按未配置展示。
fn credential_configured(configuration: Option<&Value>, credential_id: &str) -> bool {
    !credential_id.is_empty()
        && configuration
            .and_then(|value| value.get("credentials"))
            .and_then(|value| value.get(credential_id))
            .and_then(|value| value.get("configured"))
            .and_then(Value::as_bool)
            .unwrap_or(false)
}

/// 只统计与当前 Provider/Model 关联的脱敏问题，不把 issue 原始结构暴露给编辑表单。
fn issue_count(configuration: Option<&Value>, entity_id: &str) -> usize {
    configuration
        .and_then(|value| value.get("issues"))
        .and_then(Value::as_array)
        .map(|issues| {
            issues
                .iter()
                .filter(|issue| issue.get("entityId").and_then(Value::as_str) == Some(entity_id))
                .count()
        })
        .unwrap_or(0)
}

/// 读取 CAS opaque version；缺失版本由调用侧失败关闭。
fn cas_version(configuration: Option<&Value>, key: &str) -> Option<String> {
    configuration
        .and_then(|value| value.get("cas"))
        .and_then(|value| value.get(key))
        .and_then(Value::as_str)
        .map(str::to_owned)
}

/// 生成固定前缀的稳定实体 ID，避免显示名变更影响模型路由。
fn generated_id(prefix: &str) -> String {
    format!("{prefix}{}", Uuid::new_v4().simple())
}

/// 保留普通错误的首行并剔除控制字符，防止远端消息覆盖面板或注入终端控制序列。
fn safe_message(value: &str) -> String {
    value
        .lines()
        .next()
        .unwrap_or("操作失败")
        .chars()
        .filter(|ch| !ch.is_control())
        .take(240)
        .collect()
}

/// 清理标签控制字符并裁到显示安全上限。
fn safe_label(value: &str) -> String {
    value
        .chars()
        .filter(|ch| !ch.is_control())
        .take(256)
        .collect()
}

/// 转成稳定的简单文字值，缺字段显示空文本而非 JSON null。
fn text(value: Option<&Value>) -> String {
    value
        .and_then(Value::as_str)
        .map(str::to_owned)
        .unwrap_or_default()
}

/// 表单可编辑的 JSON 标量只显示原有值，不把对象序列化成难以理解的行。
fn value_string(value: &Value) -> String {
    value
        .as_str()
        .map(str::to_owned)
        .unwrap_or_else(|| value.to_string())
}

/// 配置契约按 JavaScript UTF-16 code unit 计长，避免中文长度与桌面验证结果不一致。
fn text_units(value: &str) -> usize {
    value.encode_utf16().count()
}

/// 单字段数值校验已先行完成；fallback 仅用于损坏快照的编辑显示。
fn number_value(value: &str, fallback: u64) -> Value {
    Value::Number(value.parse::<u64>().unwrap_or(fallback).into())
}

/// 创建普通文本表单字段。
fn text_field(key: &'static str, label: &'static str, value: String, advanced: bool) -> FormField {
    FormField {
        key,
        label,
        value,
        kind: FieldKind::Text,
        advanced,
    }
}

/// 创建有限协议枚举字段。
fn select_field(
    key: &'static str,
    label: &'static str,
    value: &str,
    kind: FieldKind,
    advanced: bool,
) -> FormField {
    FormField {
        key,
        label,
        value: value.to_owned(),
        kind,
        advanced,
    }
}

/// 创建 Token/超时等正整数配置字段。
fn number_field(key: &'static str, label: &'static str, value: u64, advanced: bool) -> FormField {
    FormField {
        key,
        label,
        value: value.to_string(),
        kind: FieldKind::Number,
        advanced,
    }
}

/// 创建 true/false 配置项。
fn boolean_field(key: &'static str, label: &'static str, value: bool, advanced: bool) -> FormField {
    FormField {
        key,
        label,
        value: value.to_string(),
        kind: FieldKind::Boolean,
        advanced,
    }
}

/// 以输入顺序解析已明确配置的推理映射。
fn parse_reasoning_map(value: &str) -> Result<BTreeMap<String, String>, String> {
    let mut parsed = BTreeMap::new();
    if value.trim().is_empty() {
        return Ok(parsed);
    }
    for part in value.split(',') {
        let Some((level, upstream)) = part.trim().split_once('=') else {
            return Err("推理映射格式为 level=value，并用逗号分隔".to_owned());
        };
        if !REASONING_LEVELS.contains(&level.trim())
            || upstream.trim().is_empty()
            || text_units(upstream.trim()) > 128
            || upstream.chars().any(char::is_control)
        {
            return Err(
                "推理映射仅接受 off/minimal/low/medium/high/xhigh/max 和非空上游值".to_owned(),
            );
        }
        parsed.insert(level.trim().to_owned(), upstream.trim().to_owned());
    }
    Ok(parsed)
}

/// 复用桌面安全 URL 的边界约束；CLI 不新增 URL 依赖，因此只接受明确的 HTTP(S) authority。
fn safe_http_url(value: &str) -> bool {
    let value = value.trim();
    let Some((scheme, remainder)) = value.split_once("://") else {
        return false;
    };
    if !(scheme.eq_ignore_ascii_case("http") || scheme.eq_ignore_ascii_case("https"))
        || value.chars().any(char::is_whitespace)
        || value.chars().any(char::is_control)
        || value
            .chars()
            .any(|character| matches!(character, '?' | '#' | '\\'))
    {
        return false;
    }
    let authority = remainder.split('/').next().unwrap_or_default();
    if authority.is_empty() || authority.contains('@') {
        return false;
    }
    let (host, port) = if let Some(bracketed) = authority.strip_prefix('[') {
        let Some((host, suffix)) = bracketed.split_once(']') else {
            return false;
        };
        if host.is_empty() || !host.contains(':') {
            return false;
        }
        if suffix.is_empty() {
            (host, None)
        } else if let Some(port) = suffix.strip_prefix(':') {
            (host, Some(port))
        } else {
            return false;
        }
    } else if let Some((host, port)) = authority.rsplit_once(':') {
        if host.contains(':') {
            return false;
        }
        (host, Some(port))
    } else {
        (authority, None)
    };
    if host.is_empty() {
        return false;
    }
    if let Some(port) = port
        && !port.is_empty()
        && port.parse::<u16>().is_err()
    {
        return false;
    }
    true
}

/// 将模型已有的推理映射转回表单语法。
fn format_reasoning_map(value: &Value) -> String {
    value
        .as_object()
        .map(|map| {
            REASONING_LEVELS
                .iter()
                .filter_map(|level| {
                    map.get(*level)
                        .and_then(Value::as_str)
                        .map(|upstream| format!("{level}={upstream}"))
                })
                .collect::<Vec<_>>()
                .join(",")
        })
        .unwrap_or_default()
}

/// 以字素光标安全插入内容并更新光标位置。
fn insert_at_cursor(value: &mut String, cursor: &mut usize, insertion: &str) {
    let byte_index = grapheme_byte_index(value, *cursor);
    value.insert_str(byte_index, insertion);
    *cursor += insertion.graphemes(true).count();
}

/// 将字素索引转换为 UTF-8 字节边界。
fn grapheme_byte_index(value: &str, index: usize) -> usize {
    value
        .grapheme_indices(true)
        .nth(index)
        .map(|(byte, _)| byte)
        .unwrap_or(value.len())
}

/// 有界枚举循环不允许空选项列表导致除零。
fn cycle_index(index: usize, count: usize, forward: bool) -> usize {
    if count == 0 {
        0
    } else if forward {
        (index + 1) % count
    } else {
        index.checked_sub(1).unwrap_or(count - 1)
    }
}

/// 删除行以稳定 ID 暗示影响对象，不从用户可编辑别名猜测实体引用。
fn delete_label(target: &SettingsRowTarget, document: Option<&Value>) -> String {
    match target {
        SettingsRowTarget::Provider(provider_id) => format!(
            "删除供应商 {}",
            find_provider(document, provider_id)
                .map(|provider| text(provider.get("name")))
                .unwrap_or_else(|| provider_id.clone())
        ),
        SettingsRowTarget::Model(reference) => {
            format!("删除模型 {}", model_label(document, reference))
        }
    }
}

/// 每个确认动作都展示完整默认与子智能体影响，避免选择时依赖其它行的说明。
fn delete_impact(action: &str, clears_subagents: bool) -> String {
    let mut parts = Vec::new();
    if !action.is_empty() {
        parts.push(action);
    }
    if clears_subagents {
        parts.push("子智能体跟随主任务");
    }
    if parts.is_empty() {
        parts.push("删除后可通过重新添加恢复");
    }
    parts.join(" · ")
}

/// 默认替代列表显示上游 ID。
fn model_label(document: Option<&Value>, reference: &ProviderModelRef) -> String {
    find_model(document, &reference.provider_id, &reference.model_id)
        .map(|model| text(model.get("model")))
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| reference.model_id.clone())
}

/// 追加一项短状态，避免列表说明格式分支重复。
fn push_detail(detail: &mut String, part: &str) {
    if !detail.is_empty() {
        detail.push_str(" · ");
    }
    detail.push_str(part);
}

/// 按终端显示列宽截断文本并保留省略标记。
fn truncate(value: &str, width: usize) -> String {
    if UnicodeWidthStr::width(value) <= width {
        return value.to_owned();
    }
    if width == 0 {
        return String::new();
    }
    let mut output = String::new();
    let mut used = 0usize;
    for grapheme in value.graphemes(true) {
        let cell_width = UnicodeWidthStr::width(grapheme);
        if used.saturating_add(cell_width) > width.saturating_sub(1) {
            break;
        }
        output.push_str(grapheme);
        used += cell_width;
    }
    output.push('…');
    output
}
