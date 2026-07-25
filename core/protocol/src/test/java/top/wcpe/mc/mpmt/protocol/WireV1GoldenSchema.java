package top.wcpe.mc.mpmt.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** wire-v1 固定 JSON 的字段 schema 校验器。 */
final class WireV1GoldenSchema {

    static final int MAX_EXPANDED_LENGTH = 1 << 20;

    private static final Map<String, VectorSchema> SCHEMAS = schemas();

    private WireV1GoldenSchema() {
        // 工具类不实例化
    }

    static void validate(List<Map<String, String>> vectors) {
        Objects.requireNonNull(vectors, "golden 根数组不能为空");
        for (Map<String, String> vector : vectors) {
            validateVector(vector);
        }
    }

    private static void validateVector(Map<String, String> vector) {
        Objects.requireNonNull(vector, "golden 向量对象不能为空");
        String type = required(vector, "type");
        VectorSchema schema = SCHEMAS.get(type);
        if (schema == null) {
            throw new IllegalArgumentException("golden 包类型未知：" + type);
        }
        schema.validate(vector);
        requireNonEmpty(vector, "name");
        requireNonEmpty(vector, "encoded");
        validateBase64(vector.get("encoded"));
    }

    private static Map<String, VectorSchema> schemas() {
        Map<String, VectorSchema> schemas = new HashMap<>();
        addHandshakeSchemas(schemas);
        addTextSchemas(schemas);
        addNumericSchemas(schemas);
        addFragmentSchema(schemas);
        return Collections.unmodifiableMap(schemas);
    }

    private static void addHandshakeSchemas(Map<String, VectorSchema> schemas) {
        schemas.put("ClientHello", schema(
                fields("protocolVersion"),
                fields("modVersion", "modVersionRepeat", "modVersionLength"),
                WireV1GoldenSchema::validateTextEncoding));
        schemas.put("ServerHello", schema(
                fields("protocolVersion", "sessionId", "accepted"),
                fields(),
                WireV1GoldenSchema::validateBoolean));
    }

    private static void addTextSchemas(Map<String, VectorSchema> schemas) {
        schemas.put("ServerMessage", simpleSchema("text"));
        schemas.put("ServerHudMessage", simpleSchema("kind", "text", "subtitle", "durationMillis"));
        schemas.put("Disconnect", simpleSchema("reason"));
        schemas.put("ClientIdReport", simpleSchema("clientId"));
    }

    private static void addNumericSchemas(Map<String, VectorSchema> schemas) {
        schemas.put("Ping", simpleSchema("nonce"));
        schemas.put("Pong", simpleSchema("nonce"));
        schemas.put("ResyncRequest", simpleSchema("fromRevision"));
    }

    private static void addFragmentSchema(Map<String, VectorSchema> schemas) {
        schemas.put("Fragment", schema(
                fields("seqId", "index", "total", "crc32"),
                fields("payloadHex", "payloadPatternHex", "payloadLength"),
                WireV1GoldenSchema::validatePayloadEncoding));
    }

    private static VectorSchema simpleSchema(String... required) {
        return schema(fields(required), fields(), vector -> {
            // 无附加条件
        });
    }

    private static VectorSchema schema(
            Set<String> required, Set<String> optional, Consumer<Map<String, String>> extraValidation) {
        return new VectorSchema(required, optional, extraValidation);
    }

