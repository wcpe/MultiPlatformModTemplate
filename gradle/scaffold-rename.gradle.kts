// 脚手架一键换名（由根 build.gradle.kts apply）。
// 语义对齐原 tools/rename_scaffold.py：文本替换 + Java 包目录搬迁。不依赖 python。

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

val oldGroup = "top.wcpe.mc.mpmt"
val oldPackagePath = "top/wcpe/mc/mpmt"
val oldDisplayName = "MultiPlatformModTemplate"
val oldId = "mpmt"
val oldRootName = "mpmt"

val skipDirNames =
    setOf(
        ".git",
        ".gradle",
        "build",
        ".tmp",
        "node_modules",
        ".idea",
        "run",
        "run-client",
        "run-server",
        "logs",
        "run-acceptance-server",
        "run-acceptance-client",
        "run-realserver",
    )

val textSuffixes =
    setOf(
        ".java",
        ".kt",
        ".kts",
        ".gradle",
        ".properties",
        ".yml",
        ".yaml",
        ".json",
        ".toml",
        ".md",
        ".xml",
        ".txt",
        ".MF",
        ".services",
    )

val specialNames =
    setOf(
        "plugin.yml",
        "mods.toml",
        "fabric.mod.json",
        "VERSION",
        "mcmod.info",
    )

fun validateScaffoldId(s: String) {
    require(s.matches(Regex("[a-z][a-z0-9_]{1,31}"))) {
        "mpmt.scaffold.id 须匹配 [a-z][a-z0-9_]{1,31}（Fabric/Forge modid 友好）"
    }
}

