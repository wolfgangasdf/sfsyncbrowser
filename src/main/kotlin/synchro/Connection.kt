@file:Suppress("ConstantConditionIf")

package synchro

import javafx.application.Platform
import javafx.scene.control.Alert
import mu.KotlinLogging
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.common.StreamCopier.Listener
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPException
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
import util.Helpers.toJavaPathSeparator
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.nio.file.*
import java.nio.file.attribute.FileTime
import java.security.PublicKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

// DON'T call stuff here from UI thread, can lock!

private val logger = KotlinLogging.logger {}

class MyURI(var protocol: String, var username: String, var host: String, var port: Int) {
    constructor() : this("", "", "", -1)

    private fun parseString(s: String): Boolean {
        val regexinetres = """(\S+)://(\S+)@(\S+):(\S+)""".toRegex().find(s)
        return when {
            s == "file:///" -> { protocol = "file"; true }
            regexinetres != null -> {
                val (prot1, userinfo, host1, port1) = regexinetres.destructured
                protocol = prot1
                host = host1
                port = port1.toInt()
                username = userinfo
                true
            }
            else -> false
        }
    }

    override fun toString(): String = "$protocol,$username,$host,$port"

    constructor(s: String) : this() {
        if (!this.parseString(s))
            throw RuntimeException("URI in wrong format: $")
    }
}

// if ends on "/", is dir except for "" which is also dir (basepath)
class VirtualFile(var path: String, var modTime: Long, var size: Long, var permissions: String = "") : Comparable<VirtualFile> {
    // modtime in milliseconds since xxx
    constructor() : this("", 0, 0)

    // gets file/folder name, "" if "/" or "" without trailing "/" for dirs!
    fun getFileName(): String = File(path).name
    fun getParent(): String = File(path).parent.let { if (it.endsWith("/")) it else "$it/" }
    fun getFileNameBrowser(): String = File(path).name + if (isDir()) "/" else ""
    fun getFileExtension(): String = File(getFileName()).extension
    fun isNotFiltered(filterregexp: String) = !(filterregexp.isNotEmpty() && getFileName().matches(filterregexp.toRegex()))

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


// subfolder should NOT start or end with /
abstract class GeneralConnection(val protocol: Protocol) {
    var remoteBasePath: String = protocol.baseFolder.value
    protected val debugslow = false
    val interrupted = AtomicBoolean(false)
    abstract fun getfile(localBasePath: String, from: String, mtime: Long, to: String)
    abstract fun getfile(localBasePath: String, from: String, mtime: Long)
    abstract fun putfile(localBasePath: String, from: String, mtime: Long, remotePath: String = ""): Long // returns mtime if cantSetDate
    abstract fun mkdirrec(absolutePath: String)
    abstract fun deletefile(what: String, mtime: Long)
    abstract fun list(subfolder: String, filterregexp: String, recursive: Boolean, action: (VirtualFile) -> Unit)
    abstract fun isAlive(): Boolean

    // extended functions only for some connections
    abstract fun canRename(): Boolean
    abstract fun canChmod(): Boolean
    abstract fun canDuplicate(): Boolean
    open fun extRename(oldPath: String, newPath: String) { throw Exception("Can't rename") }
    open fun extChmod(path: String, newPerms: String) { throw Exception("Can't chmod") }
    open fun extDuplicate(oldPath: String, newPath: String) { throw Exception("Can't duplicate") }

    fun assignRemoteBasePath(remoteFolder: String) {
        remoteBasePath = protocol.baseFolder.value + remoteFolder
    }
    //noinspection ScalaUnusedSymbol
    var onProgress: (progressVal: Double, bytePerSecond: Double) -> Unit = { _, _ -> }

    // return dir (most likely NOT absolute path but subfolder!) without trailing /
    fun checkIsDir(path: String): Pair<String, Boolean> {
        val isdir = path.endsWith("/") || path == ""
        val resp = if (isdir && path != "") path.substring(0, path.length - 1) else path
        return Pair(resp, isdir)
    }

