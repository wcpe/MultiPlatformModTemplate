// 根构建脚本：仅定义全局坐标与版本，不承载任何平台插件。
// 版本号唯一来源 = 根目录 VERSION 文件（testing-and-quality §3：VERSION 是版本号唯一来源）。

val mpmtVersion: String = rootProject.file("VERSION").readText().trim()

allprojects {
    group = "top.wcpe.mc.mpmt"
    version = mpmtVersion
}

// 一键全量构建：复合构建的 includeBuild 默认不并入根 build 生命周期，这里聚合
// 根构建各子模块 + 各独立 includeBuild 平台的 build，便于本地 / CI 全量验证。
tasks.register("buildAll") {
    group = "build"
    description = "构建根构建所有模块 + 各 includeBuild 平台产物"
    dependsOn(subprojects.map { "${it.path}:build" })
    dependsOn(gradle.includedBuilds.map { it.task(":build") })
}

