// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/// 当前 Thread 快照中仍未结束的上下文压缩；只保留状态行需要的身份与请求序号。
#[derive(Clone, Debug, Eq, PartialEq)]
pub(super) struct ContextCompactionStatus {
    pub(super) compaction_id: String,
    pub(super) turn_id: Option<String>,
    pub(super) retry_attempt: u64,
}

/// 压缩生命周期由 Thread 快照恢复，事件只推进同一 compaction 的即时请求次数。
#[derive(Default)]
pub(super) struct WorkActivity {
    compaction: Option<ContextCompactionStatus>,
}

impl WorkActivity {
    /// 事件只用于低延迟展示；首次请求固定为 1，之后以服务端快照校正。
    pub(super) fn started(&mut self, compaction_id: String, turn_id: Option<String>) {
        self.compaction = Some(ContextCompactionStatus {
            compaction_id,
            turn_id,
            retry_attempt: 1,
        });
    }

    /// 权威 thread/read 覆盖本地状态，解决重进会话和重连时事件可能已错过的问题。
    pub(super) fn restore(&mut self, compaction: Option<ContextCompactionStatus>) {
        self.compaction = compaction;
    }

    /// 只接纳当前压缩 Turn 的重试事件，避免同 Thread 中其它 Turn 抢占状态说明。
    pub(super) fn retry_started(&mut self, turn_id: &str, attempt: u64) -> bool {
        let Some(compaction) = self.compaction.as_mut() else {
            return false;
        };
        if compaction.turn_id.as_deref() != Some(turn_id) || attempt < 2 {
            return false;
        }
        compaction.retry_attempt = compaction.retry_attempt.max(attempt);
        true
    }

    /// 迟到的旧 compaction 终态不能清除快照中已开始的下一次压缩。
    pub(super) fn finish(&mut self, compaction_id: &str) -> bool {
        if self
            .compaction
            .as_ref()
            .is_some_and(|active| active.compaction_id == compaction_id)
        {
            self.compaction = None;
            true
        } else {
            false
        }
    }

    /// 重试序号沿用服务端请求 attempt；缩短阶段文案以便窄终端优先呈现重试事实。
    pub(super) fn status_text(&self) -> Option<String> {
        let compaction = self.compaction.as_ref()?;
        Some(if compaction.retry_attempt >= 2 {
            format!("摘要重试 · 第 {} 次请求", compaction.retry_attempt)
        } else {
            "正在压缩上下文".to_owned()
        })
    }
}
