package top.wcpe.mc.mpmt.core.domain.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * 分离度 / 分层架构回归断言（ADR-0001 / ADR-0011）：以 ArchUnit 自动校验 L0 的核心不变量，
 * 守护「同一份 L0 字节码零平台依赖」与「功能域互不依赖、无环」——即便日后 classpath 或依赖变化也回归。
 *
 * <p>本断言覆盖 L0（core-domain，含内核 {@code core.domain.*} 与功能域 {@code domain.*}）；
 * L1 各模块的平台无关性由其构建依赖（不引入任何平台 API）于构建期保证。
 */
class LayeringArchTest {

    /** 仅导入本模块生产类（不含测试），范围限定本项目包。 */
    private static final JavaClasses L0_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("top.wcpe.mc.mpmt");

    @Test
    void L0不依赖任何平台原生类型() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.bukkit..",
                        "net.minecraft..",
                        "net.minecraftforge..",
                        "net.neoforged..",
                        "net.fabricmc..",
                        "org.spongepowered..")
                .because("L0 功能域必须零平台依赖，同一份字节码在各平台一致运行（ADR-0001）")
                .check(L0_CLASSES);
    }

    @Test
    void 功能域之间不互相依赖() {
        slices()
                .matching("top.wcpe.mc.mpmt.domain.(*)..")
                .should()
                .notDependOnEachOther()
                .because("功能域之间禁止直接依赖，跨域协作经自有 EventBus 转发（ADR-0011）")
                .check(L0_CLASSES);
    }

    @Test
    void 功能域无环依赖() {
        slices()
                .matching("top.wcpe.mc.mpmt.domain.(*)..")
                .should()
                .beFreeOfCycles()
                .because("全依赖图无环、同层无互依（ADR-0011）")
                .check(L0_CLASSES);
    }
}
