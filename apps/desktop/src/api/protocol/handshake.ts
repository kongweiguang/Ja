// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { JA_ERROR_CODES } from "./errors";
import { assertNoReadyTokenLeak, ReadyTokenSchema } from "./protocol";
import { fingerprintReadyToken } from "./readyToken";

type HandshakePhase =
  | "disconnected"
  | "awaiting_initialized"
  | "awaiting_ready"
  | "ready"
  | "reconnect_required";

interface HandshakeFailure {
  code: typeof JA_ERROR_CODES.INVALID_FRAME;
  errorCode: "INVALID_FRAME";
  category: "protocol";
  retryable: false;
}

/**
 * 公开投影刻意保持最小：比较指纹及其历史只保存在模块私有 WeakMap，
 * 不进入 Zustand 或 devtools，避免 challenge 派生信息成为界面状态。
 */
export interface HandshakeProjection {
  phase: HandshakePhase;
  generation: number;
  error?: HandshakeFailure;
}

type HandshakeSignal =
  | { kind: "runtime/initialized"; readyToken: string }
  | {
      kind: "runtime";
      status: "starting" | "ready" | "shutting_down" | "stopped" | "failed";
      readyToken?: string;
    };
export type RuntimeStatus = Extract<HandshakeSignal, { kind: "runtime" }>["status"];

const MAX_TOKEN_HISTORY = 64;

interface HandshakeMetadata {
  expectedFingerprint?: string;
  usedFingerprints: readonly string[];
}

const metadataByProjection = new WeakMap<HandshakeProjection, HandshakeMetadata>();

/** 复制并冻结公开投影，避免 listener 通过共享引用篡改握手状态或附加 token 材料。 */
function copyProjection(value: HandshakeProjection): HandshakeProjection {
  const copy: HandshakeProjection = {
    phase: value.phase,
    generation: value.generation,
    ...(value.error === undefined ? {} : { error: Object.freeze({ ...value.error }) }),
  };
  return Object.freeze(copy);
}

/** 从私有 WeakMap 读取指纹历史；缺失元数据只能回到空历史，绝不从公开投影反推 challenge。 */
function metadataFor(state: HandshakeProjection): HandshakeMetadata {
  return metadataByProjection.get(state) ?? { usedFingerprints: [] };
}

/**
 * 原子安装公开投影及其私有元数据；二者由投影对象 identity 关联，
 * 因此 UI 序列化状态时不会携带 expected fingerprint 或重放历史。
 */
function installProjection(
  value: HandshakeProjection,
  metadata: HandshakeMetadata,
): HandshakeProjection {
  const projection = copyProjection(value);
  metadataByProjection.set(projection, {
    expectedFingerprint: metadata.expectedFingerprint,
    usedFingerprints: Object.freeze([...metadata.usedFingerprints]),
  });
  return projection;
}

/**
 * 创建供 UI 消费的状态；显式非 ready 阶段用于阻止 snapshot 或业务事件
 * 被误判为握手完成。
 */
function createHandshakeProjection(): HandshakeProjection {
  return installProjection(
    { phase: "awaiting_initialized", generation: 0 },
    { usedFingerprints: [] },
  );
}

/** 将任意握手拒绝归一化为不携带 challenge 的协议错误。 */
function withFailureHistory(state: HandshakeProjection): HandshakeProjection {
  return installProjection(
    {
      phase: "reconnect_required",
      generation: state.generation,
      error: {
        code: JA_ERROR_CODES.INVALID_FRAME,
        errorCode: "INVALID_FRAME",
        category: "protocol",
        retryable: false,
      },
    },
    metadataFor(state),
  );
}

/** 新 challenge 准入时保留有界不透明历史，既支持重放检测又不保存原始 token。 */
function nextHistory(state: HandshakeProjection, fingerprint: string): readonly string[] {
  return [...metadataFor(state).usedFingerprints, fingerprint].slice(-MAX_TOKEN_HISTORY);
}

