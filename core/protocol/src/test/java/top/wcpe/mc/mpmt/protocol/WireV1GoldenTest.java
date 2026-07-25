package top.wcpe.mc.mpmt.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.protocol.packet.ClientHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ClientIdReportPacket;
import top.wcpe.mc.mpmt.protocol.packet.DisconnectPacket;
import top.wcpe.mc.mpmt.protocol.packet.FragmentPacket;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.PingPacket;
import top.wcpe.mc.mpmt.protocol.packet.PongPacket;
import top.wcpe.mc.mpmt.protocol.packet.ResyncRequestPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerMessagePacket;

/** 基线 c5ae73f 人工锁定的 wire-v1 逐字节回归。 */
class WireV1GoldenTest {

    private static final Map<String, Function<Map<String, String>, Packet>> FACTORIES = factories();
    private static final Set<String> EXPECTED_TYPES = new HashSet<>(Arrays.asList(
            "ClientHello",
            "ServerHello",
            "ServerMessage",
            "ServerHudMessage",
            "Disconnect",
            "ClientIdReport",
            "Ping",
            "Pong",
            "ResyncRequest",
            "Fragment"));
    private static final Set<String> EXPECTED_NAMES = new HashSet<>(Arrays.asList(
            "client-hello-empty-zero",
            "client-hello-ascii-varint-127",
            "client-hello-utf-varint-128",
            "client-hello-negative-one",
            "client-hello-int-min",
            "client-hello-int-max",
            "client-hello-max-utf",
            "server-hello-empty-false",
            "server-hello-ascii-true",
            "server-hello-utf-true",
            "server-message-empty",
            "server-message-ascii",
            "server-message-utf",
            "disconnect-empty",
            "disconnect-utf",
            "client-id-ascii",
            "client-id-utf",
            "hud-title-zero",
            "hud-actionbar-negative",
            "hud-toast-long-min",
            "hud-chat-long-max",
            "ping-zero",
            "ping-negative-one",
            "ping-long-min",
            "ping-long-max",
            "pong-zero",
            "pong-negative-one",
            "pong-long-max",
            "resync-zero",
            "resync-negative-one",
            "resync-long-min",
            "resync-long-max",
            "fragment-empty",
            "fragment-single",
            "fragment-varint-boundaries",
            "fragment-int-boundaries",
            "fragment-max-payload"));

    @Test
    @DisplayName("全部十类包编码逐字节等于 wire-v1 golden 且解码值一致")
    void 全部包匹配golden() throws IOException {
        PacketCodec codec = new PacketCodec();
        List<Map<String, String>> vectors = readVectors();
        Set<String> coveredTypes = new HashSet<>();
        Set<String> coveredNames = new HashSet<>();
        assertEquals(EXPECTED_NAMES.size(), vectors.size(), "golden 向量数量必须固定");

        for (Map<String, String> vector : vectors) {
            String name = required(vector, "name");
            String type = required(vector, "type");
            assertTrue(coveredNames.add(name), "golden name 重复：" + name);
            Function<Map<String, String>, Packet> factory = FACTORIES.get(type);
            assertNotNull(factory, "未知 golden 包类型：" + type);
            Packet expectedPacket = factory.apply(vector);
            byte[] expectedBytes = Base64.getDecoder().decode(required(vector, "encoded"));
            assertArrayEquals(expectedBytes, codec.encode(expectedPacket), required(vector, "name"));
            Packet decoded = codec.decode(expectedBytes);
            assertEquals(expectedPacket.getClass(), decoded.getClass(), required(vector, "name"));
            assertEquals(expectedPacket, decoded, required(vector, "name"));
            coveredTypes.add(type);
        }

        assertEquals(EXPECTED_TYPES, coveredTypes);
        assertEquals(EXPECTED_NAMES, coveredNames);
    }

    private static List<Map<String, String>> readVectors() throws IOException {
        List<Map<String, String>> vectors = GoldenJsonParser.parse(readResource());
        WireV1GoldenSchema.validate(vectors);
        return vectors;
    }

    private static String readResource() throws IOException {
        InputStream input = WireV1GoldenTest.class.getResourceAsStream("/golden/wire-v1.json");
        assertNotNull(input, "缺少固定 golden：golden/wire-v1.json");
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Function<Map<String, String>, Packet>> factories() {
        Map<String, Function<Map<String, String>, Packet>> factories = new HashMap<>();
        factories.put("ClientHello", WireV1GoldenTest::clientHello);
        factories.put("ServerHello", WireV1GoldenTest::serverHello);
        factories.put("ServerMessage", fields -> new ServerMessagePacket(text(fields, "text")));
        factories.put("ServerHudMessage", WireV1GoldenTest::serverHudMessage);
        factories.put("Disconnect", fields -> new DisconnectPacket(text(fields, "reason")));
        factories.put("ClientIdReport", fields -> new ClientIdReportPacket(text(fields, "clientId")));
        factories.put("Ping", fields -> new PingPacket(longValue(fields, "nonce")));
        factories.put("Pong", fields -> new PongPacket(longValue(fields, "nonce")));
        factories.put("ResyncRequest", fields -> new ResyncRequestPacket(longValue(fields, "fromRevision")));
        factories.put("Fragment", WireV1GoldenTest::fragment);
        return factories;
    }

    private static Packet clientHello(Map<String, String> fields) {
        return new ClientHelloPacket(intValue(fields, "protocolVersion"), text(fields, "modVersion"));
    }

    private static Packet serverHello(Map<String, String> fields) {
        return new ServerHelloPacket(
                intValue(fields, "protocolVersion"),
                text(fields, "sessionId"),
                Boolean.parseBoolean(required(fields, "accepted")));
    }

    private static Packet serverHudMessage(Map<String, String> fields) {
        return new ServerHudMessagePacket(
                HudKind.valueOf(required(fields, "kind")),
                text(fields, "text"),
                text(fields, "subtitle"),
                longValue(fields, "durationMillis"));
    }

    private static Packet fragment(Map<String, String> fields) {
        return new FragmentPacket(
                intValue(fields, "seqId"),
                intValue(fields, "index"),
                intValue(fields, "total"),
                intValue(fields, "crc32"),
                bytes(fields));
    }

    private static String text(Map<String, String> fields, String name) {
        String literal = fields.get(name);
        if (literal != null) {
            return literal;
        }
        String repeat = required(fields, name + "Repeat");
        int length = intValue(fields, name + "Length");
        StringBuilder value = new StringBuilder(repeat.length() * length);
        for (int i = 0; i < length; i++) {
            value.append(repeat);
        }
        return value.toString();
    }

    private static byte[] bytes(Map<String, String> fields) {
        String literal = fields.get("payloadHex");
        if (literal != null) {
            return decodeHex(literal);
        }
        byte[] pattern = decodeHex(required(fields, "payloadPatternHex"));
        int length = intValue(fields, "payloadLength");
        byte[] value = new byte[length];
        for (int i = 0; i < length; i++) {
            value[i] = pattern[i % pattern.length];
        }
        return value;
    }

    private static byte[] decodeHex(String hex) {
        byte[] value = new byte[hex.length() / 2];
        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return value;
    }

    private static int intValue(Map<String, String> fields, String name) {
        return Integer.parseInt(required(fields, name));
    }

    private static long longValue(Map<String, String> fields, String name) {
        return Long.parseLong(required(fields, name));
    }

    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null) {
            throw new IllegalArgumentException("golden 缺少字段：" + name);
        }
        return value;
    }
}
