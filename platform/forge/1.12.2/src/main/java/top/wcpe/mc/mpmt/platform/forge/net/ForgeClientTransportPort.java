package top.wcpe.mc.mpmt.platform.forge.net;

import top.wcpe.mc.mpmt.core.domain.port.TransportPort;

/** 1.12.2 客户端传输生命周期接缝。 */
public interface ForgeClientTransportPort extends TransportPort {

    void clearReceiver();
}