/**
 * 将单个握手信号应用为有限状态转换；顺序非法、challenge 缺失、ready 重复
 * 或旧代际值一律失败关闭。
 */
function transitionHandshake(
  state: HandshakeProjection,
  signal: HandshakeSignal,
): HandshakeProjection {
  // 已断开或已故障的代际必须失败关闭，任何晚到帧都不能重新开启同一连接。
  if (state.phase === "disconnected" || state.phase === "reconnect_required") {
    return withFailureHistory(state);
  }
  if (signal.kind === "runtime/initialized") {
    // 初始化阶段先验证 challenge 形状，再用指纹完成重放检测；原始 token 不进入状态历史。
    if (!ReadyTokenSchema.safeParse(signal.readyToken).success) {
      return withFailureHistory(state);
    }
    const fingerprint = fingerprintReadyToken(signal.readyToken);
    const metadata = metadataFor(state);
    if (state.phase !== "awaiting_initialized" || metadata.usedFingerprints.includes(fingerprint)) {
      return withFailureHistory(state);
    }
    return installProjection(
      {
        phase: "awaiting_ready",
        generation: state.generation + 1,
      },
      {
        expectedFingerprint: fingerprint,
        usedFingerprints: nextHistory(state, fingerprint),
      },
    );
  }

  if (signal.status === "ready") {
    // ready 只接受当前代际的精确回显，缺失、错序和跨代际 challenge 都进入重连状态。
    const metadata = metadataFor(state);
    if (
      state.phase !== "awaiting_ready" ||
      metadata.expectedFingerprint === undefined ||
      !ReadyTokenSchema.safeParse(signal.readyToken).success ||
      fingerprintReadyToken(signal.readyToken ?? "") !== metadata.expectedFingerprint
    ) {
      return withFailureHistory(state);
    }
    return installProjection({ phase: "ready", generation: state.generation }, metadata);
  }

  if (signal.status === "stopped" || signal.status === "failed") {
    // sidecar 在 ready 前退出属于握手失败；已完成代际退出则回到等待下一次初始化。
    if (state.phase === "awaiting_ready") {
      return withFailureHistory(state);
    }
    const metadata = metadataFor(state);
    return installProjection(
      { phase: "awaiting_initialized", generation: state.generation },
      { usedFingerprints: metadata.usedFingerprints },
    );
  }
  return state;
}

/**
 * 客户端只保留当前不透明指纹；所有历史比较状态都是 WeakMap 中的
 * 有界不透明指纹，不保存 challenge 正文。
 */
export class ReadyHandshake {
  #state: HandshakeProjection = createHandshakeProjection();
  #listeners = new Set<
    (state: HandshakeProjection, opaqueFingerprints: readonly string[]) => void
  >();

