
package synchro

import javafx.scene.control.Alert
import mu.KotlinLogging
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.common.StreamCopier.Listener
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.sftp.*
import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.xfer.FilePermission
import net.schmizz.sshj.xfer.FileSystemFile
import net.schmizz.sshj.xfer.TransferListener
import store.DBSettings
import store.Protocol
import store.SettingsStore
import util.Helpers
import util.Helpers.dialogMessage
import util.Helpers.dialogOkCancel
import util.Helpers.runUIwait
import util.MFile
import java.io.Closeable
import java.io.IOException
import java.io.Serializable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.PublicKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max


// DON'T call stuff here from UI thread, can lock!

private val logger = KotlinLogging.logger {}

class MyURI(var protocol: String, var username: String, var host: String, var port: Int) {
    constructor() : this("", "", "", -1)

    private fun parseString(s: String): Boolean {
        val res = Helpers.uriRegex.find(s)!!.groupValues.filter { it.isNotEmpty() }
        return when {
            res[1] == "file" -> { protocol = "file"; true }
            res[1] == "sftp" -> {
                protocol = "sftp"
                username = res[2]
                host = res[3]
                port = res.getOrElse(4) { ":22" }.removePrefix(":").toInt()
                true
            }
            else -> false
        }
    }

    override fun toString(): String = "$protocol,$username,$host,$port"

    constructor(s: String) : this() {
        if (!this.parseString(s))
            throw RuntimeException("URI in wrong format: $s")
    }
}

// if ends on "/", is dir except for "" which is also dir (basepath)
// always below remoteBasePath!
class VirtualFile(path: String, var modTime: Long, var size: Long, var permissions: MutableSet<PosixFilePermission> = mutableSetOf()) : Comparable<VirtualFile>, Serializable {
    var path: String = MFile.normalizePath(path)

    fun getFileName(): String = MFile.getIPFileName(path) // gets file/folder name, "" if "/" or "", without trailing "/" for dirs!
    fun getPermString(): String = PosixFilePermissions.toString(permissions)
    fun getParent(): String = MFile.getIPFileParent(path).let { if (it.endsWith("/")) it else "$it/" }
    fun getFileNameBrowser(): String = MFile.getIPFileName(path) + if (isDir()) "/" else ""
    fun getFileExtension(): String = MFile.getIPFileExt(path)
    fun isNotFiltered(filterregexp: String) = !(filterregexp.isNotEmpty() && path.matches(filterregexp.toRegex()))

    override fun toString(): String = "[$path]:$modTime,$size"

    companion object {
        fun isDir(p: String): Boolean = p.endsWith("/") || p == ""
    }

    fun isDir(): Boolean = isDir(path)
    fun isFile(): Boolean = !isDir()

    override fun equals(other: Any?): Boolean {
        if (other is VirtualFile) if (this.path == other.path && this.modTime == other.modTime && this.size == other.size) return true
        return false
    }

    override fun hashCode(): Int = (path + modTime.toString() + size.toString()).hashCode()

    override fun compareTo(other: VirtualFile): Int = path.compareTo(other.path)
}

// open one connection for each (remotebasepath, onprogress,..)
// subfolder should NOT start or end with /
abstract class GeneralConnection(val protocol: Protocol) {
    private val _nextid = AtomicLong(0) // to have unique id of object
    val id = _nextid.getAndIncrement()
    var remoteBasePath: String = protocol.baseFolder.value
    var permsOverride: String = ""
    protected val debugslow = false
    val interrupted = AtomicBoolean(false)
    abstract fun getfile(localBasePath: String, from: String, mtime: Long, to: String)
    abstract fun getfile(localBasePath: String, from: String, mtime: Long)
    // returns mtime if cantSetDate ; if remotePath not empty: take this as absolute file path!
    abstract fun putfile(localBasePath: String, from: String, mtime: Long, remotePath: String = ""): Long
    abstract fun mkdirrec(absolutePath: String, addRemoteBasePath: Boolean = false)
    abstract fun deletefile(what: String)
    abstract fun list(subfolder: String, filterregexp: String, recursive: Boolean, resolveSymlinks: Boolean, action: (VirtualFile) -> Boolean) // action->false: interrupt!
    abstract fun listSingleFile(remotePath: String): VirtualFile?
    abstract fun isAlive(): Boolean
    abstract fun cleanUp()

