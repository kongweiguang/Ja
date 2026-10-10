// @author kongweiguang
import { afterEach, describe, expect, it, vi } from "vitest";
import { observeChat } from "../../../../../ja-agent/example/ja-agent-example-springboot/frontend/src/api";

afterEach(() => vi.unstubAllGlobals());

describe("Spring Boot POST SSE", () => {
  /** 字节分片边界、行边界和心跳都不能改变控制器收到的数据帧。 */
  it("parses split UTF-8 and multiline frames", async () => {
    const bytes = new TextEncoder().encode(
      ': keep-alive\r\n\r\nevent: text-delta\r\ndata: {"text":"中\r\ndata: 文"}\r\n\r\nevent: accepted\ndata: {}',
    );
    const chineseByte = bytes.indexOf(0xe4);
    const stream = new ReadableStream<Uint8Array>({
      /** 使用不同字节切片测试解码器缓冲，不依赖网络分块时序。 */
      start(controller) {
        controller.enqueue(bytes.slice(0, chineseByte + 1));
        controller.enqueue(bytes.slice(chineseByte + 1, chineseByte + 2));
        controller.enqueue(bytes.slice(chineseByte + 2));
        controller.close();
      },
    });
    const fetchMock = vi.fn().mockResolvedValue(new Response(stream, { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    const frames: Array<{ event: string; data: string[] }> = [];

    await observeChat(
      "reconnect",
      { threadId: "thr_1", turnId: "turn_1", afterSequence: 0 },
      new AbortController().signal,
      async (frame) => {
        frames.push(frame);
      },
    );

    expect(frames).toEqual([
      { event: "text-delta", data: ['{"text":"中', '文"}'] },
      { event: "accepted", data: ["{}"] },
    ]);
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/chat/reconnect",
      expect.objectContaining({ method: "POST" }),
    );
  });
});
