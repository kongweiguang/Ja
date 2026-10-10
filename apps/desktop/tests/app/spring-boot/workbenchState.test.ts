// @author kongweiguang
import { describe, expect, it } from "vitest";
import {
  appReducer,
  createInitialState,
  emptySession,
  emptyTurn,
  turnsFromHistory,
} from "../../../../../ja-agent/example/ja-agent-example-springboot/frontend/src/state";

describe("Spring Boot workbench history", () => {
  /** 多个工具调用可以共用同一条已提交的助手记录序号，但必须在界面中保持为不同条目。 */
  it("preserves conversation order and distinct tool calls", () => {
    const turns = turnsFromHistory(
      [
        { sequence: 2, turnId: "turn_one", kind: "assistant", text: "处理中" },
        { sequence: 2, turnId: "turn_one", kind: "tool", callId: "call_a", name: "search" },
        { sequence: 2, turnId: "turn_one", kind: "tool", callId: "call_b", name: "read" },
        { sequence: 1, turnId: "turn_one", kind: "user", text: "你好" },
        { sequence: 3, turnId: "turn_one", kind: "tool-result", callId: "call_a", text: "找到" },
        { sequence: 4, turnId: "turn_two", kind: "user", text: "继续" },
      ],
      { turnId: "turn_two", state: "RUNNING" },
    );

    expect(turns.map((turn) => turn.turnId)).toEqual(["turn_one", "turn_two"]);
    expect(turns[0]?.userText).toBe("你好");
    expect(turns[0]?.state).toBe("HISTORY");
    expect(turns[1]?.state).toBe("RUNNING");
    expect(turns[0]?.items.map((item) => item.kind)).toEqual([
      "assistant",
      "tool",
      "tool",
      "tool-result",
    ]);
    expect(new Set(turns[0]?.items.map((item) => item.key)).size).toBe(turns[0]?.items.length);
  });

  /** 刷新后的等待中 Turn 需要稳定的记录引用，才能恢复答复状态和已测用量。 */
  it("retains wait and usage references from public history", () => {
    const turns = turnsFromHistory(
      [
        { sequence: 1, turnId: "turn_wait", kind: "user", text: "继续" },
        { sequence: 2, turnId: "turn_wait", kind: "usage", recordId: "usage_1" },
        { sequence: 3, turnId: "turn_wait", kind: "wait", recordId: "wait_1" },
      ],
      { turnId: "turn_wait", state: "WAITING_INPUT" },
    );

    expect(turns[0]?.waitRecordId).toBe("wait_1");
    expect(turns[0]?.usageRecordIds).toEqual(["usage_1"]);
    expect(turns[0]?.state).toBe("WAITING_INPUT");
  });

  /** 其他 Thread 的迟到更新不能替换当前选中 Thread 的对话记录或草稿。 */
  it("keeps session updates scoped to their Thread", () => {
    localStorage.clear();
    const initial = createInitialState();
    const first = appReducer(initial, {
      type: "session-patch",
      threadId: "thr_a",
      value: { ...emptySession(), draft: "未发送" },
    });
    const selected = appReducer(first, { type: "patch", value: { selectedThreadId: "thr_a" } });
    const late = appReducer(selected, {
      type: "session-turn",
      threadId: "thr_b",
      turn: emptyTurn("turn_b", "另一会话", []),
    });

    expect(late.selectedThreadId).toBe("thr_a");
    expect(late.sessions["thr_a"]?.draft).toBe("未发送");
    expect(late.sessions["thr_a"]?.turns).toHaveLength(0);
    expect(late.sessions["thr_b"]?.turns[0]?.userText).toBe("另一会话");
  });
});
