package synchro

import mu.KotlinLogging
import store.Protocol
import java.io.IOException
import java.nio.file.attribute.FileTime
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.PublicKey
import java.util.concurrent.atomic.AtomicBoolean


private val logger = KotlinLogging.logger {}

class cachedFile(path: String, modTime: Long, size: Long) {
}

class MyURI(var protocol: String, var username: String, var password: String, var host: String, var port: String) {
  constructor(): this("","","","","")
  fun parseString(s: String): Boolean {
    val regexinetres = """(\S+)://(\S+)@(\S+):(\S+)""".toRegex().find(s)
    if (s == "file:///") { protocol = "file"; return true }
    else if (regexinetres != null) {
      val (prot1, userinfo, host1, port1) = regexinetres.destructured
      protocol = prot1
      host = host1
      port = port1
      val uis = userinfo.split(":")
      when (uis.size) {
        1 -> { username = uis[0]; password = "" }
        2 -> { username = uis[0]; password = uis[1] }
      }
      return true
    } else return false
  }
  fun toURIString(): String = protocol + "://" + username + ":" + password + "@" + host + ":" + port

  override fun toString(): String = "$protocol,$username,$host,$port"

  constructor(s: String): this() {
    if (!this.parseString(s))
      throw RuntimeException("URI in wrong format: $s")
  }
}

// path below baspath with a leading "/"
// if ends on "/", is dir!
class VirtualFile(var path: String, var modTime: Long, var size: Long): Comparable<VirtualFile> { // TODO was Ordered in scala
  // modtime in milliseconds since xxx
  var tagged = false // for cachelist: tagged if local/remote existing, does not need to be added "cacheonly"
  constructor(): this("",0,0)
  fun fileName() : String = if (path == "/") "/" else path.split("/").last
  override fun toString(): String = "["+path+"]:"+modTime+","+size

  fun isDir(): Boolean = path.endsWith("/")

  override fun equals(other: Any?): Boolean {
    if (other is VirtualFile) if (this.hashCode() == other.hashCode()) return true
    return false
  }

  override fun hashCode(): Int = path.hashCode() + modTime.hashCode() + size.hashCode() // TODO this is crap

  override fun compareTo(other: VirtualFile): Int = path.compareTo(other.path)

}

abstract class GeneralConnection(protocol: Protocol, isLocal: Boolean) {
  var localBasePath: String = ""
  var remoteBasePath: String = ""
  var filterregex: Regex = Regex(""".*""")
  val debugslow = false
  val interrupted = AtomicBoolean(false)
  abstract fun getfile(from: String, mtime: Long, to: String)
  abstract fun getfile(from: String, mtime: Long)
  abstract fun putfile(from: String, mtime: Long): Long // returns mtime if cantSetDate
  abstract fun mkdirrec(absolutePath: String)
  abstract fun deletefile(what: String, mtime: Long)
  abstract fun list(subfolder: String, filterregexp: String, action: (VirtualFile) -> Unit, recursive: Boolean)

  //noinspection ScalaUnusedSymbol
  var onProgress: (Double, Double) -> Unit = (val progressVal: Double, val bytePerSecond: Double) -> {}

  // return dir (most likely NOT absolute path but subfolder!) without trailing /
  fun checkIsDir(path: String): Pair<String, Boolean> {
    val isdir = path.endsWith("/")
    val resp = if (isdir) path.substring(0, path.length-1) else path
    return Pair(resp, isdir)
  }
  fun cleanUp() {}
}

class LocalConnection(protocol: Protocol, isLocal: Boolean): GeneralConnection(protocol, isLocal) {

