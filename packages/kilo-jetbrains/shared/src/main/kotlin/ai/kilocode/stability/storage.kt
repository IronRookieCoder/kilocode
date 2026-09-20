package ai.kilocode.stability

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

private const val POSIX = "posix"
private const val ACL = "acl"
private const val WRITER_LOCK_NAME = "writer.lock"
private const val EXCHANGE_LOCK_NAME = "exchange.lock"
private const val LOCK_RANGE_OFFSET = 0L
private const val LOCK_RANGE_SIZE = 1L
private const val CHANNEL_CRITICAL = "critical"
private const val CHANNEL_DIAGNOSTIC = "diagnostic"

/** 数据文件与锁文件的POSIX权限（0600）；目录为0700。Windows走ACL校验分支。 */
private val DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
private val FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------")

/**
 * 无法可靠验证存储前提（symlink/reparse越界、所有者非当前用户、权限不可验证）时抛出；
 * writer捕获后禁用采集并本地报告，绝不静默继续写不安全目录。cause保留底层故障供诊断。
 */
class StorageUnverifiedException(val reason: String, cause: Throwable? = null) : RuntimeException(reason, cause)

/**
 * 存储原语（设计5.2/7.1/7.2）：安全目录校验与创建、段文件与锁文件、原子封存改名、
 * 供A5登记文件复用的原子写入。所有方法只在writer的IO线程调用（atomicWrite由A5后台调用）。
 *
 * 权限模型：POSIX文件系统显式置0700/0600并回读核验；Windows经AclFileAttributeView核验
 * 所有者为当前用户且存在对当前用户的ALLOW条目（含读/写数据权限，继承自父目录的实际权限）。
 * 任何无法核验的情形都以[StorageUnverifiedException]失败——调用方禁用采集，不降级继续。
 *
 * 故障注入口（beforeWrite/beforeForce/beforeMove）仅供测试与G1平台矩阵使用：注入真实故障
 * （关闭通道、占位目标目录），从不伪造成功；生产代码永远读到的都是null。
 */
class Storage(private val root: Path) {

    @Volatile var beforeWrite: ((FileChannel) -> Unit)? = null
    @Volatile var beforeForce: ((FileChannel) -> Unit)? = null
    @Volatile var beforeMove: ((Path, Path) -> Unit)? = null

    /**
     * 校验并按需创建producer根目录：已存在祖先逐一排除symlink/reparse，创建后真实路径必须
     * 仍落在已核验祖先之内（真实路径范围），根目录所有者与权限核验通过才返回真实路径。
     * （每类失败点各一个throw，统一包装为不可验证域异常——见checkNoLink前的Suppress说明。）
     */
    @Suppress("ThrowsCount")
    fun verifyLayout(): Path {
        val absolute = root.toAbsolutePath().normalize()
        val anchor = existingAncestor(absolute)
        verifyNoLinks(anchor, absolute.parent)
        try {
            Files.createDirectories(absolute)
        } catch (exception: FileAlreadyExistsException) {
            throw StorageUnverifiedException("producer root exists as a non-directory: $absolute", exception)
        } catch (exception: IOException) {
            throw StorageUnverifiedException("producer root cannot be created: ${exception.message}", exception)
        }
        val real = try {
            absolute.toRealPath()
        } catch (exception: IOException) {
            throw StorageUnverifiedException("producer root real path unavailable: ${exception.message}", exception)
        }
        if (!real.startsWith(anchor.toRealPath())) {
            throw StorageUnverifiedException("producer root escapes the verified ancestor: $real")
        }
        verifyPermissions(real, isDirectory = true)
        return real
    }

    /** 通道目录（critical/diagnostic）按需创建并核验0700/ACL；失败按不可验证处理。 */
    fun channelDirectory(channel: String): Path {
        require(channel == CHANNEL_CRITICAL || channel == CHANNEL_DIAGNOSTIC) { "unknown channel '$channel'" }
        val dir = root.resolve(channel)
        try {
            Files.createDirectories(dir)
        } catch (exception: IOException) {
            throw StorageUnverifiedException("channel directory cannot be created: ${exception.message}", exception)
        }
        verifyPermissions(dir, isDirectory = true)
        return dir
    }

