package buildconventions

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.withGroovyBuilder

/**
 * realserver 验收扩展的属性按名取值：插件工程不编译依赖该扩展类型，故经 Groovy 动态取。
 *
 * 返回值仍是 Gradle 的惰性属性类型（[Property] / [ListProperty] / [RegularFileProperty]），
 * 赋值语义与静态类型取值一致。
 */
internal fun Any.stringProperty(name: String): Property<String> = bukkitExtensionProperty(name)

internal fun Any.booleanProperty(name: String): Property<Boolean> = bukkitExtensionProperty(name)

internal fun Any.intProperty(name: String): Property<Int> = bukkitExtensionProperty(name)

internal fun Any.fileProperty(name: String): RegularFileProperty =
    withGroovyBuilder { getProperty(name) } as RegularFileProperty

@Suppress("UNCHECKED_CAST")
internal fun Any.listProperty(name: String): ListProperty<String> =
    withGroovyBuilder { getProperty(name) } as ListProperty<String>

@Suppress("UNCHECKED_CAST")
private fun <T : Any> Any.bukkitExtensionProperty(name: String): Property<T> =
    withGroovyBuilder { getProperty(name) } as Property<T>
