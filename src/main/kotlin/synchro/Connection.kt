@file:Suppress("unused") // TODO

package synchro

import javafx.application.Platform
import mu.KotlinLogging
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.common.StreamCopier.Listener
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
import util.Helpers
import util.Helpers.dialogMessage
import util.Helpers.dialogOkCancel
import util.Helpers.runUIwait
import util.Helpers.toJavaPathSeparator
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.FileTime
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicBoolean

// DON'T call stuff here from UI thread, can lock!

private val logger = KotlinLogging.logger {}

class MyURI(var protocol: String, var username: String, var host: String, var port: String) {
    constructor() : this("", "", "", "")

    fun parseString(s: String): Boolean {
        val regexinetres = """(\S+)://(\S+)@(\S+):(\S+)""".toRegex().find(s)
        return when {
            s == "file:///" -> { protocol = "file"; true }
            regexinetres != null -> {
                val (prot1, userinfo, host1, port1) = regexinetres.destructured
                protocol = prot1
                host = host1
                port = port1
                username = userinfo
                true
            }
            else -> false
        }
    }

    fun toURIString(): String = "$protocol://$username@$host:$port"

    override fun toString(): String = "$protocol,$username,$host,$port"

    constructor(s: String) : this() {
        if (!this.parseString(s))
            throw RuntimeException("URI in wrong format: $")
    }
}

// path below basepath with a leading "/"
// if ends on "/", is dir!
class VirtualFile(var path: String, var modTime: Long, var size: Long) : Comparable<VirtualFile> { // TODO was Ordered in scala
    // modtime in milliseconds since xxx
    constructor() : this("", 0, 0)

    fun fileName(): String = if (path == "/") "/" else path.split("/").dropLastWhile { it.isEmpty() }.last()
    override fun toString(): String = "[$path]:$modTime,$size"

    fun isDir(): Boolean = path.endsWith("/")

    override fun equals(other: Any?): Boolean {
        if (other is VirtualFile) if (this.hashCode() == other.hashCode()) return true
        return false
    }

    override fun hashCode(): Int = path.hashCode() + modTime.hashCode() + size.hashCode() // TODO this is crap

    override fun compareTo(other: VirtualFile): Int = path.compareTo(other.path)

}


// subfolder should NOT start or end with /
abstract class GeneralConnection(val protocol: Protocol) {
    var remoteBasePath: String = protocol.baseFolder.value
    var filterregex: Regex = Regex(""".*""")
    val debugslow = false
    val interrupted = AtomicBoolean(false)
    abstract fun getfile(localBasePath: String, from: String, mtime: Long, to: String)
    abstract fun getfile(localBasePath: String, from: String, mtime: Long)
    abstract fun putfile(localBasePath: String, from: String, mtime: Long): Long // returns mtime if cantSetDate
    abstract fun mkdirrec(absolutePath: String)
    abstract fun deletefile(what: String, mtime: Long)
    abstract fun list(subfolder: String, filterregexp: String, recursive: Boolean, action: (VirtualFile) -> Unit)
    abstract fun isAlive(): Boolean

    //noinspection ScalaUnusedSymbol
    var onProgress: (progressVal: Double, bytePerSecond: Double) -> Unit = { _, _ -> }

    // return dir (most likely NOT absolute path but subfolder!) without trailing /
    fun checkIsDir(path: String): Pair<String, Boolean> {
        val isdir = path.endsWith("/")
        val resp = if (isdir) path.substring(0, path.length - 1) else path
        return Pair(resp, isdir)
    }

    abstract fun cleanUp()
}

class LocalConnection(protocol: Protocol) : GeneralConnection(protocol) {

    override fun cleanUp() {}

