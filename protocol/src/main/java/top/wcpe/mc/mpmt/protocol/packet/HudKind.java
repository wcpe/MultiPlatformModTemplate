package top.wcpe.mc.mpmt.protocol.packet;

import top.wcpe.mc.mpmt.protocol.ProtocolException;

/**
 * 跨端 HUD 消息类型（S2C）：决定客户端以何种形式呈现。
 *
 * <p>每个类型有<b>稳定线缆码</b> {@link #wireId()}（而非依赖 {@code ordinal()}，避免重排序破坏协议），
 * 是协议字节布局的一部分（单一真源，ADR-0006）。线缆码<b>从 1 起、0 保留为非法</b>；
 * 新增类型追加稳定码、不复用旧码。
 */
public enum HudKind {

    /** 屏幕中央大标题（可带副标题与时长）。 */
    TITLE(1),
    /** 物品栏上方动作栏。 */
    ACTIONBAR(2),
    /** 右上角成就式弹窗。 */
    TOAST(3),
    /** 聊天栏消息。 */
    CHAT(4);

    private final int wireId;

    HudKind(int wireId) {
        this.wireId = wireId;
    }

    /** 稳定线缆码（写入字节流）。 */
    public int wireId() {
        return wireId;
    }

    /** 按线缆码还原类型；未知码抛 {@link ProtocolException}（非法输入明确拒绝、不崩溃）。 */
    public static HudKind fromWireId(int wireId) {
        for (HudKind kind : values()) {
            if (kind.wireId == wireId) {
                return kind;
            }
        }
        throw new ProtocolException("未知 HUD 消息类型线缆码：" + wireId);
    }
}
