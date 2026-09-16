package buildconventions

import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.kotlin.dsl.withGroovyBuilder

/**
 * 插件工程编译类路径之外的外部插件类型：动态互操作。
 *
 * 本插件工程的编译期依赖只有 Gradle API 与 SpotBugs（见 `build.gradle.kts` 的 `dependencies`），
 * 而 SpongeGradle 与 shadow 的部分配置只能写在它们自己的类型上（`sponge {}` 元数据 DSL、
 * `shadowJar` 的 `configurations` 与 `relocate`）。这些位置改用 Groovy 动态调用等价表达，
 * 取值口径与迁移前的车道脚本逐项一致。
 */

/** 按名动态调用（走 Groovy 动态派发）。 */
internal fun Any.invokeGroovy(name: String, vararg args: Any?) {
    withGroovyBuilder { name.invoke(*args) }
}

/** 把 Kotlin lambda 包成 `Action<Any>`：外部插件按擦除后的 `Action` 类型接收回调。 */
internal fun groovyAction(body: (Any) -> Unit): Action<Any> =
    object : Action<Any> {
        override fun execute(target: Any) = body(target)
    }

/** 取外部类型上的枚举常量（该类型不在编译类路径内，故按全名反射）。 */
internal fun enumConstantOf(anchor: Any, className: String, constant: String): Any {
    val type = Class.forName(className, false, anchor.javaClass.classLoader)
    return type.getField(constant).get(null)
}

/**
 * 取外部插件注册的任务类型。
 *
 * 必须按全名反射取**未装饰**类：`tasks.named(...).get().javaClass` 拿到的是 Gradle 生成的
 * 装饰子类，不能用于注册新任务。
 */
@Suppress("UNCHECKED_CAST")
internal fun externalTaskType(anchor: Any, className: String): Class<Task> =
    Class.forName(className, false, anchor.javaClass.classLoader) as Class<Task>