    abstract fun cleanUp()

    fun permToString(permissions: Set<FilePermission>): String {
        val mask = FilePermission.toMask(permissions)
        return  (if (FilePermission.USR_R.isIn(mask)) "r" else "-") +
                (if (FilePermission.USR_W.isIn(mask)) "w" else "-") +
                (if (FilePermission.USR_X.isIn(mask)) "x" else "-") +
                (if (FilePermission.GRP_R.isIn(mask)) "r" else "-") +
                (if (FilePermission.GRP_W.isIn(mask)) "w" else "-") +
                (if (FilePermission.GRP_X.isIn(mask)) "x" else "-") +
                (if (FilePermission.OTH_R.isIn(mask)) "r" else "-") +
                (if (FilePermission.OTH_W.isIn(mask)) "w" else "-") +
                (if (FilePermission.OTH_X.isIn(mask)) "x" else "-")
    }
}

class LocalConnection(protocol: Protocol) : GeneralConnection(protocol) {
    override fun canRename(): Boolean = true
    override fun canChmod(): Boolean = true
    override fun canDuplicate(): Boolean = true
    override fun extRename(oldPath: String, newPath: String) {
        // TODO test
        val (cp, _) = checkIsDir(oldPath)
        Files.move(Paths.get("$remoteBasePath$cp"), Paths.get("$remoteBasePath$newPath"), StandardCopyOption.COPY_ATTRIBUTES)
    }
    override fun extChmod(path: String, newPerms: String) {
        // TODO
    }
    override fun extDuplicate(oldPath: String, newPath: String) {
        // TODO test
        val (cp, _) = checkIsDir(oldPath)
        Files.copy(Paths.get("$remoteBasePath$cp"), Paths.get("$remoteBasePath$newPath"), StandardCopyOption.COPY_ATTRIBUTES)
    }

    override fun cleanUp() {}

    override fun deletefile(what: String, mtime: Long) {
        val (cp, _) = checkIsDir(what)
        val fp = Paths.get("$remoteBasePath$cp")
        try {
            Files.delete(fp)
        } catch (e: DirectoryNotEmptyException) {
            val dir = Files.newDirectoryStream(fp).toList()
            if (runUIwait {
                        dialogOkCancel("Warning", "Directory \n $cp \n not empty, DELETE ALL?", "Content:\n" +
                                dir.asSequence().map { it.toFile().name }.joinToString("\n"))
                    }) {
                dir.forEach { Files.delete(it) }
                Files.delete(fp)
                return
            }
        }
    }