    // extended functions only for some connections
    abstract fun canRename(): Boolean
    abstract fun canChmod(): Boolean
    abstract fun canDuplicate(): Boolean
    open fun extRename(oldPath: String, newPath: String) { throw Exception("Can't rename") }
    open fun extChmod(path: String, newPerms: Set<PosixFilePermission>) { throw Exception("Can't chmod") }
    open fun extDuplicate(oldPath: String, newPath: String) { throw Exception("Can't duplicate") }

    fun assignRemoteBasePath(remoteFolder: String) {
        remoteBasePath = protocol.baseFolder.value + remoteFolder
    }

    var onProgress: (progressVal: Double, bytePerSecond: Double) -> Unit = { _, _ -> }

    // return dir (most likely NOT absolute path but subfolder!) without trailing /
    fun checkIsDir(path: String): Pair<String, Boolean> {
        val isdir = path.endsWith("/") || path == ""
        val resp = if (isdir && path != "") path.substring(0, path.length - 1) else path
        return Pair(resp, isdir)
    }

    // helper for browserview
    fun listRecursively(what: List<VirtualFile>): ArrayList<VirtualFile> {
        val fff = arrayListOf<VirtualFile>()
        what.forEach { selvf ->
            if (selvf.isDir())
                list(selvf.path, "", recursive = true, resolveSymlinks = true) { fff += it ; true } // TODO make interruptible
            else fff += selvf
        }
        return fff
    }

}

class LocalConnection(protocol: Protocol) : GeneralConnection(protocol) {
    override fun canRename(): Boolean = true
    override fun canChmod(): Boolean = true
    override fun canDuplicate(): Boolean = true
    override fun extRename(oldPath: String, newPath: String) {
        val (cp, _) = checkIsDir(oldPath)
        logger.debug("locrename: $remoteBasePath$cp -> $remoteBasePath$newPath ")
        MFile.move("$remoteBasePath$cp", "$remoteBasePath$newPath")
    }
    override fun extChmod(path: String, newPerms: Set<PosixFilePermission>) {
        MFile("$remoteBasePath$path").setPosixFilePermissions(newPerms)
    }

    override fun extDuplicate(oldPath: String, newPath: String) {
        val (cp, _) = checkIsDir(oldPath)
        MFile.copy("$remoteBasePath$cp", "$remoteBasePath$newPath", StandardCopyOption.COPY_ATTRIBUTES)
    }

    override fun cleanUp() {}

    override fun deletefile(what: String) {
        val (cp, _) = checkIsDir(what)
        val fp = MFile("$remoteBasePath$cp")
        try {
            fp.deleteThrow()
        } catch (e: DirectoryNotEmptyException) {
            val dir = fp.newDirectoryStreamList()
            if (runUIwait {
                        dialogOkCancel("Warning", "Directory \n $cp \n not empty, DELETE ALL?", "Content:\n" +
                                dir.asSequence().map { it.name }.joinToString("\n"))
                    }) {
                dir.forEach { it.deleteThrow() }
                fp.deleteThrow()
                return
            }
        }
    }

