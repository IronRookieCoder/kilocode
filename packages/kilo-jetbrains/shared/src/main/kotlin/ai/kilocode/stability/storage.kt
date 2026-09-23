package ai.kilocode.stability

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.util.EnumSet

private const val POSIX = "posix"
private const val ACL = "acl"

/** Windows本地系统账户（服务侧），保留其访问以便后台服务消费；查无此名（本地化系统）则省略。 */
private const val SYSTEM_PRINCIPAL = "SYSTEM"

/** 数据文件的POSIX权限（0600）；目录为0700。Windows走ACL校验分支。 */
private val DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
private val FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------")

/**
 * 无法可靠验证存储前提（symlink/reparse越界、所有者非当前用户、权限不可验证）时抛出；
 * writer捕获后禁用采集并本地报告，绝不静默继续写不安全目录。cause保留底层故障供诊断。
 */
class StorageUnverifiedException(val reason: String, cause: Throwable? = null) : RuntimeException(reason, cause)

/**
 * 存储原语（设计5.2/6.1/7.1/7.4）：安全目录校验与创建、事实文件追加打开、原子替换写入。
 * 无登记目录、无锁文件、无.open/.ready状态机——追加协议只有一个平铺jsonl事实文件，
 * 全部方法只在writer的IO线程调用（atomicWrite供writer容量重写使用）。
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
     * 校验并按需创建producer根目录：创建前后均检查目标自身及全部祖先，排除symlink/reparse；
     * 路径解析和权限视图不跟随末级链接，权限修改前再次核验。根目录所有者与权限核验
     * （Windows为配置后核验）通过才返回路径。
     * （每类失败点各一个throw，统一包装为不可验证域异常——见verifyNoLinks前的Suppress说明。）
     */
    @Suppress("ThrowsCount")
    fun verifyLayout(): Path {
        val absolute = root.toAbsolutePath().normalize()
        verifyNoLinks(absolute)
        try {
            Files.createDirectories(absolute)
        } catch (exception: FileAlreadyExistsException) {
            throw StorageUnverifiedException("producer root exists as a non-directory: $absolute", exception)
        } catch (exception: IOException) {
            throw StorageUnverifiedException("producer root cannot be created: ${exception.message}", exception)
        }
        verifyNoLinks(absolute)
        val real = try {
            absolute.toRealPath(LinkOption.NOFOLLOW_LINKS)
        } catch (exception: IOException) {
            throw StorageUnverifiedException("producer root real path unavailable: ${exception.message}", exception)
        }
        // 根目录走"配置后核验"（Windows整体替换最小DACL，POSIX置位0700）；子项只核验（继承）。
        verifyPermissions(real, isDirectory = true, configure = true)
        return real
    }

    /** 追加打开事实文件（存在则追加，绝不truncate；不存在则创建）并核验0600/ACL。 */
    fun openAppend(path: Path): FileChannel {
        verifyNoLinks(path.toAbsolutePath().normalize())
        val channel = try {
            FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)
        } catch (exception: IOException) {
            throw StorageUnverifiedException("outbox file cannot be opened: ${exception.message}", exception)
        }
        return try {
            verifyPermissions(path, isDirectory = false)
            channel
        } catch (exception: StorageUnverifiedException) {
            runCatching { channel.close() }
            throw exception
        }
    }

    /** 安全读取root直系事实文件；缺失或非普通文件视为无内容，链接/权限异常拒绝读取。 */
    fun read(path: Path): ByteArray? {
        val target = path.toAbsolutePath().normalize()
        val parent = root.toAbsolutePath().normalize()
        if (target.parent != parent) throw StorageUnverifiedException("outbox read escapes producer root: $target")
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null
        verifyNoLinks(target)
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return null
        verifyPermissions(target, isDirectory = false)
        return try {
            FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val bytes = ByteArray(channel.size().toInt())
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
                bytes.copyOf(buffer.position())
            }
        } catch (exception: IOException) {
            throw StorageUnverifiedException("outbox file cannot be read: ${exception.message}", exception)
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

    /** force(true)：数据与元数据一并同步（flush=force一步，不可与close交换）。 */
    fun force(channel: FileChannel) {
        beforeForce?.invoke(channel)
        channel.force(true)
    }

    /**
     * 原子写任意文件（容量重写与登记元数据的落盘原语）：同目录临时文件写入并force后
     * 原子改名到目标；不支持原子改名时删除临时文件并失败，不降级普通rename假装成功。
     */
    fun atomicWrite(target: Path, bytes: ByteArray) {
        verifyNoLinks(target.toAbsolutePath().normalize())
        val directory = target.toAbsolutePath().parent
        val temp = Files.createTempFile(directory, target.fileName.toString(), ".tmp")
        var moved = false
        try {
            FileChannel.open(
                temp,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS,
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

// 校验代码刻意把每类失败点收敛为一个throw（底层IOException带cause包装为统一的
// StorageUnverifiedException域异常），白名单式逐项失败比刻意压缩throw数量更可读。

/**
 * 已存在祖先逐一排除symlink；Windows（无POSIX视图）进一步以真实路径等价性侦测
 * junction/reparse（NIO不直接暴露reparse标志）。不存在的中间组件留给创建后的范围核验。
 */
private fun verifyNoLinks(path: Path) {
    val windows = POSIX !in path.fileSystem.supportedFileAttributeViews()
    var current: Path? = path
    while (current != null) {
        checkNoLink(current, windows)
        current = current.parent
    }
}

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

/**
 * 权限核验入口：POSIX显式0700/0600并回读比对；Windows核验ACL实际授权（[configure]时先
 * 整体替换为当前用户最小DACL再核验）；其余模型拒绝。所有者必须是当前用户（[verifyOwner]并入）。
 */
@Suppress("ThrowsCount")
private fun verifyPermissions(path: Path, isDirectory: Boolean, configure: Boolean = false) {
    verifyNoLinks(path.toAbsolutePath().normalize())
    val views = path.fileSystem.supportedFileAttributeViews()
    when {
        POSIX in views -> verifyPosix(path, isDirectory)
        ACL in views -> if (configure) configureAcl(path) else verifyAcl(path)
        else -> throw StorageUnverifiedException("no verifiable permission model for $path")
    }
    val owner = try {
        Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).name
    } catch (exception: IOException) {
        throw StorageUnverifiedException("owner unavailable for $path: ${exception.message}", exception)
    }
    if (!principalMatches(owner, System.getProperty("user.name"))) {
        throw StorageUnverifiedException("owner '$owner' is not the current user for $path")
    }
}

@Suppress("ThrowsCount")
private fun verifyPosix(path: Path, isDirectory: Boolean) {
    val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
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
    val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        ?: throw StorageUnverifiedException("acl view unavailable for $path")
    val acl = try {
        view.acl
    } catch (exception: IOException) {
        throw StorageUnverifiedException("acl unreadable for $path: ${exception.message}", exception)
    }
    val ownerAllowed = acl.any { entry ->
        entry.type() == AclEntryType.ALLOW &&
            (entry.principal() == view.owner || principalMatches(entry.principal().name, view.owner.name)) &&
            entry.permissions().containsAll(setOf(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA))
    }
    if (!ownerAllowed) {
        throw StorageUnverifiedException("no allow entry for the current user on $path")
    }
}

/**
 * Windows根目录ACL"配置后核验"（设计5.2：使用AclFileAttributeView配置当前用户ACL并检查
 * 继承后的实际权限）：以最小DACL整体替换继承自父目录的授权（当前用户完全控制，SYSTEM尽力
 * 保留，条目对子目录/文件可继承），随后重读核验生效DACL与所设条目逐条一致——父目录（如
 * 临时目录）对其他本地主体的宽授权不再生效。配置失败按不可验证禁采。
 */
@Suppress("ThrowsCount")
private fun configureAcl(path: Path) {
    val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        ?: throw StorageUnverifiedException("acl view unavailable for $path")
    val configured = minimalAcl(view, path)
    try {
        view.setAcl(configured)
    } catch (exception: IOException) {
        throw StorageUnverifiedException("acl cannot be configured for $path: ${exception.message}", exception)
    }
    verifyAcl(path)
    val effective = try {
        view.acl
    } catch (exception: IOException) {
        throw StorageUnverifiedException("acl unreadable for $path: ${exception.message}", exception)
    }
    if (!aclMatches(effective, configured)) {
        throw StorageUnverifiedException("effective acl on $path does not match the configured minimal dacl")
    }
}

/** 生效DACL与所设条目一致性：条目数一致且每条（主体/类型/标志/权限）都能在所设条目中找到。 */
private fun aclMatches(effective: List<AclEntry>, configured: List<AclEntry>): Boolean =
    effective.size == configured.size && effective.all { entry ->
        configured.any { wanted ->
            entry.type() == wanted.type() &&
                entry.principal() == wanted.principal() &&
                entry.flags() == wanted.flags() &&
                entry.permissions() == wanted.permissions()
        }
    }

/** 最小DACL：当前用户完全控制 + SYSTEM（尽力保留，本地化系统查无此名时省略），条目可被子项继承。 */
private fun minimalAcl(view: AclFileAttributeView, path: Path): List<AclEntry> {
    val inheritable = EnumSet.of(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT)
    val fullControl = EnumSet.allOf(AclEntryPermission::class.java)
    fun entry(principal: UserPrincipal) = AclEntry.newBuilder()
        .setPrincipal(principal)
        .setType(AclEntryType.ALLOW)
        .setFlags(inheritable)
        .setPermissions(fullControl)
        .build()

    val entries = mutableListOf(entry(view.owner))
    runCatching { path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(SYSTEM_PRINCIPAL) }
        .getOrNull()
        ?.let { system -> entries += entry(system) }
    return entries
}

/** 域限定名与本地名互认：全名相等或去掉域前缀（\\或/之后）后相等（大小写不敏感）。 */
private fun principalMatches(name: String, user: String): Boolean {
    if (name.equals(user, ignoreCase = true)) return true
    val shortName = name.substringAfterLast('\\').substringAfterLast('/')
    return shortName.equals(user, ignoreCase = true)
}