fun validateScaffoldGroup(s: String) {
    require(s.matches(Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+"))) {
        "mpmt.scaffold.group 须为合法 Java 包名，至少两段（如 com.example.mygame）"
    }
}

fun shouldSkipRelative(rel: Path): Boolean {
    for (i in 0 until rel.nameCount) {
        if (rel.getName(i).toString() in skipDirNames) {
            return true
        }
    }
    return false
}

fun isTextCandidate(path: Path): Boolean {
    val name = path.fileName.toString()
    if (name in specialNames) {
        return true
    }
    val dot = name.lastIndexOf('.')
    if (dot < 0) {
        return false
    }
    return name.substring(dot) in textSuffixes
}

fun replaceScaffoldText(
    content: String,
    newGroup: String,
    newName: String,
    newId: String,
    rewriteChannels: Boolean,
): String {
    var out = content
    out = out.replace(oldGroup, newGroup)
    out = out.replace(oldPackagePath, newGroup.replace('.', '/'))
    out = out.replace(oldDisplayName, newName)

    out = Regex("\\b${Regex.escape(oldId)}-").replace(out, "$newId-")
    out = out.replace("\"$oldId-", "\"$newId-")
    out = out.replace("'$oldId-", "'$newId-")

    out =
        Regex("""rootProject\.name\s*=\s*"${Regex.escape(oldRootName)}"""")
            .replace(out, """rootProject.name = "$newId"""")
    out =
        Regex("""rootProject\.name\s*=\s*'${Regex.escape(oldRootName)}'""")
            .replace(out, "rootProject.name = '$newId'")
    out =
        Regex("""("id"\s*:\s*")${Regex.escape(oldId)}(")""")
            .replace(out) { m -> m.groupValues[1] + newId + m.groupValues[2] }
    out =
        Regex("""(modId\s*=\s*")${Regex.escape(oldId)}(")""")
            .replace(out) { m -> m.groupValues[1] + newId + m.groupValues[2] }
    out =
        Regex("""(modId\s*=\s*')${Regex.escape(oldId)}(')""")
            .replace(out) { m -> m.groupValues[1] + newId + m.groupValues[2] }

    if (rewriteChannels) {
        out = out.replace("mpmt:main", "$newId:main")
        out = out.replace("mpmt-test:acceptance", "$newId-test:acceptance")
        val legacy =
            if (newId.length >= 4) {
                newId.uppercase().take(4)
            } else {
                newId.uppercase().padEnd(4, 'X')
            }
        val legacyTest = (newId.uppercase() + "TEST").take(8)
        out = Regex("""\b"MPMT"""").replace(out, "\"$legacy\"")
        out = Regex("""\b'MPMT'""").replace(out, "'$legacy'")
        out = Regex("""\b"MPMTTEST"""").replace(out, "\"$legacyTest\"")
        out = Regex("""\b'MPMTTEST'""").replace(out, "'$legacyTest'")
    }

    out = Regex("\\b${Regex.escape(oldId)}\\b").replace(out, newId)
    return out
}

fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) {
        return
    }
    Files.walkFileTree(
        path,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

fun copyTreeMerge(src: Path, dest: Path) {
    Files.walkFileTree(
        src,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val target = dest.resolve(src.relativize(dir).toString())
                Files.createDirectories(target)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val target = dest.resolve(src.relativize(file).toString())
                Files.createDirectories(target.parent)
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

fun isOldJavaPackageDir(root: Path, dir: Path): Boolean {
    if (!Files.isDirectory(dir) || dir.fileName.toString() != "mpmt") {
        return false
    }
    val rel =
        try {
            root.relativize(dir).toString().replace('\\', '/')
        } catch (_: IllegalArgumentException) {
            return false
        }
    return rel.endsWith("java/top/wcpe/mc/mpmt") || rel.contains("/java/top/wcpe/mc/mpmt")
}

fun moveJavaPackageTrees(
    root: Path,
    newGroup: String,
    dryRun: Boolean,
    log: (String) -> Unit,
): Int {
    val candidates = mutableListOf<Path>()
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val rel =
                    try {
                        root.relativize(dir)
                    } catch (_: IllegalArgumentException) {
                        return FileVisitResult.CONTINUE
                    }
                if (shouldSkipRelative(rel)) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                if (isOldJavaPackageDir(root, dir)) {
                    candidates.add(dir)
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }
        },
    )

    var moves = 0
    for (oldDir in candidates.sortedByDescending { it.toString().length }) {
        if (!Files.isDirectory(oldDir)) {
            continue
        }
        var javaRoot = oldDir
        while (javaRoot.fileName != null && javaRoot.fileName.toString() != "java") {
            javaRoot = javaRoot.parent ?: break
        }
        if (javaRoot.fileName?.toString() != "java") {
            continue
        }
        var newDir = javaRoot
        for (seg in newGroup.split('.')) {
            newDir = newDir.resolve(seg)
        }
        log("MOVE ${root.relativize(oldDir)} -> ${root.relativize(newDir)}")
        moves++
        if (dryRun) {
            continue
        }
        Files.createDirectories(newDir.parent)
        if (Files.exists(newDir)) {
            Files.list(oldDir).use { stream ->
                stream.forEach { child ->
                    val dest = newDir.resolve(child.fileName)
                    if (Files.exists(dest)) {
                        if (Files.isDirectory(child)) {
                            copyTreeMerge(child, dest)
                            deleteRecursively(child)
                        } else {
                            Files.copy(child, dest, StandardCopyOption.REPLACE_EXISTING)
                            Files.delete(child)
                        }
                    } else {
                        Files.move(child, dest, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            deleteRecursively(oldDir)
        } else {
            Files.move(oldDir, newDir)
        }
        var parent = oldDir.parent
        repeat(4) {
            val p = parent ?: return@repeat
            if (p == javaRoot || !Files.isDirectory(p)) {
                return@repeat
            }
            val empty = Files.list(p).use { it.findFirst().isEmpty }
            if (!empty) {
                return@repeat
            }
            val toDelete = p
            parent = p.parent
            Files.deleteIfExists(toDelete)
        }
    }
    return moves
}

tasks.register("renameScaffold") {
    group = "help"
    description =
        "一键换名脚手架身份（纯 kts）。必填 -P mpmt.scaffold.id= -P mpmt.scaffold.group= " +
            "-P mpmt.scaffold.name=；预览 -P mpmt.scaffold.dryRun=true；改通道 " +
            "-P mpmt.scaffold.rewriteChannels=true"
    notCompatibleWithConfigurationCache("换名会改写源树")

    val idProp = providers.gradleProperty("mpmt.scaffold.id")
    val groupProp = providers.gradleProperty("mpmt.scaffold.group")
    val nameProp = providers.gradleProperty("mpmt.scaffold.name")
    val dryRunProp =
        providers.gradleProperty("mpmt.scaffold.dryRun").map { it == "true" }.orElse(false)
    val rewriteProp =
        providers
            .gradleProperty("mpmt.scaffold.rewriteChannels")
            .map { it == "true" }
            .orElse(false)

    doLast {
        if (!idProp.isPresent || !groupProp.isPresent || !nameProp.isPresent) {
            throw GradleException(
                "缺少 -P mpmt.scaffold.id / group / name。例：\n" +
                    "  ./gradlew -P mpmt.scaffold.id=mygame " +
                    "-P mpmt.scaffold.group=com.example.mygame -P mpmt.scaffold.name=MyGame " +
                    "-P mpmt.scaffold.dryRun=true renameScaffold",
            )
        }
        val newId = idProp.get()
        val newGroup = groupProp.get()
        val newName = nameProp.get()
        val dryRun = dryRunProp.get()
        val rewriteChannels = rewriteProp.get()

        validateScaffoldId(newId)
        validateScaffoldGroup(newGroup)
        if (newId == oldId && newGroup == oldGroup && newName == oldDisplayName) {
            throw GradleException("新旧身份相同，无需改名")
        }

        val root = rootDir.toPath().toAbsolutePath().normalize()
        if (!Files.isRegularFile(root.resolve("settings.gradle.kts"))) {
            throw GradleException("不像仓库根：$root")
        }

        logger.lifecycle("[renameScaffold] root=$root")
        logger.lifecycle("  id:    $oldId -> $newId")
        logger.lifecycle("  group: $oldGroup -> $newGroup")
        logger.lifecycle("  name:  $oldDisplayName -> $newName")
        logger.lifecycle(
            "  channels: ${if (rewriteChannels) "rewrite" else "keep mpmt:main / MPMT"}",
        )
        logger.lifecycle("  mode:  ${if (dryRun) "DRY-RUN" else "WRITE"}")

        var changedFiles = 0
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    val rel =
                        try {
                            root.relativize(dir)
                        } catch (_: IllegalArgumentException) {
                            return FileVisitResult.CONTINUE
                        }
                    if (shouldSkipRelative(rel)) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val rel =
                        try {
                            root.relativize(file)
                        } catch (_: IllegalArgumentException) {
                            return FileVisitResult.CONTINUE
                        }
                    if (shouldSkipRelative(rel)) {
                        return FileVisitResult.CONTINUE
                    }
                    val name = file.fileName.toString()
                    if (name == "rename_scaffold.py" || name == "scaffold-rename.gradle.kts") {
                        return FileVisitResult.CONTINUE
                    }
                    if (!isTextCandidate(file)) {
                        return FileVisitResult.CONTINUE
                    }
                    val text =
                        try {
                            Files.readString(file)
                        } catch (_: Exception) {
                            return FileVisitResult.CONTINUE
                        }
                    val newText =
                        replaceScaffoldText(
                            text,
                            newGroup = newGroup,
                            newName = newName,
                            newId = newId,
                            rewriteChannels = rewriteChannels,
                        )
                    if (newText != text) {
                        logger.lifecycle("  TEXT $rel")
                        changedFiles++
                        if (!dryRun) {
                            Files.writeString(file, newText)
                        }
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )

        val moves =
            moveJavaPackageTrees(root, newGroup, dryRun) { line ->
                logger.lifecycle("  $line")
            }

        logger.lifecycle(
            "[renameScaffold] done: text_files=$changedFiles, package_moves=$moves",
        )
        if (dryRun) {
            logger.lifecycle(
                "[renameScaffold] dry-run 未写盘。去掉 -P mpmt.scaffold.dryRun=true 执行。",
            )
        } else {
            logger.lifecycle(
                "[renameScaffold] 请运行：./gradlew --no-daemon :core:domain:compileJava " +
                    ":platform:bukkit:common:compileJava 做冒烟。",
            )
        }
    }
}
