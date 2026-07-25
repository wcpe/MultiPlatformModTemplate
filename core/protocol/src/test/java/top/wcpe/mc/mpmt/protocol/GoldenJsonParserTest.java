package top.wcpe.mc.mpmt.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** wire golden 专用严格 JSON 解析与 schema 校验。 */
class GoldenJsonParserTest {

    @Test
    @DisplayName("严格解析数组、对象、字符串及合法转义")
    void 解析合法结构与转义() {
        String json = "[{\"name\":\"a\\n\\u4e2d\\ud83d\\ude42\","
                + "\"type\":\"Ping\",\"nonce\":\"0\",\"encoded\":\"AQ==\"}]";

        List<Map<String, String>> values = GoldenJsonParser.parse(json);

        assertEquals(1, values.size());
        assertEquals("a\n中🙂", values.get(0).get("name"));
    }

    @Test
    @DisplayName("拒绝错误根类型、非对象元素、逗号错误和尾随垃圾")
    void 拒绝结构错误() {
        List<String> invalid = Arrays.asList(
                "{}",
                "[\"x\"]",
                "[{\"a\":\"b\"} {\"c\":\"d\"}]",
                "[{\"a\":\"b\"},]",
                "[{\"a\":\"b\"}] trailing");

        for (String json : invalid) {
            assertThrows(IllegalArgumentException.class, () -> GoldenJsonParser.parse(json), json);
        }
    }

    @Test
    @DisplayName("拒绝非法转义、重复键和非字符串字段值")
    void 拒绝字段语法错误() {
        List<String> invalid = Arrays.asList(
                "[{\"a\":\"\\x\"}]",
                "[{\"a\":\"b\",\"a\":\"c\"}]",
                "[{\"a\":1}]",
                "[{\"a\":\"未结束}]");

        for (String json : invalid) {
            assertThrows(IllegalArgumentException.class, () -> GoldenJsonParser.parse(json), json);
        }
    }

    @Test
    @DisplayName("schema 拒绝未知字段、缺失字段和非法 boolean")
    void schema拒绝非法字段() {
        String valid = "[{\"name\":\"server-hello-empty-false\",\"type\":\"ServerHello\","
                + "\"protocolVersion\":\"0\",\"sessionId\":\"\",\"accepted\":\"false\","
                + "\"encoded\":\"AQ==\"}]";
        WireV1GoldenSchema.validate(GoldenJsonParser.parse(valid));

        assertSchemaRejected(valid.replace("\"encoded\"", "\"unknown\":\"x\",\"encoded\""));
        assertSchemaRejected(valid.replace("\"protocolVersion\":\"0\",", ""));
        assertSchemaRejected(valid.replace("\"accepted\":\"false\"", "\"accepted\":\"maybe\""));
    }

    @Test
    @DisplayName("schema 拒绝奇数、非法十六进制及空 pattern")
    void schema拒绝非法十六进制() {
        assertSchemaRejected(fragment("\"payloadHex\":\"0\""));
        assertSchemaRejected(fragment("\"payloadHex\":\"0g\""));
        assertSchemaRejected(fragment("\"payloadPatternHex\":\"0\",\"payloadLength\":\"1\""));
        assertSchemaRejected(fragment("\"payloadPatternHex\":\"0g\",\"payloadLength\":\"1\""));
        assertSchemaRejected(fragment("\"payloadPatternHex\":\"\",\"payloadLength\":\"0\""));
        assertSchemaRejected(clientRepeat("", "1"));
    }

    @Test
    @DisplayName("schema 拒绝负数、展开超限及字面 payload 超限")
    void schema拒绝长度越界() {
        assertSchemaRejected(fragment("\"payloadPatternHex\":\"00\",\"payloadLength\":\"-1\""));
        assertSchemaRejected(fragment("\"payloadPatternHex\":\"00\",\"payloadLength\":\"1048577\""));
        assertSchemaRejected(clientRepeat("a", "-1"));
        assertSchemaRejected(clientRepeat("🙂", "262145"));
        String oversizedHex = repeat("00", WireV1GoldenSchema.MAX_EXPANDED_LENGTH + 1);
        assertSchemaRejected(fragment("\"payloadHex\":\"" + oversizedHex + "\""));
    }

    private static String fragment(String payloadFields) {
        return "[{\"name\":\"fragment-empty\",\"type\":\"Fragment\","
                + "\"seqId\":\"0\",\"index\":\"0\",\"total\":\"1\",\"crc32\":\"0\","
                + payloadFields + ",\"encoded\":\"AQ==\"}]";
    }

    private static String clientRepeat(String repeat, String length) {
        return "[{\"name\":\"client-hello-max-utf\",\"type\":\"ClientHello\","
                + "\"protocolVersion\":\"1\",\"modVersionRepeat\":\"" + repeat + "\","
                + "\"modVersionLength\":\"" + length + "\",\"encoded\":\"AQ==\"}]";
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }

    private static void assertSchemaRejected(String json) {
        List<Map<String, String>> values = GoldenJsonParser.parse(json);
        assertThrows(IllegalArgumentException.class, () -> WireV1GoldenSchema.validate(values));
    }
}
