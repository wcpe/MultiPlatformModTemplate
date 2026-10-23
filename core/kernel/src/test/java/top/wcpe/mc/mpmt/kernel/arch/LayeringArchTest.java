package top.wcpe.mc.mpmt.kernel.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * 内核层架构回归断言（ADR-0001）：{@code core/kernel} 必须零平台依赖。
 *
 * <p>自 ADR-0028 起功能域不再是内核模块内的包，而是独立的 {@code core/domains/<域>} 模块，
 * 因此「域之间不互相依赖 / 无环」的两条断言已迁至 {@link DomainArchTest}——
 * 它们需要能看到域模块的类，放在内核模块里会因为 classpath 里没有域类而无法生效。
 *
 * <p>本断言覆盖 L0 内核（{@code kernel.*}）。
 */
class LayeringArchTest {

    /** 仅导入本模块生产类（不含测试），范围限定本项目包。 */
    private static final JavaClasses L0_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("top.wcpe.mc.mpmt");

    @Test
    void L0内核不依赖任何平台原生类型() {
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
                .because("L0 内核必须零平台依赖，同一份字节码在各平台一致运行（ADR-0001）")
                .check(L0_CLASSES);
    }
}
