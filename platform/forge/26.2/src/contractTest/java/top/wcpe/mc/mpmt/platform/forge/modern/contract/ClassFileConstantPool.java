package top.wcpe.mc.mpmt.platform.forge.modern.contract;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 类文件常量池与类头的最小只读解析器，供契约测试以「编译产物」为唯一断言对象。
 *
 * <p>契约测试禁止读源码 / 构建脚本文本做判断，因此「载荷实现了哪个接口」「通道用了哪套 API」
 * 「通道名与场景名实际取了哪个字面量」「调用了哪个类的哪个成员」这些事实只能从 JAR 内 class 取得：
 * 类名引用给出实际引用的类型，字符串常量给出实际使用的字面量，成员引用给出实际调用的字段 / 方法，
 * 直接接口表与声明方法表给出实际实现 / 声明的结构。
 */
final class ClassFileConstantPool {

    private static final int MAGIC = 0xCAFEBABE;

    private static final int TAG_UTF8 = 1;
    private static final int TAG_INTEGER = 3;
    private static final int TAG_FLOAT = 4;
    private static final int TAG_LONG = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_CLASS = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_FIELD_REF = 9;
    private static final int TAG_METHOD_REF = 10;
    private static final int TAG_INTERFACE_METHOD_REF = 11;
    private static final int TAG_NAME_AND_TYPE = 12;
    private static final int TAG_METHOD_HANDLE = 15;
    private static final int TAG_METHOD_TYPE = 16;
    private static final int TAG_DYNAMIC = 17;
    private static final int TAG_INVOKE_DYNAMIC = 18;
    private static final int TAG_MODULE = 19;
    private static final int TAG_PACKAGE = 20;

    /** 类名引用（{@code CONSTANT_Class}，内部名以 '/' 分隔）。 */
    private final List<String> classReferences;

    /** 字符串常量（{@code CONSTANT_String}，即源码里的字面量）。 */
    private final List<String> stringConstants;

    /** 成员引用（{@code owner.member} 形式，覆盖字段 / 方法与接口方法引用）。 */
    private final List<String> memberReferences;

    /** 全部 UTF8 常量，含字段签名与方法描述符，用于断言语义化描述符（如 {@code [B}）。 */
    private final List<String> utf8Constants;

    /** 类直接实现的接口内部名。 */
    private final List<String> interfaces;

    /** 类自身声明的方法名（含 {@code <init>} 等特殊名）。 */
    private final List<String> declaredMethods;