    override fun putfile(localBasePath: String, from: String, mtime: Long, remotePath: String): Long {
        val (cp, isdir) = checkIsDir(from)
        val remoteabspath = if (remotePath == "") "$remoteBasePath$cp" else "$remoteBasePath$remotePath"
        logger.debug("putfile: from=$from isdir=$isdir remabspath=$remoteabspath")
        if (isdir) { // ensure that target path exists
            if (!MFile(remoteabspath).parent()!!.exists()) {
                logger.debug("creating folder $cp")
                mkdirrec(MFile(remoteabspath).parent()!!.internalPath)
            }
        }
        MFile.copy("$localBasePath$cp", remoteabspath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        return mtime
    }

    // here, "to" is a full path!
    override fun getfile(localBasePath: String, from: String, mtime: Long, to: String) {
        val (cp, isdir) = checkIsDir(from)
        if (isdir) { // ensure that target path exists
            MFile(to).createDirectories() // simply create parents if necessary, avoids separate check
        } else {
            MFile(to).parent()!!.createDirectories()
            MFile.copy("$remoteBasePath$cp", to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        }
        MFile(to).setLastModifiedTime(mtime)
    }

    override fun getfile(localBasePath: String, from: String, mtime: Long) {
        val (cp, _) = checkIsDir(from)
        val lp = "$localBasePath$cp"
        getfile(localBasePath, from, mtime, lp)
    }

    private fun stripPath(full: String) = if (full == remoteBasePath.dropLast(1)) "" else full.substring(remoteBasePath.length)

    // include the subfolder but root "/" is not allowed!
    override fun list(subfolder: String, filterregexp: String, recursive: Boolean, resolveSymlinks: Boolean, action: (VirtualFile) -> Boolean) {
        logger.debug("listrec(rbp=$remoteBasePath sf=$subfolder rec=$recursive) in thread ${Thread.currentThread().threadId()}")
        fun parseContent(cc: MFile, goDeeper: Boolean, forceFollowSymlinks: Boolean = false) {
            val linkOptions = if (resolveSymlinks || forceFollowSymlinks) arrayOf() else arrayOf(LinkOption.NOFOLLOW_LINKS)
            var strippedPath: String = stripPath(cc.internalPath)
            if (cc.isDirectory(*linkOptions) && strippedPath != "" && !strippedPath.endsWith("/")) strippedPath += "/"
            if (cc.isDirectory(*linkOptions) || cc.isRegularFile(*linkOptions)) {
                val vf = VirtualFile(strippedPath, cc.getLastModifiedTime(), cc.getSize(),
                        cc.getPosixFilePermissions())
                if (vf.isNotFiltered(filterregexp)) {
                    if (debugslow) Thread.sleep(500)
                    if (!action(vf)) throw InterruptedException("recursive listing interrupted!")
                    if (cc.isDirectory(*linkOptions) && goDeeper) {
                        val dir = cc.newDirectoryStreamList()
                        for (cc1 in dir) parseContent(cc1, goDeeper = recursive)
                    }
                }
            }
        }

        val sp = MFile(remoteBasePath + subfolder)
        if (sp.exists()) {
            parseContent(sp, goDeeper = true, forceFollowSymlinks = true)
        }
        logger.debug("listrec DONE (rbp=$remoteBasePath sf=$subfolder rec=$recursive) in thread ${Thread.currentThread().threadId()}")
    }

    override fun listSingleFile(remotePath: String): VirtualFile? {
        logger.debug("listsinglefile: $remotePath")
        val sp = MFile(remoteBasePath + remotePath)
        return if (sp.exists()) VirtualFile(stripPath(sp.internalPath), sp.getLastModifiedTime(), sp.getSize(), sp.getPosixFilePermissions()) else null
    }

    override fun mkdirrec(absolutePath: String, addRemoteBasePath: Boolean) {
        MFile(if (addRemoteBasePath) "$remoteBasePath$absolutePath" else absolutePath).createDirectories()
    }

    override fun isAlive() = true
}

class SftpConnection(protocol: Protocol) : GeneralConnection(protocol) {

    private val uri = protocol.getmyuri()

    override fun canRename(): Boolean = true
    override fun canChmod(): Boolean = true
    override fun canDuplicate(): Boolean = false
    override fun extRename(oldPath: String, newPath: String) {
        val (cp, _) = checkIsDir(oldPath)
        logger.debug("sftprename: $remoteBasePath$cp -> $remoteBasePath$newPath ")
        sftpc.rename("$remoteBasePath$cp", "$remoteBasePath$newPath")
    }
    override fun extChmod(path: String, newPerms: Set<PosixFilePermission>) {
        val rp = "$remoteBasePath$path"
        val fab = FileAttributes.Builder()
        fab.withPermissions(posix2filePermissions(newPerms))
        sftpc.setattr(rp, fab.build())
    }

    inner class MyTransferListener(private var relPath: String = "") : TransferListener {
        var bytesTransferred: Long = 0
        private var lastBytesTransferred: Long = 0
        var bytesTotal: Long = 0
        private var lastTime: Long = 0

        override fun directory(name: String) =
                MyTransferListener("$relPath$name/")

        override fun file(name: String, size: Long): Listener {
            bytesTotal = size
            bytesTransferred = 0
            lastBytesTransferred = 0
            lastTime = System.nanoTime()
            return Listener { transferred ->
                bytesTransferred = transferred
                if (interrupted.get()) throw InterruptedException("sftp connection interrupted")
                val tnow = System.nanoTime()
                if ((tnow - lastTime) / 1.0e9 > 0.5) {
                    val byps = (bytesTransferred - lastBytesTransferred) / ((tnow - lastTime) / 1.0e9)
                    lastTime = tnow
                    lastBytesTransferred = bytesTransferred
                    onProgress(bytesTransferred.toDouble() / bytesTotal, byps)
                }
            }
        }
    }

    override fun deletefile(what: String) {
        val (cp, isdir) = checkIsDir(what)
        if (isdir) {
            try {
                sftpc.rmdir("$remoteBasePath$cp")
            } catch (e: IOException) { // unfortunately only "Failure" ; checking for content would be slow
                val xx = sftpc.ls("$remoteBasePath$cp")
                if (xx.isNotEmpty()) {
                    val tmp = mutableListOf<RemoteResourceInfo>()
                    for (obj in xx) {
                        val lse = obj as RemoteResourceInfo
                        if (lse.name != "." && lse.name != "..") tmp += lse
                    }
                    if (runUIwait {
                                dialogOkCancel("Warning", "Directory \n $cp \n not empty, DELETE ALL?", "Content:\n" +
                                        tmp.asSequence().map { it.name }.joinToString("\n"))
                            }) {
                        tmp.forEach { sftpc.rm(remoteBasePath + cp + "/" + it.name) }
                        sftpc.rmdir("$remoteBasePath$cp")
                        return
                    }
                }
            }
        } else {
            sftpc.rm("$remoteBasePath$cp")
        }
    }

    // need to create a sftpfiletransfer & transferlisteder for each thread!
    private fun getSftpSlowTransfer(): Pair<MyTransferListener, SFTPFileTransfer> {
        val transferListener = MyTransferListener()
        val sftpt = sftpc.fileTransfer
        sftpt.preserveAttributes =
            false // don't set permissions from local, mostly doesn't make sense! Either by user or not at all.
        sftpt.transferListener = transferListener
        return Pair(transferListener, sftpt)
    }

    override fun putfile(localBasePath: String, from: String, mtime: Long, remotePath: String): Long {
        val (transferListener, sftpt) = getSftpSlowTransfer()
        val (cp, isdir) = checkIsDir(from)
        val rp = if (remotePath == "") "$remoteBasePath$cp" else "$remoteBasePath$remotePath"
        logger.debug("putfile: from=$from isdir=$isdir rp=$rp")

        fun setAttr(changeperms: Boolean, rp: String) {
            val lf = FileSystemFile("$localBasePath$cp")
            val fab = FileAttributes.Builder()
            if (changeperms) {
                val perms = sftpc.perms(rp).toMutableList()
                Helpers.permissionsParseRegex.findAll(protocol.perms.value).forEach { r ->
                    val fp = when(r.groupValues[1] + r.groupValues[3]) {
                        "ur" -> FilePermission.USR_R
                        "uw" -> FilePermission.USR_W
                        "ux" -> FilePermission.USR_X
                        "gr" -> FilePermission.GRP_R
                        "gw" -> FilePermission.GRP_W
                        "gx" -> FilePermission.GRP_X
                        "or" -> FilePermission.OTH_R
                        "ow" -> FilePermission.OTH_W
                        "ox" -> FilePermission.OTH_X
                        else -> throw Exception("should not happen")
                    }
                    if (r.groupValues[2] == "+") perms.add(fp) else perms.remove(fp)
                }
                fab.withPermissions(perms.toSet())
            }
            fab.withAtimeMtime(lf.lastAccessTime, lf.lastModifiedTime)
            sftpc.setattr(rp, fab.build())
        }

        return if (isdir) {
            mkdirrec(rp)
            if (protocol.doSetPermissions.value) setAttr(true, rp)
            mtime // dirs don't need mtime
        } else {
            try {
                if (!MFile("$localBasePath$cp").isReadable()) throw IllegalStateException("can't read file $cp")
                sftpt.upload("$localBasePath$cp", rp) // use this in place of sftpc.put to not always set file attrs
                if (protocol.doSetPermissions.value) setAttr(true, rp)
                else if (!protocol.cantSetDate.value) setAttr(false, rp)
            } catch (e: Exception) {
                logger.debug("putfile: exception: $e")
                if (transferListener.bytesTransferred > 0) { // file may be corrupted, but don't delete if nothing transferred
                    // prevent delete of root-owned files if user in group admin, sftp rm seems to "override permission"
                    sftpc.rm(rp)
                }
                throw e
            }
            if (transferListener.bytesTotal != transferListener.bytesTransferred)
                throw IllegalStateException("filesize mismatch: ${transferListener.bytesTotal} <> ${transferListener.bytesTransferred}")
            if (protocol.cantSetDate.value) {
                sftpc.mtime(rp) * 1000
            } else {
                mtime
            }
        }
    }

    override fun getfile(localBasePath: String, from: String, mtime: Long, to: String) {
        val (transferListener, sftpt) = getSftpSlowTransfer()

        val (cp, isdir) = checkIsDir(from)
        val tof = MFile(to)
        if (isdir) {
            tof.createDirectories() // simply create parents if necessary, avoids separate check
            tof.setLastModifiedTime(mtime)
        } else {
            tof.parent()?.let { if (!it.exists()) it.createDirectories() }
            // copy-then-move: sftpt.download erases local file if it exists also if remote file can't be read
            val tmpf = MFile.createTempFile("sfsync-tempfile", ".dat")
            sftpt.download("$remoteBasePath$cp", tmpf.internalPath)
            MFile.move(tmpf.internalPath, to, StandardCopyOption.REPLACE_EXISTING)
            if (transferListener.bytesTotal != transferListener.bytesTransferred)
                throw IllegalStateException("filesize mismatch: ${transferListener.bytesTotal} <> ${transferListener.bytesTransferred}")
            tof.setLastModifiedTime(mtime)
        }
    }

    override fun getfile(localBasePath: String, from: String, mtime: Long) {
        val (cp, _) = checkIsDir(from)
        val lp = "$localBasePath$cp"
        getfile(localBasePath, from, mtime, lp)
    }

    private fun sftpexists(sp: String): FileAttributes? {
        var resls: FileAttributes? = null
        try {
            resls = sftpc.stat(sp) // throws exception if not
        } catch (e: SFTPException) {
            if (e.statusCode == StatusCode.NO_SUCH_FILE) logger.debug("no such file <$sp>: ${e.message}") else throw(e)
        }
        return resls
    }

    override fun list(subfolder: String, filterregexp: String, recursive: Boolean, resolveSymlinks: Boolean, action: (VirtualFile) -> Boolean) {
        logger.debug("listrecsftp(rbp=$remoteBasePath sf=$subfolder rec=$recursive fil=$filterregexp) in thread ${Thread.currentThread().threadId()}")

        fun doaction(rripath: String, rriattributesini: FileAttributes, parsealways: Boolean = false, parseContentFun: (String) -> Unit) {

            var rriattributes = rriattributesini

            if (rriattributes.type == FileMode.Type.SYMLINK && resolveSymlinks) { // resolve symlink. use target attrs but link path.
                var cp = rripath
                logger.debug("sftp: resolving symlink $cp...")
                do {
                    sftpc.readlink(cp).let {
                        cp = if (!it.startsWith("/")) "${MFile(cp).parent()!!.internalPath}/$it" else it
                    }
                    logger.debug("sftp: resolving symlink, next: $cp")
                    try { rriattributes = sftpc.lstat(cp) }
                    catch (e: SFTPException) {
                        logger.debug("sftp: can't stat, skip: $cp")
                        break
                    }
                } while (rriattributes.type == FileMode.Type.SYMLINK)
            }

            if (!listOf(FileMode.Type.DIRECTORY, FileMode.Type.REGULAR).contains(rriattributes.type)) {
                logger.error("Not a regular file or directory, ignoring: $rripath : $rriattributes")
            } else {
                val vf = VirtualFile(rripath.substring(remoteBasePath.length), rriattributes.mtime * 1000, rriattributes.size).apply {
                    permissions = filePermissions2posix(rriattributes.permissions)
                    if (rriattributes.type == FileMode.Type.DIRECTORY && !path.endsWith("/")) path += "/"
                    if (path == "/") path = ""
                }
                if (vf.isNotFiltered(filterregexp)) {
                    if (!action(vf)) throw InterruptedException("recursive listing interrupted!")
                    if (vf.isDir() && (recursive || parsealways)) parseContentFun(rripath)
                }
            }
        }

        fun parseContent(folder: String) {
            val rris = sftpc.ls(folder)
            for (rri in rris.sortedBy { it.name }) {
                // if (stopRequested) return
                if (rri.name != "." && rri.name != "..") {
                    doaction(rri.path, rri.attributes) { parseContent(it) }
                }
            }
        }
        val sp = remoteBasePath + subfolder
        val sftpsp = sftpexists(sp)
        logger.debug("list sftp:$sp sftpsp:$sftpsp")
        if (sftpsp != null) { // run for base folder
            doaction(sp, sftpsp, true) { parseContent(it) }
        }
        logger.debug("parsing done")
    }

    override fun listSingleFile(remotePath: String): VirtualFile? {
        logger.debug("listsinglefile: $remotePath")
        val sftpsp = sftpexists(remoteBasePath + remotePath)
        return if (sftpsp == null) null else VirtualFile(remotePath.substring(remoteBasePath.length), sftpsp.mtime * 1000, sftpsp.size)
    }

    private val filePermissions2posix = mapOf(
        FilePermission.USR_R to PosixFilePermission.OWNER_READ,
        FilePermission.USR_W to PosixFilePermission.OWNER_WRITE,
        FilePermission.USR_X to PosixFilePermission.OWNER_EXECUTE,
        FilePermission.GRP_R to PosixFilePermission.GROUP_READ,
        FilePermission.GRP_W to PosixFilePermission.GROUP_WRITE,
        FilePermission.GRP_X to PosixFilePermission.GROUP_EXECUTE,
        FilePermission.OTH_R to PosixFilePermission.OTHERS_READ,
        FilePermission.OTH_W to PosixFilePermission.OTHERS_WRITE,
        FilePermission.OTH_X to PosixFilePermission.OTHERS_EXECUTE
    )
    private val posix2filePermissions = filePermissions2posix.entries.associateBy({ it.value }) { it.key }
    private fun filePermissions2posix(fp: Set<FilePermission>): MutableSet<PosixFilePermission> {
        return fp.mapNotNull {filePermissions2posix[it] }.toMutableSet()
    }
    private fun posix2filePermissions(fp: Set<PosixFilePermission>): MutableSet<FilePermission> {
        return fp.mapNotNull {posix2filePermissions[it] }.toMutableSet()
    }

    private val sftphysc = SftpConnectionPool.getPortForwardedSftpClient(uri.host, uri.port, protocol.tunnelHostname(), protocol.tunnelPort(), max(0, SettingsStore.tunnelModes.indexOf(protocol.tunnelMode.value)),uri.username, protocol.password.value)
    private val sftpc = sftphysc.sftpc
    private val pfsftp = sftphysc.pfsftp

    init {
        logger.debug("sftpconnection: init id=$id remoteBasePath=$remoteBasePath")
    }

    override fun isAlive() = pfsftp.sshClient.isConnected

    override fun cleanUp() {
        logger.debug("sftpconnection.cleanup id=$id remoteBasePath=$remoteBasePath")
        pfsftp.close() // TODO cleanup better
    }

    override fun mkdirrec(absolutePath: String, addRemoteBasePath: Boolean) {
        logger.debug("sftp mkdirrec $absolutePath")
        sftpc.mkdirs(if (addRemoteBasePath) "$remoteBasePath$absolutePath" else absolutePath)
    }
}

class PhysicalSftConnection(var pfsftp: PortForwardedSftp, var sftpc: SFTPClient)

object SftpConnectionPool {
    const val timeoutms = 10000
    const val ctimeoutms = 15000

    private val sftpconnections = HashMap<String, PhysicalSftConnection> ()

    // see ConsoleKnownHostsVerifier
    class MyHostKeyVerifier : OpenSSHKnownHosts(DBSettings.knownHostsFile.file) {
        override fun hostKeyUnverifiableAction(hostname: String, key: PublicKey): Boolean {
            return if (runUIwait { dialogOkCancel("SFTP server verification", "Can't verify public key of server $hostname",
                    "Fingerprint:\n${SecurityUtils.getFingerprint(key)}\nPress OK to connect and add to SFSync's known_hosts.") }) {
                entries.add(HostEntry(null, hostname, KeyType.fromKey(key), key))
                write()
                true
            } else false
        }

        override fun hostKeyChangedAction(hostname: String?, key: PublicKey?): Boolean {
            return if (runUIwait { dialogOkCancel("SFTP server verification", "Host key of server $hostname has changed!",
                    "Fingerprint:\n${SecurityUtils.getFingerprint(key)}\nPress OK if you are 100% sure if this change was intended.") }) {
                entries.add(HostEntry(null, hostname, KeyType.fromKey(key), key))
                write()
                true
            } else false
        }
    }

    // gets connection from pool or new.
    fun getPortForwardedSftpClient(hostsftp: String, portsftp: Int, hosttunnel: String, porttunnel: Int, tunnelmode: Int,
                            username: String, password: String): PhysicalSftConnection {
        val key = "$hostsftp-$portsftp-$hosttunnel-$porttunnel-$tunnelmode-$username-$password"
        if (!sftpconnections.contains(key)) {
            logger.debug("getPortForwardedSftpClient: key not found, open new connection...")
            val pfsftp = PortForwardedSftp(hostsftp, portsftp, hosttunnel, porttunnel, tunnelmode, username, password)
            val sftpc = pfsftp.sshClient.newSFTPClient()
            sftpc.sftpEngine.timeoutMs = timeoutms
            sftpconnections[key] = PhysicalSftConnection(pfsftp, sftpc)
        } else { logger.debug("getPortForwardedSftpClient: key found!") }
        logger.debug("getPortForwardedSftpClient!")
        return sftpconnections[key]!!
    }
}

// https://stackoverflow.com/a/16023513
class PortForwardedSftp(private val hostsftp: String, private val portsftp: Int, private val hosttunnel: String, private val porttunnel: Int, private val tunnelmode: Int,
                        private val username: String, private val password: String) {

    private val startPort = 2222

    inner class PortForwarder(private val sshClient: SSHClient, private val remoteAddress: InetSocketAddress, private val localSocket: ServerSocket) : Thread(), Closeable {
        val latch = CountDownLatch(1)

        private var forwarder: LocalPortForwarder? = null
        override fun run() {
            val params = Parameters("127.0.0.1", localSocket.localPort,
                remoteAddress.hostName, remoteAddress.port)
            forwarder = sshClient.newLocalPortForwarder(params, localSocket)
            try {
                latch.countDown()
                forwarder!!.listen()
            } catch (ignore: IOException) {} /* OK. */
        }

        override fun close() {
            localSocket.close()
            forwarder!!.close()
        }
    }

    inner class TunnelPortManager {
        private val maxPort = 65536
        private val portsHandedOut = HashSet<Int>()

        fun leaseNewPort(startFrom: Int): ServerSocket {
            for (port in startFrom..maxPort) {
                if (isLeased(port)) continue

                val socket = tryBind (port)
                if (socket != null) {
                    portsHandedOut.add(port)
                    logger.info("handing out port $port for local binding")
                    return socket
                }
            }
            throw IllegalStateException("Could not find a single free port in the range [$startFrom-$maxPort]...")
        }

        private fun isLeased(port: Int) = portsHandedOut.contains(port)

        private fun tryBind(localPort: Int): ServerSocket? {
            return try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("localhost", localPort))
                ss
            } catch (e: IOException) {
                null
            }
        }
    }

