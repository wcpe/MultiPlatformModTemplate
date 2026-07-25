package top.wcpe.mc.mpmt.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** wire golden schema 所需的严格最小 JSON 解析器。 */
final class GoldenJsonParser {

    private GoldenJsonParser() {
        // 工具类不实例化
    }

    static List<Map<String, String>> parse(String source) {
        if (source == null) {
            throw new IllegalArgumentException("JSON 文本不能为空");
        }
        return new Parser(source).parseRoot();
    }

    private static final class Parser {

        private final String source;
        private int position;

        private Parser(String source) {
            this.source = source;
        }

        private List<Map<String, String>> parseRoot() {
            skipWhitespace();
            List<Map<String, String>> values = parseArray();
            skipWhitespace();
            if (position != source.length()) {
                throw failure("根数组后存在尾随垃圾");
            }
            return values;
        }

        private List<Map<String, String>> parseArray() {
            expect('[');
            skipWhitespace();
            List<Map<String, String>> values = new ArrayList<>();
            if (consume(']')) {
                return values;
            }
            while (true) {
                requireNext('{', "根数组元素必须为对象");
                values.add(parseObject());
                skipWhitespace();
                if (consume(']')) {
                    return values;
                }
                expect(',');
                skipWhitespace();
                requireNotNext(']', "根数组不允许尾随逗号");
            }
        }

        private Map<String, String> parseObject() {
            expect('{');
            skipWhitespace();
            Map<String, String> fields = new LinkedHashMap<>();
            if (consume('}')) {
                return fields;
            }
            while (true) {
                String key = readRequiredString("对象字段名必须为字符串");
                skipWhitespace();
                expect(':');
                skipWhitespace();
                String value = readRequiredString("字段值必须为字符串");
                if (fields.put(key, value) != null) {
                    throw failure("对象字段重复：" + key);
                }
                skipWhitespace();
                if (consume('}')) {
                    return fields;
                }
                expect(',');
                skipWhitespace();
                requireNotNext('}', "对象不允许尾随逗号");
            }
        }

        private String readRequiredString(String message) {
            requireNext('"', message);
            return parseString();
        }

        private String parseString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (position < source.length()) {
                char current = source.charAt(position++);
                if (current == '"') {
                    return value.toString();
                }
                if (current == '\\') {
                    appendEscape(value);
                } else {
                    appendDirect(value, current);
                }
            }
            throw failure("字符串未闭合");
        }

        private void appendEscape(StringBuilder value) {
            if (position >= source.length()) {
                throw failure("字符串转义不完整");
            }
            char escaped = source.charAt(position++);
            if (appendSimpleEscape(value, escaped)) {
                return;
            }
            if (escaped == 'u') {
                appendUnicodeEscape(value);
                return;
            }
            throw failure("非法字符串转义：\\" + escaped);
        }

        private static boolean appendSimpleEscape(StringBuilder value, char escaped) {
            switch (escaped) {
                case '"':
                case '\\':
                case '/':
                    value.append(escaped);
                    return true;
                case 'b':
                    value.append('\b');
                    return true;
                case 'f':
                    value.append('\f');
                    return true;
                case 'n':
                    value.append('\n');
                    return true;
                case 'r':
                    value.append('\r');
                    return true;
                case 't':
                    value.append('\t');
                    return true;
                default:
                    return false;
            }
        }

        private void appendUnicodeEscape(StringBuilder value) {
            char current = readHexQuad();
            if (Character.isLowSurrogate(current)) {
                throw failure("Unicode 转义出现孤立低代理项");
            }
            value.append(current);
            if (!Character.isHighSurrogate(current)) {
                return;
            }
            if (!consume('\\') || !consume('u')) {
                throw failure("Unicode 高代理项后缺少低代理项");
            }
            char low = readHexQuad();
            if (!Character.isLowSurrogate(low)) {
                throw failure("Unicode 高代理项后不是低代理项");
            }
            value.append(low);
        }

        private char readHexQuad() {
            if (position + 4 > source.length()) {
                throw failure("Unicode 转义不足四位");
            }
            int value = 0;
            for (int index = 0; index < 4; index++) {
                int digit = Character.digit(source.charAt(position++), 16);
                if (digit < 0) {
                    throw failure("Unicode 转义包含非十六进制字符");
                }
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private void appendDirect(StringBuilder value, char current) {
            if (current < 0x20) {
                throw failure("字符串包含未转义控制字符");
            }
            if (Character.isLowSurrogate(current)) {
                throw failure("字符串出现孤立低代理项");
            }
            value.append(current);
            if (!Character.isHighSurrogate(current)) {
                return;
            }
            if (position >= source.length() || !Character.isLowSurrogate(source.charAt(position))) {
                throw failure("字符串高代理项后缺少低代理项");
            }
            value.append(source.charAt(position++));
        }

        private void skipWhitespace() {
            while (position < source.length()) {
                char current = source.charAt(position);
                if (current != ' ' && current != '\t' && current != '\r' && current != '\n') {
                    return;
                }
                position++;
            }
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw failure("预期字符：" + expected);
            }
        }

        private boolean consume(char expected) {
            if (position < source.length() && source.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void requireNext(char expected, String message) {
            if (position >= source.length() || source.charAt(position) != expected) {
                throw failure(message);
            }
        }

        private void requireNotNext(char forbidden, String message) {
            if (position < source.length() && source.charAt(position) == forbidden) {
                throw failure(message);
            }
        }

        private IllegalArgumentException failure(String message) {
            return new IllegalArgumentException(message + "，位置：" + position);
        }
    }
}
