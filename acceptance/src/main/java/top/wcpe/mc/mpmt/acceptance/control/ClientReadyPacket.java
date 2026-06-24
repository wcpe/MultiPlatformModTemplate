package top.wcpe.mc.mpmt.acceptance.control;

import lombok.Value;

/** C2S：程序化客户端连入并通道就绪后上报，携带其控制协议版本（供兼容校验）。 */
@Value
public class ClientReadyPacket implements AcceptanceControlPacket {

    /** 客户端控制协议版本。 */
    int protocolVersion;
}
