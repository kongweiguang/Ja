// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import io.github.kongweiguang.ja.conversation.adapter.out.provider.ProviderProtocolException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 为命名 SSE 与 data-only SSE 共享严格 UTF-8 行边界，同时不解释任何字段语义。 */
public final class StrictSseLineReader {
    private final InputStream input;
    private final String providerCode;
    private final String invalidUtf8Message;
    private int pendingByte = -1;
    private boolean eof;

    /**
     * 只冻结错误分类和底层受限流；字段白名单与终止帧仍由各协议 Reader 独立决定。
     */
    public StrictSseLineReader(InputStream input, String providerCode, String invalidUtf8Message) {
        this.input = Objects.requireNonNull(input, "input");
        this.providerCode = Objects.requireNonNull(providerCode, "providerCode");
        this.invalidUtf8Message = Objects.requireNonNull(invalidUtf8Message, "invalidUtf8Message");
    }

    /**
     * 接受 LF、CRLF 与独立 CR，并把独立 CR 后已消费的字节留给下一行，避免跳过外层容量计数。
     */
    public String nextLine() throws IOException {
        if (eof) return null;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
        while (true) {
            int value = readByte();
            if (value < 0) {
                eof = true;
                return bytes.size() == 0 ? null : decode(bytes.toByteArray());
            }
            if (value == '\n') return decode(bytes.toByteArray());
            if (value == '\r') {
                int following = readByte();
                if (following >= 0 && following != '\n') pendingByte = following;
                if (following < 0) eof = true;
                return decode(bytes.toByteArray());
            }
            bytes.write(value);
        }
    }

    /** 先返回独立 CR 后的暂存字节，再推进唯一底层流。 */
    private int readByte() throws IOException {
        if (pendingByte >= 0) {
            int value = pendingByte;
            pendingByte = -1;
            return value;
        }
        return input.read();
    }

    /** 将畸形 UTF-8 归入调用方固定的脱敏协议类别，不使用替换字符继续解析。 */
    private String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw new ProviderProtocolException(providerCode, invalidUtf8Message, false);
        }
    }
}