    override fun deletefile(what: String, mtime: Long) {
        val (cp, _) = checkIsDir(what)
        val fp = Paths.get("$remoteBasePath/$cp")
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

    override fun putfile(localBasePath: String, from: String, mtime: Long): Long {
        val (cp, isdir) = checkIsDir(from)
        logger.debug("from=$from isdir=$isdir")
        if (isdir) { // ensure that target path exists
            val abspath = "$remoteBasePath/$cp"
            if (!Files.exists(Paths.get(abspath).parent)) {
                logger.debug("creating folder $cp")
                mkdirrec(Paths.get(abspath).parent.toString())
            }
        }
        Files.copy(Paths.get("$localBasePath/$cp"), Paths.get("$remoteBasePath/$cp"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        return mtime
    }

    // here, "to" is a full path!
    override fun getfile(localBasePath: String, from: String, mtime: Long, to: String) {
        val (cp, isdir) = checkIsDir(from)
        if (isdir) { // ensure that target path exists
            Files.createDirectories(Paths.get(to)) // simply create parents if necessary, avoids separate check
        } else {
            Files.copy(Paths.get("$remoteBasePath/$cp"), Paths.get(to), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        }
        Files.setLastModifiedTime(Paths.get(to), FileTime.fromMillis(mtime))
    }

    override fun getfile(localBasePath: String, from: String, mtime: Long) {
        val (cp, _) = checkIsDir(from)
        val lp = "$localBasePath/$cp"
        getfile(localBasePath, from, mtime, lp)
    }

    // include the subfolder but root "/" is not allowed!
    override fun list(subfolder: String, filterregexp: String, recursive: Boolean, action: (VirtualFile) -> Unit) {
        logger.debug("listrec(rbp=$remoteBasePath sf=$subfolder rec=$recursive) in thread ${Thread.currentThread().id}")
        // scalax.io is horribly slow, there is an issue filed
        fun parseContent(cc: Path, goDeeper: Boolean) {
            // on mac 10.8 with oracle java 7, filenames are encoded with strange 'decomposed unicode'. grr
            // this is in addition to the bug that LC_CTYPE is not set. grrr
            // don't use cc.getPath directly!!
            if (Helpers.failat == 4) throw UnsupportedOperationException("fail 4")
            val javaPath = toJavaPathSeparator(cc.toString())
            val fixedPath = java.text.Normalizer.normalize(javaPath, java.text.Normalizer.Form.NFC)
            var strippedPath: String = if (fixedPath == remoteBasePath) "/" else fixedPath.substring(remoteBasePath.length)
            if (Files.isDirectory(cc) && strippedPath != "/") strippedPath += "/"
            val vf = VirtualFile(strippedPath, Files.getLastModifiedTime(cc).toMillis(), Files.size(cc))
            if (!vf.fileName().matches(filterregexp.toRegex())) {
                if (debugslow) Thread.sleep(500)
                action(vf)
                if (Files.isDirectory(cc) && goDeeper) {
                    val dir = Files.newDirectoryStream(cc)
                    for (cc1 in dir) parseContent(cc1, goDeeper = recursive)
                    dir.close()
                }
            }
        }

        val sp = Paths.get(remoteBasePath + (if (subfolder.length > 0) "/" else "") + subfolder)
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

    private val ssh = SSHClient()

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
                sftpc.rmdir("$remoteBasePath/$cp")
            } catch (e: IOException) { // unfortunately only "Failure" ; checking for content would be slow
                val xx = sftpc.ls("$remoteBasePath/$cp")
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
                        tmp.forEach { sftpc.rm(remoteBasePath + "/" + cp + "/" + it.name) }
                        sftpc.rmdir("$remoteBasePath/$cp")
                        return
                    }
                }
            }
        } else {
            sftpc.rm("$remoteBasePath/$cp")
        }
    }