    private static Set<String> fields(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    private static void requireAlternative(
            Map<String, String> vector, String literal, String repeated, String length) {
        boolean hasLiteral = vector.containsKey(literal);
        boolean hasRepeated = vector.containsKey(repeated);
        boolean hasLength = vector.containsKey(length);
        if (hasLiteral == (hasRepeated || hasLength)) {
            throw new IllegalArgumentException("golden 字段必须二选一：" + literal + " 或 " + repeated + "/" + length);
        }
        if (!hasLiteral && (!hasRepeated || !hasLength)) {
            throw new IllegalArgumentException("golden 重复描述字段不完整：" + repeated + "/" + length);
        }
    }

    private static void validateTextEncoding(Map<String, String> vector) {
        requireAlternative(vector, "modVersion", "modVersionRepeat", "modVersionLength");
        if (vector.containsKey("modVersion")) {
            requireExpandedLength("modVersion", vector.get("modVersion").getBytes(StandardCharsets.UTF_8).length);
            return;
        }
        String repeat = required(vector, "modVersionRepeat");
        if (repeat.isEmpty()) {
            throw new IllegalArgumentException("modVersionRepeat 不能为空");
        }
        int count = parseNonNegativeLength(vector, "modVersionLength");
        long expanded = (long) repeat.getBytes(StandardCharsets.UTF_8).length * count;
        requireExpandedLength("modVersionRepeat", expanded);
    }

    private static void validatePayloadEncoding(Map<String, String> vector) {
        requireAlternative(vector, "payloadHex", "payloadPatternHex", "payloadLength");
        if (vector.containsKey("payloadHex")) {
            validateHex("payloadHex", vector.get("payloadHex"), true);
            return;
        }
        validateHex("payloadPatternHex", required(vector, "payloadPatternHex"), false);
        int payloadLength = parseNonNegativeLength(vector, "payloadLength");
        requireExpandedLength("payloadLength", payloadLength);
    }

    private static void validateHex(String name, String value, boolean allowEmpty) {
        if (!allowEmpty && value.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        if ((value.length() & 1) != 0) {
            throw new IllegalArgumentException(name + " 必须为偶数长度十六进制");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.digit(value.charAt(index), 16) < 0) {
                throw new IllegalArgumentException(name + " 包含非十六进制字符");
            }
        }
        requireExpandedLength(name, value.length() / 2L);
    }

    private static int parseNonNegativeLength(Map<String, String> vector, String name) {
        int value;
        try {
            value = Integer.parseInt(required(vector, name));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " 必须为整数", exception);
        }
        if (value < 0) {
            throw new IllegalArgumentException(name + " 不能为负数");
        }
        return value;
    }

    private static void requireExpandedLength(String name, long length) {
        if (length > MAX_EXPANDED_LENGTH) {
            throw new IllegalArgumentException(name + " 展开长度超过协议上限：" + MAX_EXPANDED_LENGTH);
        }
    }

    private static void validateBoolean(Map<String, String> vector) {
        String accepted = required(vector, "accepted");
        if (!"true".equals(accepted) && !"false".equals(accepted)) {
            throw new IllegalArgumentException("accepted 必须为 true 或 false 字符串");
        }
    }

    private static void validateBase64(String value) {
        try {
            Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("encoded 不是合法 Base64", exception);
        }
    }

    private static void requireNonEmpty(Map<String, String> vector, String name) {
        if (required(vector, name).isEmpty()) {
            throw new IllegalArgumentException("golden 字段不能为空：" + name);
        }
    }

    private static String required(Map<String, String> vector, String name) {
        String value = vector.get(name);
        if (value == null) {
            throw new IllegalArgumentException("golden 缺少字段：" + name);
        }
        return value;
    }

    private static final class VectorSchema {

        private final Set<String> required;
        private final Set<String> allowed;
        private final Consumer<Map<String, String>> extraValidation;

        private VectorSchema(
                Set<String> typeRequired,
                Set<String> optional,
                Consumer<Map<String, String>> extraValidation) {
            this.required = withCommon(typeRequired);
            this.allowed = new HashSet<>(required);
            this.allowed.addAll(optional);
            this.extraValidation = extraValidation;
        }

        private void validate(Map<String, String> vector) {
            Set<String> missing = new HashSet<>(required);
            missing.removeAll(vector.keySet());
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("golden 缺少字段：" + missing);
            }
            Set<String> unknown = new HashSet<>(vector.keySet());
            unknown.removeAll(allowed);
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("golden 包含未知字段：" + unknown);
            }
            extraValidation.accept(vector);
        }

        private static Set<String> withCommon(Set<String> typeRequired) {
            Set<String> fields = new HashSet<>(typeRequired);
            fields.add("name");
            fields.add("type");
            fields.add("encoded");
            return fields;
        }
    }
}