  /** 返回冻结副本，让调用方只能观察阶段与 generation，不能访问或修改私有指纹元数据。 */
  get state(): HandshakeProjection {
    return copyProjection(this.#state);
  }

  /** ready 判断只读取内部有限状态机，不以 Runtime UI 状态或事件数量推断握手成功。 */
  get isReady(): boolean {
    return this.#state.phase === "ready";
  }

  /** 启动新的传输代际并保留有界历史，使旧 challenge 不能在重连后重新准入。 */
  start(): void {
    const metadata = metadataFor(this.#state);
    this.#state = installProjection(
      { phase: "awaiting_initialized", generation: this.#state.generation },
      { usedFingerprints: metadata.usedFingerprints },
    );
    this.emit();
  }

  /** 标记传输已断开，并显式清除 ready 语义，避免陈旧状态看似仍可用。 */
  disconnect(): void {
    const metadata = metadataFor(this.#state);
    this.#state = installProjection(
      { phase: "disconnected", generation: this.#state.generation },
      { usedFingerprints: metadata.usedFingerprints },
    );
    this.emit();
  }

  /** 每个代际只接受一次客户端拥有的出站 challenge，防止同代际重复初始化。 */
  acceptInitialized(token: string): HandshakeProjection {
    // 校验阶段拒绝畸形 challenge，避免无效值进入指纹计算或历史集合。
    if (!ReadyTokenSchema.safeParse(token).success) {
      this.fail();
      return this.#state;
    }
    // 状态转换阶段只允许 awaiting_initialized -> awaiting_ready 的单向推进。
    const next = transitionHandshake(this.#state, {
      kind: "runtime/initialized",
      readyToken: token,
    });
    if (next.phase !== "awaiting_ready") {
      this.fail();
      return this.#state;
    }
    // 发布阶段只广播不含 token 的投影和不透明指纹，observer 不能取得 challenge 正文。
    this.#state = next;
    this.emit();
    return this.#state;
  }

  /** 仅当 ready 回显本代际已初始化 challenge 时接受，禁止跨代际关联。 */
  acceptRuntimeStatus(status: RuntimeStatus, readyToken?: string): HandshakeProjection {
    // 状态机同时校验顺序与 ready 回显，不在调用层复制 challenge 比较逻辑。
    const next = transitionHandshake(this.#state, { kind: "runtime", status, readyToken });
    if (next.phase === "reconnect_required") {
      this.fail();
      return this.#state;
    }
    this.#state = next;
    this.emit();
    return this.#state;
  }

  /**
   * 在 parser 或 listener 看到帧之前执行整帧泄漏检查；历史仅按指纹比较，
   * 不保留旧 token。
   */
  assertFrameSafe(value: unknown): void {
    // 先识别协议唯一允许携带 challenge/credential 的精确字段路径，其他相似键仍按泄漏处理。
    const root =
      value !== null && typeof value === "object" ? (value as Record<string, unknown>) : undefined;
    const allowCredentialSecret = root?.["method"] === "credential/set";
    const allowChallengePath =
      root?.["method"] === "runtime/initialized" ||
      (root?.["method"] === "runtime/status-changed" &&
        (root?.["params"] as Record<string, unknown> | undefined)?.["status"] === "ready")
        ? (["params", "readyToken"] as const)
        : undefined;
    // 再对完整帧执行递归安全校验；历史只通过不可逆指纹参与已知 token 判断。
    const usedFingerprints = metadataFor(this.#state).usedFingerprints;
    assertNoReadyTokenLeak(value, {
      allowChallengePath,
      isKnownReadyToken: (candidate) => {
        if (!ReadyTokenSchema.safeParse(candidate).success) {
          return false;
        }
        return usedFingerprints.includes(fingerprintReadyToken(candidate));
      },
      allowCredentialSecret,
    });
  }

  /** 将投影置为稳定错误状态，同时禁止 token 进入错误对象。 */
  failHandshake(): void {
    this.fail();
  }

  /** 订阅 UI 启动与重连状态，但不向观察者暴露 token 材料。 */
  onChange(
    listener: (state: HandshakeProjection, opaqueFingerprints: readonly string[]) => void,
  ): () => void {
    this.#listeners.add(listener);
    return () => this.#listeners.delete(listener);
  }

  /** 将任意内部拒绝收敛为 reconnect_required，并保留指纹历史阻止故障后重放。 */
  private fail(): void {
    this.#state = withFailureHistory(this.#state);
    this.emit();
  }

  /** 广播冻结投影与有界不透明历史；observer 异常被隔离，不能反向阻断安全状态转换。 */
  private emit(): void {
    const opaqueFingerprints = Object.freeze([...metadataFor(this.#state).usedFingerprints]);
    for (const listener of this.#listeners) {
      try {
        listener(copyProjection(this.#state), opaqueFingerprints);
      } catch {
        // UI observer 失败不得阻止传输状态进入安全阶段，否则展示层会反向控制协议生命周期。
      }
    }
  }
}
