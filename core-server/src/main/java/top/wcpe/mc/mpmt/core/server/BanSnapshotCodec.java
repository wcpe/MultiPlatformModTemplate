package top.wcpe.mc.mpmt.core.server;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import top.wcpe.mc.mpmt.core.domain.ban.BanEntry;
import top.wcpe.mc.mpmt.core.domain.ban.MachineCode;

/** 封禁快照编解码：版本行后逐行保存机器码与原因的 Base64。 */
public final class BanSnapshotCodec {

    private static final String VERSION = "v1";
    private static final String FIELD_SEPARATOR = "\t";
    private static final String LINE_SEPARATOR = "\n";

    /** 按机器码排序后生成确定性快照。 */
    public String encode(Collection<BanEntry> entries) {
        Objects.requireNonNull(entries, "entries 不能为空");
        List<BanEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(entry -> entry.getCode().getValue()));
        StringBuilder result = new StringBuilder(VERSION);
        for (BanEntry entry : sorted) {
            BanEntry value = Objects.requireNonNull(entry, "entry 不能为空");
            result.append(LINE_SEPARATOR)
                    .append(base64(value.getCode().getValue()))
                    .append(FIELD_SEPARATOR)
                    .append(base64(value.getReason()));
        }
        return result.toString();
    }

    /** 解码快照；未知版本、损坏行或重复机器码均明确失败。 */
    public List<BanEntry> decode(String snapshot) {
        Objects.requireNonNull(snapshot, "snapshot 不能为空");
        String[] lines = snapshot.split(LINE_SEPARATOR, -1);
        if (lines.length == 0 || !VERSION.equals(lines[0])) {
            throw new IllegalArgumentException("不支持的封禁快照版本");
        }
        List<BanEntry> entries = new ArrayList<>();
        Set<MachineCode> seen = new HashSet<>();
        for (int index = 1; index < lines.length; index++) {
            BanEntry entry = decodeLine(lines[index], index + 1);
            if (!seen.add(entry.getCode())) {
                throw new IllegalArgumentException("封禁快照包含重复机器码，第 " + (index + 1) + " 行");
            }
            entries.add(entry);
        }
        return Collections.unmodifiableList(entries);
    }

    private static BanEntry decodeLine(String line, int lineNumber) {
        String[] fields = line.split(FIELD_SEPARATOR, -1);
        if (fields.length != 2) {
            throw new IllegalArgumentException("封禁快照第 " + lineNumber + " 行格式错误");
        }
        try {
            return new BanEntry(new MachineCode(text(fields[0])), text(fields[1]));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("封禁快照第 " + lineNumber + " 行 Base64 损坏", error);
        }
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