    /** 打开段文件（存在则追加，绝不truncate既有数据）并核验0600/ACL。 */
    fun openSegment(path: Path): FileChannel {
        val channel = try {
            FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
        } catch (exception: IOException) {
            throw StorageUnverifiedException("segment file cannot be opened: ${exception.message}", exception)
        }
        return try {
            verifyPermissions(path, isDirectory = false)
            channel
        } catch (exception: StorageUnverifiedException) {
            runCatching { channel.close() }
            throw exception
        }
    }

    /** 写循环：直到ByteBuffer耗尽；返回写入字节数。负返回值视为底层故障。 */
    fun writeAll(channel: FileChannel, buffer: ByteBuffer): Int {
        beforeWrite?.invoke(channel)
        var written = 0
        while (buffer.hasRemaining()) {
            val count = channel.write(buffer)
            if (count < 0) throw IOException("segment channel closed while writing")
            written += count
        }
        return written
    }

    /** force(true)：数据与元数据一并同步（brief Step 3顺序第一步，不可与close交换）。 */
    fun force(channel: FileChannel) {
        beforeForce?.invoke(channel)
        channel.force(true)
    }

    /**
     * 原子封存：同目录.open→.ready。文件系统不支持原子改名时抛出
     * [AtomicMoveNotSupportedException]——调用方保留.open并记write_error，绝不降级普通rename。
     */
    fun seal(open: Path, ready: Path) {
        beforeMove?.invoke(open, ready)
        Files.move(open, ready, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * writer.lock：FileChannel字节范围[0,1)独占锁（G0建议的JVM侧），持有整个采集生命周期。
     * 锁文件只创建一次，从不unlink/recreate（第7.4章：他人可能持有或打开它）。
     */
    @Suppress("ThrowsCount")
    fun acquireWriterLock(): FileChannel {
        val lockChannel = openLockFile(root.resolve(WRITER_LOCK_NAME))
        return try {
            // 同JVM已有writer持锁→OverlappingFileLockException；跨进程持锁→tryLock返回null。
            lockChannel.tryLock(LOCK_RANGE_OFFSET, LOCK_RANGE_SIZE, false)
                ?: throw IOException("writer.lock is held by another writer")
            lockChannel
        } catch (locked: OverlappingFileLockException) {
            runCatching { lockChannel.close() }
            throw locked
        } catch (exception: IOException) {
            runCatching { lockChannel.close() }
            throw exception
        }
    }

    /** exchange.lock只保证文件存在（消费端/清理端在A5后续任务使用），writer从不锁它。 */
    fun ensureExchangeLock(): Path {
        val path = root.resolve(EXCHANGE_LOCK_NAME)
        openLockFile(path).close()
        return path
    }

    /**
     * 原子写任意文件（producer.json等登记元数据的落盘原语）：同目录临时文件写入并force后
     * 原子改名到目标；不支持原子改名时删除临时文件并失败，不降级普通rename假装成功。
     */
    fun atomicWrite(target: Path, bytes: ByteArray) {
        val directory = target.toAbsolutePath().parent
        val temp = Files.createTempFile(directory, target.fileName.toString(), ".tmp")
        var moved = false
        try {
            FileChannel.open(
                temp,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                writeAll(channel, ByteBuffer.wrap(bytes))
                force(channel)
            }
            verifyPermissions(temp, isDirectory = false)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            moved = true
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw IOException("atomic rename unavailable for ${target.fileName}", unsupported)
        } finally {
            if (!moved) runCatching { Files.deleteIfExists(temp) }
        }
    }
}

/** 自[root]向上找最近的已存在祖先（至多到文件系统根），作为可信锚点。 */
private fun existingAncestor(path: Path): Path {
    var current: Path = path
    while (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        current = current.parent ?: return current
    }
    return current
}

/**
 * 已存在祖先逐一排除symlink；Windows（无POSIX视图）进一步以真实路径等价性侦测
 * junction/reparse（NIO不直接暴露reparse标志）。不存在的中间组件留给创建后的范围核验。
 */
private fun verifyNoLinks(anchor: Path, bottom: Path?) {
    val start = bottom ?: return
    val windowsLike = POSIX !in start.fileSystem.supportedFileAttributeViews()
    var current: Path = start
    while (current != anchor) {
        checkNoLink(current, windowsLike)
        current = current.parent ?: return
    }
    checkNoLink(anchor, windowsLike)
}

// 校验代码刻意把每类失败点收敛为一个throw（底层IOException带cause包装为统一的
// StorageUnverifiedException域异常），白名单式逐项失败比刻意压缩throw数量更可读。
@Suppress("ThrowsCount")
private fun checkNoLink(path: Path, windowsLike: Boolean) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    if (Files.isSymbolicLink(path)) {
        throw StorageUnverifiedException("symbolic link in the storage path: $path")
    }
    if (!windowsLike) return
    val real = try {
        path.toRealPath()
    } catch (exception: IOException) {
        throw StorageUnverifiedException("real path unavailable for $path: ${exception.message}", exception)
    }
    if (!real.toString().equals(path.toString(), ignoreCase = true)) {
        throw StorageUnverifiedException("reparse point or link in the storage path: $path")
    }
}

/** 权限核验入口：POSIX显式0700/0600并回读比对；Windows核验ACL实际授权；其余模型拒绝。 */
private fun verifyPermissions(path: Path, isDirectory: Boolean) {
    val views = path.fileSystem.supportedFileAttributeViews()
    when {
        POSIX in views -> verifyPosix(path, isDirectory)
        ACL in views -> verifyAcl(path)
        else -> throw StorageUnverifiedException("no verifiable permission model for $path")
    }
    verifyOwner(path)
}

@Suppress("ThrowsCount")
private fun verifyPosix(path: Path, isDirectory: Boolean) {
    val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        ?: throw StorageUnverifiedException("posix view unavailable for $path")
    val expected = if (isDirectory) DIRECTORY_PERMISSIONS else FILE_PERMISSIONS
    try {
        view.setPermissions(expected)
        val actual = view.readAttributes().permissions()
        if (actual != expected) {
            throw StorageUnverifiedException("permissions $actual are not $expected for $path")
        }
    } catch (exception: IOException) {
        throw StorageUnverifiedException("posix permissions unverifiable for $path: ${exception.message}", exception)
    }
}

/**
 * Windows ACL核验（设计5.2：不能把chmod数值当作Windows权限实现）：所有者必须是当前用户，
 * 且ACL存在对当前用户的ALLOW条目（继承后的实际权限）授予读/写数据。
 */
@Suppress("ThrowsCount")
private fun verifyAcl(path: Path) {
    val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
        ?: throw StorageUnverifiedException("acl view unavailable for $path")
    val acl = try {
        view.acl
    } catch (exception: IOException) {
        throw StorageUnverifiedException("acl unreadable for $path: ${exception.message}", exception)
    }
    val ownerAllowed = acl.any { entry -> entryForOwner(entry, view) }
    if (!ownerAllowed) {
        throw StorageUnverifiedException("no allow entry for the current user on $path")
    }
}

/** 所有者条目校验：ALLOW类型 + 当前用户（对象等价或名称互认）+ 实际授予读/写数据权限。 */
private fun entryForOwner(entry: AclEntry, view: AclFileAttributeView): Boolean =
    entry.type() == AclEntryType.ALLOW &&
        (entry.principal() == view.owner || principalMatches(entry.principal().name, view.owner.name)) &&
        entry.permissions().containsAll(setOf(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA))

private fun verifyOwner(path: Path) {
    val owner = try {
        Files.getOwner(path).name
    } catch (exception: IOException) {
        throw StorageUnverifiedException("owner unavailable for $path: ${exception.message}", exception)
    }
    if (!principalMatches(owner, System.getProperty("user.name"))) {
        throw StorageUnverifiedException("owner '$owner' is not the current user for $path")
    }
}

/** 域限定名与本地名互认：全名相等或去掉域前缀（\\或/之后）后相等（大小写不敏感）。 */
private fun principalMatches(name: String, user: String): Boolean {
    if (name.equals(user, ignoreCase = true)) return true
    val shortName = name.substringAfterLast('\\').substringAfterLast('/')
    return shortName.equals(user, ignoreCase = true)
}

/** 锁文件只创建一次：不存在则CREATE_NEW，已存在则原样打开，绝不unlink/recreate。 */
private fun openLockFile(path: Path): FileChannel = try {
    FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE)
} catch (_: FileAlreadyExistsException) {
    FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)
}
