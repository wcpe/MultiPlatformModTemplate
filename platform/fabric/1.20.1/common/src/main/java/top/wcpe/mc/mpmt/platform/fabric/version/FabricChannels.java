package top.wcpe.mc.mpmt.platform.fabric.version;

/** 产品与验收通道的单一真源。 */
public final class FabricChannels {

    public static final FabricChannel PRODUCT = new FabricChannel("mpmt", "main");

    private FabricChannels() {
    }
}
