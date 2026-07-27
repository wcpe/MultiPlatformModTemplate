package top.wcpe.mc.mpmt.platform.fabric.version;

import java.util.Objects;

/** 不依赖 Minecraft 类型的 Fabric 通道标识。 */
public final class FabricChannel {

    private final String namespace;
    private final String path;

    public FabricChannel(String namespace, String path) {
        this.namespace = Objects.requireNonNull(namespace, "namespace 不能为空");
        this.path = Objects.requireNonNull(path, "path 不能为空");
    }

    public String namespace() {
        return namespace;
    }

    public String path() {
        return path;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FabricChannel)) {
            return false;
        }
        FabricChannel channel = (FabricChannel) other;
        return namespace.equals(channel.namespace) && path.equals(channel.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace, path);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
