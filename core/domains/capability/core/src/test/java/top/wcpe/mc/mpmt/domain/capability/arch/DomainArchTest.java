package top.wcpe.mc.mpmt.domain.capability.arch;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * 功能域之间的依赖约束（ADR-0011 / ADR-0028）。
 *
 * <p>自 ADR-0028 起域是独立模块，域类的可见性取决于所在模块的 classpath：这两条断言
 * 必须放在「能看到全部域」的模块里才有意义，故跟随域代码落在此处，而非 {@code core:kernel}。
 *
 * <p>当前只有 {@code capability} 一个域，切片规则无对象可检；用 {@code allowEmptyShould(true)}
 * 使单域阶段不误报，加入第二个域后自动生效。域间若出现直接依赖或环，本测试即失败——
 * 这也是「跨域只经 EventBus」这条红线的机检落点。
 */
class DomainArchTest {

    /** 仅导入生产类（不含测试），范围限定域包。 */
    private static final JavaClasses DOMAIN_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("top.wcpe.mc.mpmt.domain");

    @Test
    void 功能域之间不互相依赖() {
        slices()
                .matching("top.wcpe.mc.mpmt.domain.(*)..")
                .should()
                .notDependOnEachOther()
                .allowEmptyShould(true)
                .because("功能域之间禁止直接依赖，跨域协作经自有 EventBus 转发（ADR-0011）")
                .check(DOMAIN_CLASSES);
    }

    @Test
    void 功能域无环依赖() {
        slices()
                .matching("top.wcpe.mc.mpmt.domain.(*)..")
                .should()
                .beFreeOfCycles()
                .allowEmptyShould(true)
                .because("全依赖图无环、同层无互依（ADR-0011）")
                .check(DOMAIN_CLASSES);
    }
}