    private ClassFileConstantPool(DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC) {
            throw new IOException("类文件魔数错误");
        }
        input.readUnsignedShort(); // minor_version
        input.readUnsignedShort(); // major_version（由 verifyMajor 单独断言）
        int poolCount = input.readUnsignedShort();
        String[] utf8Table = new String[poolCount];
        int[] classTable = new int[poolCount];
        int[] stringTable = new int[poolCount];
        int[][] memberTable = new int[poolCount][];
        int[][] nameAndTypeTable = new int[poolCount][];
        for (int index = 1; index < poolCount; index++) {
            int tag = input.readUnsignedByte();
            switch (tag) {
                case TAG_UTF8 -> utf8Table[index] = input.readUTF();
                case TAG_CLASS -> classTable[index] = input.readUnsignedShort();
                case TAG_STRING -> stringTable[index] = input.readUnsignedShort();
                case TAG_FIELD_REF, TAG_METHOD_REF, TAG_INTERFACE_METHOD_REF ->
                        memberTable[index] = readPair(input);
                case TAG_NAME_AND_TYPE -> nameAndTypeTable[index] = readPair(input);
                case TAG_LONG, TAG_DOUBLE -> {
                    input.skipNBytes(Long.BYTES);
                    // 8 字节常量在常量表里占两个槽位，下一个索引必须跳过
                    index++;
                }
                case TAG_INTEGER, TAG_FLOAT -> input.skipNBytes(Integer.BYTES);
                case TAG_METHOD_HANDLE -> input.skipNBytes(Byte.BYTES + Short.BYTES);
                case TAG_METHOD_TYPE, TAG_MODULE, TAG_PACKAGE -> input.skipNBytes(Short.BYTES);
                case TAG_DYNAMIC, TAG_INVOKE_DYNAMIC -> input.skipNBytes(Short.BYTES * 2L);
                default -> throw new IOException("未知常量池标签：" + tag);
            }
        }
        input.readUnsignedShort(); // access_flags
        input.readUnsignedShort(); // this_class
        input.readUnsignedShort(); // super_class
        int interfaceCount = input.readUnsignedShort();
        List<String> interfaceNames = new ArrayList<>(interfaceCount);
        for (int index = 0; index < interfaceCount; index++) {
            interfaceNames.add(utf8Table[classTable[input.readUnsignedShort()]]);
        }
        skipMemberTable(input); // 字段表
        List<String> methodNames = readMethodNames(input, utf8Table);
        this.interfaces = List.copyOf(interfaceNames);
        this.declaredMethods = methodNames;
        this.classReferences = collectClassReferences(classTable, utf8Table);
        this.stringConstants = collectStringConstants(stringTable, utf8Table);
        this.memberReferences =
                collectMemberReferences(memberTable, nameAndTypeTable, classTable, utf8Table);
        this.utf8Constants = collectUtf8Constants(utf8Table);
    }

    /** 从字节流读出一份常量池视图；调用方负责关闭底层流。 */
    static ClassFileConstantPool read(InputStream stream) throws IOException {
        return new ClassFileConstantPool(new DataInputStream(stream));
    }

    List<String> classReferences() {
        return classReferences;
    }

    List<String> stringConstants() {
        return stringConstants;
    }

    List<String> memberReferences() {
        return memberReferences;
    }

    List<String> utf8Constants() {
        return utf8Constants;
    }

    List<String> interfaces() {
        return interfaces;
    }

    List<String> declaredMethods() {
        return declaredMethods;
    }

    private static int[] readPair(DataInputStream input) throws IOException {
        return new int[] {input.readUnsignedShort(), input.readUnsignedShort()};
    }

    private static List<String> collectClassReferences(int[] classTable, String[] utf8Table) {
        List<String> references = new ArrayList<>();
        for (int index = 1; index < classTable.length; index++) {
            if (classTable[index] != 0) {
                references.add(utf8Table[classTable[index]]);
            }
        }
        return List.copyOf(references);
    }

    private static List<String> collectStringConstants(int[] stringTable, String[] utf8Table) {
        List<String> values = new ArrayList<>();
        for (int index = 1; index < stringTable.length; index++) {
            if (stringTable[index] != 0) {
                values.add(utf8Table[stringTable[index]]);
            }
        }
        return List.copyOf(values);
    }

    private static List<String> collectUtf8Constants(String[] utf8Table) {
        List<String> values = new ArrayList<>();
        for (int index = 1; index < utf8Table.length; index++) {
            if (utf8Table[index] != null) {
                values.add(utf8Table[index]);
            }
        }
        return List.copyOf(values);
    }

    private static List<String> collectMemberReferences(
            int[][] memberTable, int[][] nameAndTypeTable, int[] classTable, String[] utf8Table) {
        List<String> references = new ArrayList<>();
        for (int index = 1; index < memberTable.length; index++) {
            int[] member = memberTable[index];
            if (member == null) {
                continue;
            }
            int[] nameAndType = nameAndTypeTable[member[1]];
            references.add(utf8Table[classTable[member[0]]] + "." + utf8Table[nameAndType[0]]);
        }
        return List.copyOf(references);
    }

    /** 跳过字段表：field_info = access_flags + name_index + descriptor_index + attributes。 */
    private static void skipMemberTable(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        for (int index = 0; index < count; index++) {
            input.skipNBytes(Short.BYTES * 3L);
            skipAttributes(input);
        }
    }

    private static List<String> readMethodNames(DataInputStream input, String[] utf8Table)
            throws IOException {
        int count = input.readUnsignedShort();
        List<String> names = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            input.readUnsignedShort(); // access_flags
            names.add(utf8Table[input.readUnsignedShort()]);
            input.readUnsignedShort(); // descriptor_index
            skipAttributes(input);
        }
        return List.copyOf(names);
    }

    private static void skipAttributes(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        for (int index = 0; index < count; index++) {
            input.skipNBytes(Short.BYTES);
            input.skipNBytes(input.readInt());
        }
    }
}
