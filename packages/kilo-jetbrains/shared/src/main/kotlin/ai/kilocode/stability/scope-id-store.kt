package ai.kilocode.stability

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

private const val SIZE = 15
private val pattern = Regex("^sc-[0-9a-f]{12}$")

/**
 * IDE配置范围内的同步scope存储：写完临时文件并force后才原子发布，失败不返回随机替代值。
 * JVM内不同store共用创建锁；共享配置目录的多JVM运行仍依赖IDE单实例约束，不在支持范围内。
 */
internal class FileScopeIdStore(private val config: Path) : ScopeIdStore {
    private val file = config.resolve("kilo-stability-scope-id")
    private val id by lazy { synchronized(lock) { load() } }

    override fun loadOrCreate(): String = id

    private fun load(): String {
        Files.createDirectories(config)
        val stored = read()
        if (stored != null) return stored
        val id = "sc-" + randomId()
        val temp = Files.createTempFile(config, "kilo-stability-scope-", ".tmp")
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(id.toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return id
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun read(): String? {
        val attrs = runCatching {
            Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrElse { err ->
            if (err !is NoSuchFileException) throw err
            null
        }
        if (attrs != null && !attrs.isRegularFile) throw IOException("scope storage is not a regular file")
        if (attrs == null || attrs.size() != SIZE.toLong()) return null
        val value = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(SIZE)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
            String(buffer.array(), 0, buffer.position(), Charsets.UTF_8)
        }
        return value.takeIf(pattern::matches)
    }

    private companion object {
        val lock = Any()
    }
}