  override fun deletefile(what: String, mtime: Long) {
    val (cp, _) = checkIsDir(what)
    val fp = Paths.get(remoteBasePath + "/" + cp)
    try {
      Files.delete(fp)
    } catch(DirectoryNotEmptyException) {
        val dir = Files.newDirectoryStream(fp).toList()
        if (runUIwait(dialogOkCancel("Warning", s"Directory \n $cp \n not empty, DELETE ALL?", "Content:\n" + dir.map(a => a.toFile.getName).mkString("\n"))) == true) {
      dir.foreach(f => Files . delete (f) )
      Files.delete(fp)
      return
    }
    }
  }
  override fun putfile(from: String, mtime: Long): Long = {
    val (cp, isdir) = checkIsDir(from)
    logger.debug(s"from=$from isdir=$isdir")
    if (isdir) { // ensure that target path exists
      val abspath = remoteBasePath + "/" + cp
      if (!Files.exists(Paths.get(abspath).getParent)) {
        logger.debug(s"creating folder $cp")
        mkdirrec(Paths.get(abspath).getParent.toString)
      }
    }
    Files.copy(Paths.get(localBasePath + "/" + cp), Paths.get(remoteBasePath + "/" + cp), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
    mtime
  }
  override fun getfile(from: String, mtime: Long, to: String) {
    val (cp, isdir) = checkIsDir(from)
    if (isdir) { // ensure that target path exists
      Files.createDirectories(Paths.get(to)) // simply create parents if necessary, avoids separate check
    } else {
      Files.copy(Paths.get(remoteBasePath + "/" + cp), Paths.get(to), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
    }
    Files.setLastModifiedTime(Paths.get(to), FileTime.fromMillis(mtime))
  }
  override fun getfile(from: String, mtime: Long) {
    val (cp, _) = checkIsDir(from)
    val lp = localBasePath + "/" + cp
    getfile(from, mtime, lp)
  }

  // include the subfolder but root "/" is not allowed!
  fun list(subfolder: String, filterregexp: String, action: (VirtualFile) => Unit, recursive: Boolean) {
    logger.debug(s"listrec(rbp=$remoteBasePath sf=$subfolder rec=$recursive) in thread ${Thread.currentThread().getId}")
    // scalax.io is horribly slow, there is an issue filed
    fun parseContent(cc: Path, goDeeper: Boolean) {
      // on mac 10.8 with oracle java 7, filenames are encoded with strange 'decomposed unicode'. grr
      // this is in addition to the bug that LC_CTYPE is not set. grrr
      // don't use cc.getPath directly!!
      if (Helpers.failat == 4) throw UnsupportedOperationException("fail 4")
      val javaPath = toJavaPathSeparator(cc.toString)
      val fixedPath = java.text.Normalizer.normalize(javaPath, java.text.Normalizer.Form.NFC)
      var strippedPath: String = if (fixedPath == remoteBasePath) "/" else fixedPath.substring(remoteBasePath.length)
      if (Files.isDirectory(cc) && strippedPath != "/") strippedPath += "/"
      val vf = VirtualFile(strippedPath, Files.getLastModifiedTime(cc).toMillis, Files.size(cc))
      if ( !vf.fileName.matches(filterregexp)) {
        if (debugslow) Thread.sleep(500)
        action(vf)
        if (Files.isDirectory(cc) && goDeeper ) {
          val dir = Files.newDirectoryStream(cc)
          for (cc1 <- dir.asScala) parseContent(cc1, goDeeper = recursive)
          dir.close()
        }
      }
      unit()
    }
    val sp = Paths.get(remoteBasePath + (if (subfolder.length>0) "/" else "") + subfolder)
    if (Files.exists(sp)) {
      parseContent(sp, goDeeper = true)
    }
    logger.debug(s"listrec DONE (rbp=$remoteBasePath sf=$subfolder rec=$recursive) in thread ${Thread.currentThread().getId}")
  }

  fun mkdirrec(absolutePath: String): Unit = {
    Files.createDirectories(Paths.get(absolutePath))
  }
}


class SftpConnection(protocol: Protocol, isLocal: Boolean, var uri: MyURI): GeneralConnection(protocol, isLocal) {

  class MyTransferListener(var relPath: String = "") extends TransferListener {
    var bytesTransferred: Long = 0
    var lastBytesTransferred: Long = 0
    var bytesTotal: Long = 0
    var lastTime: Long = 0

    override fun directory(name: String): TransferListener = {
      MyTransferListener(relPath + name + "/")
    }

    override fun file(name: String, size: Long): Listener = {
      bytesTotal = size
      bytesTransferred = 0
      lastBytesTransferred = 0
      lastTime = System.nanoTime
      (transferred: Long) => {
        bytesTransferred = transferred
        if (interrupted.get) throw InterruptedException("sftp connection interrupted")
        val tnow = System.nanoTime
        if ((tnow - lastTime) / 1.0e9 > 0.5) {
          val byps = (bytesTransferred - lastBytesTransferred) / ((tnow - lastTime) / 1.0e9)
          lastTime = tnow
          lastBytesTransferred = bytesTransferred
          onProgress(bytesTransferred.toDouble / bytesTotal, byps)
        }
      }
    }
  }

  fun isDirectoryx(fa: FileAttributes): Boolean = {
    (fa.getType.toMask & FileMode.Type.DIRECTORY.toMask) > 0
  }

  var transferListener: MyTransferListener = _

  fun deletefile(what: String, mtime: Long) {
    val (cp, isdir) = checkIsDir(what)
    if (isdir) {
      try {
        sftpc.rmdir(remoteBasePath + "/" + cp)
      } catch {
        case _: IOException => // unfortunately only "Failure" ; checking for content would be slow
          val xx = sftpc.ls(remoteBasePath + "/" + cp).asScala
          if (xx.nonEmpty) {
            val tmp = ListBuffer[RemoteResourceInfo]
            for (obj <- xx ) {
              val lse = obj.asInstanceOf[RemoteResourceInfo]
              lse.getName match {
                case "." | ".." =>
                case _ => tmp += lse
              }
            }
            if (runUIwait(dialogOkCancel("Warning", s"Directory \n $cp \n not empty, DELETE ALL?", "Content:\n" + tmp.map(a => a.getName).mkString("\n"))) == true) {
              tmp.foreach(f => sftpc.rm(remoteBasePath + "/" + cp + "/" + f.getName) )
              sftpc.rmdir(remoteBasePath + "/" + cp)
              return
            }
          }
      }
    } else {
      sftpc.rm(remoteBasePath + "/" + cp)
    }
  }
  fun putfile(from: String, mtime: Long): Long = {
    val (cp, isdir) = checkIsDir(from)
    val rp = remoteBasePath + "/" + cp

    fun setAttr(changeperms: Boolean): Unit = {
      val lf = FileSystemFile(localBasePath + "/" + cp)
      val fab = FileAttributes.Builder
      if (changeperms) {
        val perms = FilePermission.fromMask(lf.getPermissions)
        if (protocol.remGroupWrite.value) perms.add(FilePermission.GRP_W) else perms.remove(FilePermission.GRP_W)
        if (protocol.remOthersWrite.value) perms.add(FilePermission.OTH_W) else perms.remove(FilePermission.OTH_W)
        fab.withPermissions(perms)
      }
      fab.withAtimeMtime(lf.getLastAccessTime, lf.getLastModifiedTime)
      sftpc.setattr(rp, fab.build())
    }

    if (isdir) {
      fun checkit(p: String) { // recursively create parents
        val parent = Paths.get(p).getParent.toString
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
        if (!Files.isReadable(Paths.get(localBasePath + "/" + cp))) throw IllegalStateException("can't read file " + cp)
        sftpt.upload(localBasePath + "/" + cp, rp) // use this in place of sftpc.put to not always set file attrs
        if (protocol.doSetPermissions.value) setAttr(true)
        else if (!protocol.cantSetDate.value) setAttr(false)
      } catch {
        case e: Exception =>
        logger.debug(s"putfile: exception: $e")
          if (transferListener.bytesTransferred > 0) { // file may be corrupted, but don't delete if nothing transferred
            // prevent delete of root-owned files if user in group admin, sftp rm seems to "override permissions"
            sftpc.rm(rp)
          }
          throw e
      }
      if (transferListener.bytesTotal != transferListener.bytesTransferred)
        throw IllegalStateException(s"filesize mismatch: ${transferListener.bytesTotal} <> ${transferListener.bytesTransferred}")
      if (protocol.cantSetDate.value) {
        sftpc.mtime(rp) * 1000
      } else {
        mtime
      }
    }
  }
  fun getfile(from: String, mtime: Long, to: String) {
    val (cp, isdir) = checkIsDir(from)
    if (isdir) {
      Files.createDirectories(Paths.get(to)) // simply create parents if necessary, avoids separate check
      Files.setLastModifiedTime(Paths.get(to), FileTime.fromMillis(mtime))
    } else {
      try {
        // sftpt.download erases local file if it exists also if remote file can't be read
        val tmpf = createTempFile("sfsync-tempfile", ".dat")
        sftpt.download(remoteBasePath + "/" + cp, tmpf.getAbsolutePath)
        Files.move(tmpf.toPath, Paths.get(to), StandardCopyOption.REPLACE_EXISTING)
      } catch {
        case e: Exception =>
        logger.debug("getfile: exception " + e)
          throw e
      }
      if (transferListener.bytesTotal != transferListener.bytesTransferred)
        throw IllegalStateException(s"filesize mismatch: ${transferListener.bytesTotal} <> ${transferListener.bytesTransferred}")

      Files.setLastModifiedTime(Paths.get(to), FileTime.fromMillis(mtime))
    }
  }
  fun getfile(from: String, mtime: Long) {
    val (cp, _) = checkIsDir(from)
    val lp = localBasePath + "/" + cp
    getfile(from, mtime, lp)
  }
  fun sftpexists(sp: String): FileAttributes = {
    var resls: FileAttributes = null
    try {
      resls = sftpc.stat(sp) // throws exception if not
    } catch {
      case e: SFTPException if e.getStatusCode == StatusCode.NO_SUCH_FILE => logger.debug(e)
      case e: Throwable => throw e
    }
    resls
  }

  fun list(subfolder: String, filterregexp: String, action: (VirtualFile) => Unit, recursive: Boolean) {
    logger.debug(s"listrecsftp(rbp=$remoteBasePath sf=$subfolder rec=$recursive) in thread ${Thread.currentThread().getId}")

    fun VFfromSftp(fullFilePath: String, attrs: FileAttributes) = {
      VirtualFile {
        path= fullFilePath.substring(remoteBasePath.length)
        modTime = attrs.getMtime * 1000
        size = attrs.getSize
        if (isDirectoryx(attrs) && path != "/") path += "/"
      }
    }
    fun parseContent(folder: String) {
      if (Helpers.failat == 3) throw UnsupportedOperationException("fail 3")
      val rris = sftpc.ls(folder).asScala
      val ord = Ordering[RemoteResourceInfo]() { fun compare(l: RemoteResourceInfo, r: RemoteResourceInfo): Int = l.getName compare r.getName }
      for (rri <- rris.sorted(ord)) {
        // if (stopRequested) return
        if (!rri.getName.equals(".") && !rri.getName.equals("..")) {
          val vf = VFfromSftp(rri.getPath, rri.getAttributes)
          if ( !vf.fileName.matches(filterregexp) ) {
            action(vf)
            if (isDirectoryx(rri.getAttributes) && recursive ) {
              parseContent(rri.getPath)
            }
          }
        }
      }
      unit()
    }
    logger.debug("searching " + remoteBasePath + "/" + subfolder)
    val sp = remoteBasePath + (if (subfolder.length>0) "/" else "") + subfolder
    val sftpsp = sftpexists(sp)
    if (sftpsp != null) { // not nice: duplicate code (above)
      val vf = VFfromSftp(sp, sftpsp) // not nice: duplicate code (above)
      if ( !vf.fileName.matches(filterregexp) ) {
        action(vf)
        if (isDirectoryx(sftpsp)) {
          parseContent(sp)
        }
      }
    }
    logger.debug("parsing done")
  }

  // init

//  System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "WARN")

  // see ConsoleKnownHostsVerifier
  class MyHostKeyVerifier extends OpenSSHKnownHosts(DBSettings.knownHostsFile) {
    override fun hostKeyUnverifiableAction(hostname: String, key: PublicKey): Boolean = {
      if (runUIwait(dialogOkCancel("SFTP server verification", s"Can't verify public key of server $hostname",
        s"Fingerprint:\n${SecurityUtils.getFingerprint(key)}\nPress OK to connect and add to SFSync's known_hosts.")).asInstanceOf[Boolean]) {
        entries.add(OpenSSHKnownHosts.SimpleEntry(null, hostname, KeyType.fromKey(key), key))
        write()
        true
      } else false
    }

    override fun hostKeyChangedAction(entry: HostEntry, hostname: String, key: PublicKey): Boolean = {
      if (runUIwait(dialogOkCancel("SFTP server verification", s"Host key of server $hostname has changed!",
        s"Fingerprint:\n${SecurityUtils.getFingerprint(key)}\nPress OK if you are 100% sure if this change was intended.")).asInstanceOf[Boolean]) {
        entries.remove(entry)
        entries.add(OpenSSHKnownHosts.SimpleEntry(null, hostname, KeyType.fromKey(key), key))
        write()
        true
      } else false
    }
  }

  val ssh = SSHClient()
  ssh.addHostKeyVerifier(MyHostKeyVerifier)
  ssh.connect(uri.host, uri.port.toInt)

  private var password = uri.password
  if (password.startsWith("##"))
    password = Tools.crypto.decrypt(password.substring(2)) // decode password

  try {
    ssh.authPublickey(uri.username)
  } catch {
    case e: UserAuthException =>
      info("Public key auth failed: " + e)
      info("auth methods: " + ssh.getUserAuth.getAllowedMethods.asScala.mkString(","))
      // under win7 this doesn't work, try password in any case
//      if (ssh.getUserAuth.getAllowedMethods.exists(s => s == "keyboard-interactive" || s == "password" )) {
        if (password == "") {
          val res = runUIwait(dialogInputString("SSH", s"Public key auth failed, require password. \nNote: to store the password: add to URI string, it will be encrypted", "Password:")).asInstanceOf[String]
          if (res != "") password = res
        }
        if (password != "") {
          info("Trying password login...")
          ssh.authPassword(uri.username, password)
        } else throw UserAuthException("No password")
//      }
  }
  if (!ssh.isAuthenticated) {
    throw UserAuthException("Not authenticated!")
  } else info("Authenticated!")


  private val sftpc = ssh.newSFTPClient
  private val sftpt = sftpc.getFileTransfer

  transferListener = MyTransferListener()
  sftpt.setTransferListener(transferListener)

  sftpt.setPreserveAttributes(false) // don't set permissions remote! Either by user or not at all.

  if (Helpers.failat == 2) throw UnsupportedOperationException("fail 2")

  override fun cleanUp() {
    super.cleanUp()
    sftpc.close()
    if (ssh.isConnected) ssh.disconnect()
  }

  fun mkdirrec(absolutePath: String) {
    throw NotImplementedError("mkdirrec for sftp")
  }
}


