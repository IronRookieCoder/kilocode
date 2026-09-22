package ai.kilocode.stability

import java.nio.file.Files
import java.nio.file.Path

/** 同scope陈旧文件保留期（设计7.4）：自最后一次追加起24小时。 */
private const val DEFAULT_MAX_AGE_MS = 24L * 60 * 60 * 1000

private val FILE_NAME_PATTERN = Regex("^[a-z0-9][a-z0-9-]*\\.jsonl$")

/**
 * 陈旧清理（设计7.4）：只删整个文件——同scope-id前缀、自最后追加起超过保留期24小时的
 * 平铺常规.jsonl；本实例活跃文件与其余文件（他scope、非jsonl、子目录）一律不碰。
 * 跨scope残留由cs-cloud按保留期清理，不属于本类职责。无锁、无死亡推断、绝不阻塞业务。
 */
class Retention(
    private val outboxDir: Path,
    private val scopeId: String,
    private val activeFile: Path,
    private val clock: Clock,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) {

    /** 后台入口：目录不存在即no-op（本实例尚未写出任何文件时常态）。 */
    fun sweep() {
        if (!Files.isDirectory(outboxDir)) return
        val now = clock.wall()
        Files.list(outboxDir).use { files ->
            files.filter { path -> Files.isRegularFile(path) }
                .filter { path -> path != activeFile }
                .filter { path ->
                    val name = path.fileName.toString()
                    name.startsWith("$scopeId-") && FILE_NAME_PATTERN.matches(name)
                }
                .filter { path -> now - modifiedAt(path) > maxAgeMs }
                .forEach { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }

    /** mtime读取失败按"永不到期"处理（保守，不误删证据不明文件）。 */
    private fun modifiedAt(path: Path): Long =
        runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(Long.MAX_VALUE)
}