    private fun startForwarder(forwarderThread: PortForwarder): PortForwarder {
        forwarderThread.start()
        try {
            forwarderThread.latch.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return forwarderThread
    }


    private fun getSSHClient(username: String, pw: String, hostname: String, port: Int): SSHClient {
        val defaultConfig = DefaultConfig()
        defaultConfig.keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
        val ssh = SSHClient(defaultConfig)
        ssh.addHostKeyVerifier(SftpConnectionPool.MyHostKeyVerifier())
        ssh.timeout = SftpConnectionPool.timeoutms
        ssh.connectTimeout = SftpConnectionPool.ctimeoutms
        ssh.connect(hostname, port)
        ssh.connection.keepAlive.keepAliveInterval = 2 // KeepaliveRunner makes 5 retries...
        try {
            ssh.authPublickey(username)
        } catch (e: UserAuthException) {
            logger.info("Public key auth failed: $e")
            logger.info("auth methods: " + ssh.userAuth.allowedMethods.joinToString(","))
            // under win7 this doesn't work, try password in any case
            //      if (ssh.getUserAuth.getAllowedMethods.exists(s => s == "keyboard-interactive" || s == "password" )) {
            if (pw != "") {
                ssh.authPassword(username, pw)
            } else {
                runUIwait { dialogMessage(Alert.AlertType.ERROR, "SSH", "Public key auth failed, require password.", "") }
                throw UserAuthException("No password")
            }
        }
        if (!ssh.isAuthenticated) {
            throw UserAuthException("Not authenticated!")
        } else logger.info("Authenticated!")
        return ssh
    }

    private fun connect(): SSHClient {
        var usetunnel = tunnelmode == 1
        if (tunnelmode == 2) {
            try {
                val addr = InetAddress.getByName(hostsftp)
                usetunnel = !addr.isReachable(500)
            } catch (e: UnknownHostException) { logger.info("unknown host, assume not reachable!")}
        }

        return if (usetunnel) {
            if (hosttunnel.isEmpty()) throw Exception("tunnel host must not be empty!")
            logger.info("making initial connection to $hosttunnel")
            val sshClient = getSSHClient(username, password, hosttunnel, porttunnel)
            logger.info("creating connection to $hostsftp")
            val ss = portManager.leaseNewPort(startPort)

            val sftpAddress = InetSocketAddress(hostsftp, portsftp)

            forwarderThread = PortForwarder(sshClient, sftpAddress, ss)
            forwarderThread = startForwarder(forwarderThread!!)
            getSSHClient(username, password, "127.0.0.1", ss.localPort)
        } else {
            logger.info("creating direct connection to $hostsftp")
            getSSHClient(username, password, hostsftp, portsftp)
        }

    }

    fun close() {
        try {
            logger.debug("sshc.isal=${sshClient.connection.keepAlive.isAlive}")
            if (forwarderThread != null) forwarderThread!!.close()
            if (sshClient.isConnected) sshClient.disconnect()
        } catch (e: Exception) {
            logger.info("Exception during PFSftp close, ignored! " + e.message)
        }
        logger.info("Sftp closed!")
    }

    private var forwarderThread: PortForwarder? = null
    private val portManager = TunnelPortManager()

    val sshClient = connect()
}


