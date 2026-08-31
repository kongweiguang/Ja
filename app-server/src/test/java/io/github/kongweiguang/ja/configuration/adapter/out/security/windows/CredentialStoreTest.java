// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.security.windows;

import io.github.kongweiguang.ja.configuration.domain.ConfigurationData;
import io.github.kongweiguang.ja.configuration.adapter.out.document.ConfigurationStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CredentialStoreTest 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。 */
final class CredentialStoreTest {
    @TempDir Path temporary;

    /** parserFailureClearsLoadedAuthBytes 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。 */
    @Test
    void parserFailureClearsLoadedAuthBytes() throws Exception {
        Path auth = temporary.resolve("auth.json");
        byte[] source = "{\"cred_test\":\"sensitive-value\"}".getBytes(StandardCharsets.UTF_8);
        ConfigurationStore.writeAtomic(auth, source, true);
        CapturingMapper mapper = new CapturingMapper();

        CredentialStore.State state = new CredentialStore(auth, mapper).load();
        try {
            assertEquals(ConfigurationData.LayerStatus.CORRUPT, state.status());
            assertNotNull(mapper.input);
            assertTrue(Arrays.equals(new byte[mapper.input.length], mapper.input));
        } finally {
            state.close();
        }
    }

    /** 保留 CredentialStore 传入的原始字节组引用，专用于观测解析失败后是否已清零。 */
    private static final class CapturingMapper extends ObjectMapper {
        private static final long serialVersionUID = 1L;
        private byte[] input;

        /** 捕获生产路径的同一输入数组后立即注入解析失败，以覆盖 finally 清零分支。 */
        @Override
        public JsonNode readTree(byte[] content) throws IOException {
            input = content;
            throw new IOException("injected_parser_failure");
        }
    }
}