    override fun putfile(localBasePath: String, from: String, mtime: Long, remotePath: String): Long {
        val (cp, isdir) = checkIsDir(from)
        val remoteabspath = if (remotePath == "") "$remoteBasePath$cp" else "$remoteBasePath$remotePath"
        logger.debug("putfile: from=$from isdir=$isdir remabspath=$remoteabspath")
        if (isdir) { // ensure that target path exists
            if (!Files.exists(Paths.get(remoteabspath).parent)) {
                logger.debug("creating folder $cp")
                mkdirrec(Paths.get(remoteabspath).parent.toString())
            }
        }
        Files.copy(Paths.get("$localBasePath$cp"), Paths.get(remoteabspath), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        return mtime
    }

    // here, "to" is a full path!
    override fun getfile(localBasePath: String, from: String, mtime: Long, to: String) {
        val (cp, isdir) = checkIsDir(from)
        if (isdir) { // ensure that target path exists
            Files.createDirectories(Paths.get(to)) // simply create parents if necessary, avoids separate check
        } else {
            Files.copy(Paths.get("$remoteBasePath$cp"), Paths.get(to), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        }
        Files.setLastModifiedTime(Paths.get(to), FileTime.fromMillis(mtime))
    }

    override fun getfile(localBasePath: String, from: String, mtime: Long) {
        val (cp, _) = checkIsDir(from)
        val lp = "$localBasePath$cp"
        getfile(localBasePath, from, mtime, lp)
    }

    // include the subfolder but root "/" is not allowed!
    override fun list(subfolder: String, filterregexp: String, recursive: Boolean, action: (VirtualFile) -> Unit) {
        logger.debug("listrec(rbp=$remoteBasePath sf=$subfolder rec=$recursive) in thread ${Thread.currentThread().id}")
        fun parseContent(cc: Path, goDeeper: Boolean) {
            // on mac 10.8 with oracle java 7, filenames are encoded with strange 'decomposed unicode'. grr
            // this is in addition to the bug that LC_CTYPE is not set. grrr
            // don't use cc.getPath directly!!
            if (Helpers.failat == 4) throw UnsupportedOperationException("fail 4")
            val javaPath = toJavaPathSeparator(cc.toString())
            val fixedPath = java.text.Normalizer.normalize(javaPath, java.text.Normalizer.Form.NFC)
            // fixedPath is without trailing "/" for dirs!
            logger.debug("javap=$javaPath fp=$fixedPath rbp=$remoteBasePath")
            var strippedPath: String = if (fixedPath == remoteBasePath.dropLast(1)) "" else fixedPath.substring(remoteBasePath.length)
            if (Files.isDirectory(cc) && strippedPath != "") strippedPath += "/"
            val vf = VirtualFile(strippedPath, Files.getLastModifiedTime(cc).toMillis(), Files.size(cc))
            if (vf.isNotFiltered(filterregexp)) {
                if (debugslow) Thread.sleep(500)
                action(vf)
                if (Files.isDirectory(cc) && goDeeper) {
                    val dir = Files.newDirectoryStream(cc)
                    for (cc1 in dir) parseContent(cc1, goDeeper = recursive)
                    dir.close()
                }
            }
        }

        val sp = Paths.get(remoteBasePath + subfolder)
        if (Files.exists(sp)) {
            parseContent(sp, goDeeper = true)
        }
        logger.debug("listrec DONE (rbp=$remoteBasePath sf=$subfolder rec=$recursive) in thread ${Thread.currentThread().id}")
    }

    override fun mkdirrec(absolutePath: String) {
        Files.createDirectories(Paths.get(absolutePath))
    }

    override fun isAlive() = true
}


class SftpConnection(protocol: Protocol) : GeneralConnection(protocol) {

    private val uri = protocol.getmyuri()

    init {
        if (Platform.isFxApplicationThread()) throw Exception("must not be called from JFX thread (blocks, opens dialogs)")
    }

    override fun canRename(): Boolean = true
    override fun canChmod(): Boolean = true
    override fun canDuplicate(): Boolean = false //
    override fun extRename(oldPath: String, newPath: String) {
        // TODO test
        val (cp, _) = checkIsDir(oldPath)
        sftpc.rename("$remoteBasePath$cp", "$remoteBasePath$newPath")
    }
    override fun extChmod(path: String, newPerms: String) {
        // TODO
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

    private fun isDirectoryx(fa: FileAttributes): Boolean =
            ((fa.type.toMask() and FileMode.Type.DIRECTORY.toMask()) > 0)

    private var transferListener: MyTransferListener? = null

    override fun deletefile(what: String, mtime: Long) {
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

    override fun putfile(localBasePath: String, from: String, mtime: Long, remotePath: String): Long {
        val (cp, isdir) = checkIsDir(from)
        val rp = if (remotePath == "") "$remoteBasePath$cp" else "$remoteBasePath$remotePath"
        logger.debug("putfile: from=$from isdir=$isdir rp=$rp")

        fun setAttr(changeperms: Boolean, rp: String) {
            val lf = FileSystemFile("$localBasePath$cp")
            val fab = FileAttributes.Builder()
            if (changeperms) {
                val perms = sftpc.perms(rp).toMutableList()
                Helpers.permissionsParseRegex.findAll(protocol.perms.value).forEach { r ->
                    val whowhat = r.groupValues[1] + r.groupValues[3]
                    val fp = when(whowhat) {
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
            fun checkit(p: String) { // recursively create parents
                val parent = Paths.get(p).parent.toString()
                if (sftpexists(parent) == null) {
                    checkit(parent)
                    sftpc.mkdir(parent)
                }
            }
            checkit(rp)
            sftpc.mkdir(rp)
            if (protocol.doSetPermissions.value) setAttr(true, rp)
            mtime // dirs don't need mtime
        } else {
            try {
                if (!Files.isReadable(Paths.get("$localBasePath$cp"))) throw IllegalStateException("can't read file $cp")
                sftpt.upload("$localBasePath$cp", rp) // use this in place of sftpc.put to not always set file attrs
                if (protocol.doSetPermissions.value) setAttr(true, rp)
                else if (!protocol.cantSetDate.value) setAttr(false, rp)
            } catch (e: Exception) {
                logger.debug("putfile: exception: $e")
                if (transferListener!!.bytesTransferred > 0) { // file may be corrupted, but don't delete if nothing transferred
                    // prevent delete of root-owned files if user in group admin, sftp rm seems to "override permission"
                    sftpc.rm(rp)
                }
                throw e
            }
            if (transferListener!!.bytesTotal != transferListener!!.bytesTransferred)
                throw IllegalStateException("filesize mismatch: ${transferListener!!.bytesTotal} <> ${transferListener!!.bytesTransferred}")
            if (protocol.cantSetDate.value) {
                sftpc.mtime(rp) * 1000
            } else {
                mtime
            }
        }
    }

    override fun getfile(localBasePath: String, from: String, mtime: Long, to: String) {
        val (cp, isdir) = checkIsDir(from)
        if (isdir) {
            Files.createDirectories(Paths.get(to)) // simply create parents if necessary, avoids separate check
            Files.setLastModifiedTime(Paths.get(to), FileTime.fromMillis(mtime))
        } else {
            Files.createDirectories(Paths.get(to).parent)
            // copy-then-move: sftpt.download erases local file if it exists also if remote file can't be read
            val tmpf = createTempFile("sfsync-tempfile", ".dat")
            sftpt.download("$remoteBasePath$cp", tmpf.absolutePath)
            Files.move(tmpf.toPath(), Paths.get(to), StandardCopyOption.REPLACE_EXISTING)
            if (transferListener!!.bytesTotal != transferListener!!.bytesTransferred)
                throw IllegalStateException("filesize mismatch: ${transferListener!!.bytesTotal} <> ${transferListener!!.bytesTransferred}")
            Files.setLastModifiedTime(Paths.get(to), FileTime.fromMillis(mtime))
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

    override fun list(subfolder: String, filterregexp: String, recursive: Boolean, action: (VirtualFile) -> Unit) {
        logger.debug("listrecsftp(rbp=$remoteBasePath sf=$subfolder rec=$recursive fil=$filterregexp) in thread ${Thread.currentThread().id}")

        fun vfFromSftp(fullFilePath: String, attrs: FileAttributes): VirtualFile {
            return VirtualFile().apply {
                path = fullFilePath.substring(remoteBasePath.length)
                modTime = attrs.mtime * 1000
                size = attrs.size
                permissions = permToString(attrs.permissions)
                if (isDirectoryx(attrs) && !path.endsWith("/")) path += "/"
                if (path == "/") path = ""
            }
        }

        fun doaction(rripath: String, rriattributes: FileAttributes, parsealways: Boolean = false, parseContentFun: (String) -> Unit) {
            val vf = vfFromSftp(rripath, rriattributes)
            if (vf.isNotFiltered(filterregexp)) {
                action(vf)
                if (isDirectoryx(rriattributes) && (recursive || parsealways))  parseContentFun(rripath)
            }
        }

        fun parseContent(folder: String) {
            if (Helpers.failat == 3) throw UnsupportedOperationException("fail 3")
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

    // see ConsoleKnownHostsVerifier
    class MyHostKeyVerifier : OpenSSHKnownHosts(DBSettings.knownHostsFile) {
        override fun hostKeyUnverifiableAction(hostname: String, key: PublicKey): Boolean {
            return if (runUIwait { Helpers.dialogOkCancel("SFTP server verification", "Can't verify public key of server $hostname",
                                "Fingerprint:\n${SecurityUtils.getFingerprint(key)}\nPress OK to connect and add to SFSync's known_hosts.") }) {
                entries.add(OpenSSHKnownHosts.HostEntry(null, hostname, KeyType.fromKey(key), key))
                write()
                true
            } else false
        }

        override fun hostKeyChangedAction(hostname: String?, key: PublicKey?): Boolean {
            return if (runUIwait { dialogOkCancel("SFTP server verification", "Host key of server $hostname has changed!",
                                "Fingerprint:\n${SecurityUtils.getFingerprint(key)}\nPress OK if you are 100% sure if this change was intended.") }) {
                entries.add(OpenSSHKnownHosts.HostEntry(null, hostname, KeyType.fromKey(key), key))
                write()
                true
            } else false
        }
    }

    // https://stackoverflow.com/a/16023513
    class PortForwardedSftp(private val hostsftp: String, private val portsftp: Int, private val hosttunnel: String, private val porttunnel: Int, private val tunnelmode: Int,
                            private val username: String, private val password: String) {

        private val startPort = 2222

        class PortForwarder(private val sshClient: SSHClient, private val remoteAddress: InetSocketAddress, private val localSocket: ServerSocket) : Thread(), Closeable {
            val latch = CountDownLatch(1)

            private var forwarder: LocalPortForwarder? = null
            override fun run() {
                val params = LocalPortForwarder.Parameters("127.0.0.1", localSocket.localPort,
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

        class TunnelPortManager {
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

//            @Synchronized
//            fun returnPort(socket: ServerSocket) {
//                portsHandedOut.remove(socket.localPort)
//            }

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
            val ssh = SSHClient()
            ssh.addHostKeyVerifier(MyHostKeyVerifier())
            ssh.connect(hostname, port)
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
            sftpClient.close()
            if (forwarderThread != null) forwarderThread!!.close()
            if (sshClient.isConnected) sshClient.disconnect()
            logger.info("Sftp closed!")
        }

        private var forwarderThread: PortForwarder? = null
        private val portManager = TunnelPortManager()
        // Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())

        val sshClient = connect()
        init {
            sshClient.startSession()
        }
        private val sftpClient = sshClient.newSFTPClient()!!
    }

    private val pfsftp = PortForwardedSftp(uri.host, uri.port, protocol.tunnelHostname(), protocol.tunnelPort(), max(0, SettingsStore.tunnelModes.indexOf(protocol.tunnelMode.value)),uri.username, protocol.password.value)
    private val sftpc = pfsftp.sshClient.newSFTPClient()
    private val sftpt = sftpc.fileTransfer


    init {
        logger.debug("ini sftp connection remoteBasePath=$remoteBasePath")
        transferListener = MyTransferListener()
        sftpt.transferListener = transferListener

        sftpt.preserveAttributes = false // don't set permissions from local, mostly doesn't make sense! Either by user or not at all.

        if (Helpers.failat == 2) throw UnsupportedOperationException("fail 2")
    }

    override fun isAlive() = pfsftp.sshClient.isConnected

    override fun cleanUp() {
        pfsftp.close()
    }

    override fun mkdirrec(absolutePath: String) {
        throw NotImplementedError("mkdirrec for sftp") // TODO implement from checkit() above?
    }
}


