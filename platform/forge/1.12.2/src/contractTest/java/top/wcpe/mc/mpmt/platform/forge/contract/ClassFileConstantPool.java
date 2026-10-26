package top.wcpe.mc.mpmt.platform.forge.contract;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 类文件常量池读取器：把 .class 字节里的 UTF8 常量与类名引用抽出来。
 *
 * <p>契约测试只允许断言编译产物与运行期行为，不得回读源码文本；本类让"通道名 / 目标 MC 版本 /
 * 重映射痕迹"这类发布物契约落在 jar 内 class 的真实常量池条目上，而不是 {@code build.gradle.kts}
 * 或 {@code .java} 的文本匹配。
 *
 * <p>Java 8 兼容：契约测试车道以 Java 8 编译运行，故只用 Java 8 API（无 var / List.of / 文本块）。
 */
final class ClassFileConstantPool {

    /** 常量池条目标签：UTF8 字符串。 */
    private static final int TAG_UTF8 = 1;
    /** 常量池条目标签：整型字面量。 */
    private static final int TAG_INTEGER = 3;
    /** 常量池条目标签：浮点字面量。 */
    private static final int TAG_FLOAT = 4;
    /** 常量池条目标签：长整型字面量（占两个槽位）。 */
    private static final int TAG_LONG = 5;
    /** 常量池条目标签：双精度字面量（占两个槽位）。 */
    private static final int TAG_DOUBLE = 6;
    /** 常量池条目标签：类或接口引用。 */
    private static final int TAG_CLASS = 7;
    /** 常量池条目标签：字符串字面量（指向 UTF8）。 */
    private static final int TAG_STRING = 8;
    /** 常量池条目标签：字段引用。 */
    private static final int TAG_FIELD_REF = 9;
    /** 常量池条目标签：方法引用。 */
    private static final int TAG_METHOD_REF = 10;
    /** 常量池条目标签：接口方法引用。 */
    private static final int TAG_INTERFACE_METHOD_REF = 11;
    /** 常量池条目标签：名称与类型描述符。 */
    private static final int TAG_NAME_AND_TYPE = 12;
    /** 常量池条目标签：方法句柄（Java 7+）。 */
    private static final int TAG_METHOD_HANDLE = 15;
    /** 常量池条目标签：方法类型（Java 7+）。 */
    private static final int TAG_METHOD_TYPE = 16;
    /** 常量池条目标签：动态常量（Java 11+）。 */
    private static final int TAG_DYNAMIC = 17;
    /** 常量池条目标签：invokedynamic 引导项（Java 7+）。 */
    private static final int TAG_INVOKE_DYNAMIC = 18;
    /** 常量池条目标签：模块（Java 9+）。 */
    private static final int TAG_MODULE = 19;
    /** 常量池条目标签：包（Java 9+）。 */
    private static final int TAG_PACKAGE = 20;

    /** 常量池 UTF8 条目原文（保留声明顺序与重复项）。 */
    private final List<String> utf8;
    /** CONSTANT_Class 条目解析出的内部类名（形如 top/wcpe/mc/mpmt/Foo）。 */
    private final List<String> classNames;

    private ClassFileConstantPool(List<String> utf8, List<String> classNames) {
        this.utf8 = utf8;
        this.classNames = classNames;
    }

    /** 解析一份类文件的常量池；结构非法时直接抛异常，不静默降级。 */
    static ClassFileConstantPool read(byte[] classBytes) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(classBytes));
        requireMagic(input);
        input.readUnsignedShort(); // 跳过 minor version
        input.readUnsignedShort(); // 跳过 major version（Java 8 上限由产物断言另行校验）
        int poolCount = input.readUnsignedShort();
        Map<Integer, String> utf8ByIndex = new HashMap<Integer, String>();
        Map<Integer, Integer> classNameIndex = new HashMap<Integer, Integer>();
        List<Integer> classPositions = new ArrayList<Integer>();
        List<String> utf8 = new ArrayList<String>();

        for (int index = 1; index < poolCount; index++) {
            int tag = input.readUnsignedByte();
            switch (tag) {
                case TAG_UTF8:
                    // CONSTANT_Utf8 的布局就是 u2 长度 + modified UTF-8，与 readUTF 一致
                    String value = input.readUTF();
                    utf8.add(value);
                    utf8ByIndex.put(index, value);
                    break;
                case TAG_CLASS:
                    classNameIndex.put(index, input.readUnsignedShort());
                    classPositions.add(index);
                    break;
                case TAG_STRING:
                case TAG_METHOD_TYPE:
                case TAG_MODULE:
                case TAG_PACKAGE:
                    input.readUnsignedShort();
                    break;
                case TAG_INTEGER:
                case TAG_FLOAT:
                case TAG_FIELD_REF:
                case TAG_METHOD_REF:
                case TAG_INTERFACE_METHOD_REF:
                case TAG_NAME_AND_TYPE:
                case TAG_DYNAMIC:
                case TAG_INVOKE_DYNAMIC:
                    input.readInt();
                    break;
                case TAG_LONG:
                case TAG_DOUBLE:
                    input.readLong();
                    // long / double 各占两个常量池槽位，后一个槽位不可寻址
                    index++;
                    break;
                case TAG_METHOD_HANDLE:
                    input.readUnsignedByte(); // 引用类型（reference kind）
                    input.readUnsignedShort(); // 引用下标（reference index）
                    break;
                default:
                    throw new IOException("未知常量池标签：" + tag);
            }
        }

        List<String> classNames = new ArrayList<String>();
        for (Integer position : classPositions) {
            String name = utf8ByIndex.get(classNameIndex.get(position));
            if (name != null) {
                classNames.add(name);
            }
        }
        return new ClassFileConstantPool(utf8, classNames);
    }

    /** 常量池是否含该 UTF8 原文（精确匹配，不会把 MPMT 误判成 MPMTTEST 的子串命中）。 */
    boolean hasUtf8(String value) {
        return utf8.contains(value);
    }

    /** 常量池是否存在以该前缀起始的 UTF8 条目（用于 SRG 成员名等前缀族判断）。 */
    boolean hasUtf8Prefix(String prefix) {
        for (String value : utf8) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 常量池是否引用该内部类名（证明"用了某个 API / 接入了某个类型"）。 */
    boolean hasClassReference(String internalName) {
        return classNames.contains(internalName);
    }

    private static void requireMagic(DataInputStream input) throws IOException {
        if (input.readInt() != 0xCAFEBABE) {
            throw new IOException("非法类文件：缺少 0xCAFEBABE 魔数");
        }
    }
}
