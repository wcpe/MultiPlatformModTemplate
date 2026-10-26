package buildconventions

/**
 * 架构守卫的三档（ADR-0028 决策 7）。
 *
 * 档位决定「哪些外部引用算违规」：层级越靠内，禁得越多。
 */
enum class ArchitectureTier {
    /** `core` 目录下全部模块：禁 MC、禁加载器、禁平台 SDK。 */
    STRICT,

    /** `mc-shared` 目录：允许 MC，禁加载器与平台 SDK。 */
    MC_AWARE,

    /** `platform` 全目录：全允许，但受表单校验。 */
    PERMITTED,
    ;

    /** 本档禁止出现的外部包前缀。 */
    fun forbiddenPrefixes(): List<String> =
        when (this) {
            STRICT -> MC_PREFIXES + LOADER_PREFIXES + PLATFORM_SDK_PREFIXES
            MC_AWARE -> LOADER_PREFIXES + PLATFORM_SDK_PREFIXES
            PERMITTED -> emptyList()
        }

    companion object {
        /** Minecraft 本体与 Mojang 工具。 */
        val MC_PREFIXES =
            listOf("net.minecraft.", "com.mojang.", "net.minecraftforge.", "net.neoforged.")

        /** 加载器 API（跨版本不可共享的根因）。 */
        val LOADER_PREFIXES =
            listOf("net.fabricmc.", "net.minecraftforge.", "net.neoforged.", "org.spongepowered.")

        /** 平台 SDK。 */
        val PLATFORM_SDK_PREFIXES =
            listOf("org.bukkit.", "io.papermc.", "org.spongepowered.", "net.kyori.")
    }
}
