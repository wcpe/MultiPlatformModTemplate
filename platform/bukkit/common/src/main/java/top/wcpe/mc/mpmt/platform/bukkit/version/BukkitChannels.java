package top.wcpe.mc.mpmt.platform.bukkit.version;

import java.util.Objects;

/**
 * Bukkit 产品协议通道映射。
 *
 * <p>验收控制通道不得进入产品 jar；本对象仅描述产品通道，供 L4 适配器声明版本相关通道名。
 */
public final class BukkitChannels {

    private final String product;

    public BukkitChannels(String product) {
        this.product = Objects.requireNonNull(product, "产品通道不能为空");
        if (product.trim().isEmpty()) {
            throw new IllegalArgumentException("产品通道不能为空字符串");
        }
    }

    /** 产品协议通道（1.12 为 {@code MPMT}，1.13+ 为 {@code mpmt:main}）。 */
    public String product() {
        return product;
    }
}