    override fun putfile(localBasePath: String, from: String, mtime: Long): Long {
        val (cp, isdir) = checkIsDir(from)
        val rp = "$remoteBasePath/$cp"

        fun setAttr(changeperms: Boolean) {
            val lf = FileSystemFile("$localBasePath/$cp")
            val fab = FileAttributes.Builder()
            if (changeperms) {
                val perms = FilePermission.fromMask(lf.permissions)
//        if (protocol.remGroupWrite.value) perms.add(FilePermission.GRP_W) else perms.remove(FilePermission.GRP_W)
//        if (protocol.remOthersWrite.value) perms.add(FilePermission.OTH_W) else perms.remove(FilePermission.OTH_W)
                fab.withPermissions(perms)
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
            if (protocol.doSetPermissions.value) setAttr(true)
            mtime // dirs don't need mtime
        } else {
            try {
                if (!Files.isReadable(Paths.get("$localBasePath/$cp"))) throw IllegalStateException("can't read file $cp")
                sftpt.upload("$localBasePath/$cp", rp) // use this in place of sftpc.put to not always set file attrs
                if (protocol.doSetPermissions.value) setAttr(true)
                else if (!protocol.cantSetDate.value) setAttr(false)
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
            try {
                // sftpt.download erases local file if it exists also if remote file can't be read
                val tmpf = createTempFile("sfsync-tempfile", ".dat")
                sftpt.download("$remoteBasePath/$cp", tmpf.absolutePath)
                Files.move(tmpf.toPath(), Paths.get(to), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                logger.debug("getfile: exception $e")
                throw e
            }
            if (transferListener!!.bytesTotal != transferListener!!.bytesTransferred)
                throw IllegalStateException("filesize mismatch: ${transferListener!!.bytesTotal} <> ${transferListener!!.bytesTransferred}")

            Files.setLastModifiedTime(Paths.get(to), FileTime.fromMillis(mtime))
        }
    }

    override fun getfile(localBasePath: String, from: String, mtime: Long) {
        val (cp, _) = checkIsDir(from)
        val lp = "$localBasePath/$cp"
        getfile(localBasePath, from, mtime, lp)
    }

    private fun sftpexists(sp: String): FileAttributes? {
        var resls: FileAttributes? = null
        try {
            resls = sftpc.stat(sp) // throws exception if not
        } catch (e: SFTPException) {
            if (e.statusCode == StatusCode.NO_SUCH_FILE) logger.debug(e.message) else throw(e)
        }
        return resls
    }

    override fun list(subfolder: String, filterregexp: String, recursive: Boolean, action: (VirtualFile) -> Unit) {
        logger.debug("listrecsftp(rbp=$remoteBasePath sf=$subfolder rec=$recursive) in thread ${Thread.currentThread().id}")

        fun VFfromSftp(fullFilePath: String, attrs: FileAttributes): VirtualFile {
            return VirtualFile().apply {
                path = fullFilePath.substring(remoteBasePath.length)
                modTime = attrs.mtime * 1000
                size = attrs.size
                if (isDirectoryx(attrs) && !path.endsWith("/")) path += "/"
            }
        }

        fun doaction(rripath: String, rriattributes: FileAttributes, parsealways: Boolean = false, parseContentFun: (String) -> Unit) {
            val vf = VFfromSftp(rripath, rriattributes)
            if (!vf.fileName().matches(filterregexp.toRegex())) {
                action(vf)
                if (isDirectoryx(rriattributes) && (recursive || parsealways)) {
                    parseContentFun(rripath)
                }
            }
        }

        fun parseContent(folder: String) {
            if (Helpers.failat == 3) throw UnsupportedOperationException("fail 3")
            val rris = sftpc.ls(folder)
            for (rri in rris.sortedBy { it.name }) {
                // if (stopRequested) return
                if (!rri.name.equals(".") && !rri.name.equals("..")) {
                    doaction(rri.path, rri.attributes) { parseContent(it) }
                }
            }
        }
        val sp = remoteBasePath + (if (subfolder.isNotEmpty()) "/" else "") + subfolder
        logger.debug("searching $sp")
        val sftpsp = sftpexists(sp)
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

    override fun isAlive() = ssh.isConnected

    init {
        if (Platform.isFxApplicationThread()) throw Exception("must not be called from JFX thread (blocks, opens dialogs)")
        ssh.addHostKeyVerifier(MyHostKeyVerifier())
        ssh.connect(uri.host, uri.port.toInt())
        try {
            ssh.authPublickey(uri.username)
        } catch (e: UserAuthException) {
            logger.info("Public key auth failed: $e")
            logger.info("auth methods: " + ssh.userAuth.allowedMethods.joinToString(","))
            // under win7 this doesn't work, try password in any case
            //      if (ssh.getUserAuth.getAllowedMethods.exists(s => s == "keyboard-interactive" || s == "password" )) {
            if (protocol.password.value != "") {
                ssh.authPassword(uri.username, protocol.password.value)
            } else {
                runUIwait { dialogMessage("SSH", "Public key auth failed, require password.", "") }
                throw UserAuthException("No password")
            }
        }
        if (!ssh.isAuthenticated) {
            throw UserAuthException("Not authenticated!")
        } else logger.info("Authenticated!")
    }

    private val sftpc = ssh.newSFTPClient()
    private val sftpt = sftpc.fileTransfer

    init {
        transferListener = MyTransferListener()
        sftpt.transferListener = transferListener

        sftpt.preserveAttributes = false // don't set permissions remote! Either by user or not at all.

        if (Helpers.failat == 2) throw UnsupportedOperationException("fail 2")
    }

    override fun cleanUp() {
        sftpc.close()
        if (ssh.isConnected) ssh.disconnect()
    }

    override fun mkdirrec(absolutePath: String) {
        throw NotImplementedError("mkdirrec for sftp")
    }
}


