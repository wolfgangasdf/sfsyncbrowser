package util

import javafx.stage.FileChooser
import mu.KotlinLogging
import tornadofx.chooseDirectory
import tornadofx.chooseFile
import java.io.*
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.util.*
import java.util.jar.JarFile

private val logger = KotlinLogging.logger {}

/* This must be used always if local files are handled, instead of java.io.File and java.nio.file.Path
    it does not use the virtualfile convention that directories end with "/", but must accept it as internalpath!
    internalpath: unix-like path with forward slashes, allows easy string manipulation
    For windows, it maps internalpath -> ip:
        /c/folder/ -> "c:/folder/" (i.e., on windows, all absolute paths start with drive letter)
        //SERVER/some/path is UNC path
 */

// directly wrap any function that returns File or Path into MFile!
fun File.asMFile() = MFile(this)
fun List<File>.asMFile() = this.map { it.asMFile() }

// immutable!
class MFile(val internalPath: String) {
    override fun toString(): String = internalPath
    val file = File(ospathFromIP(internalPath))
    val name: String = file.name

    companion object {
        private val isWin = System.getProperty("os.name").toLowerCase().contains("win")
        private val reWinPath = """(.):\\(.*)""".toRegex()
        private val reWinUNC = """\\\\.*\\.*""".toRegex()
        fun createTempFile(prefix: String, suffix: String) = MFile(Files.createTempFile(prefix, suffix).toFile())
        fun createTempDirectory(prefix: String) = MFile(Files.createTempDirectory(prefix))
        fun fromOSPath(p: String) = MFile(File(p))
        fun ipFromFile(f: File): String {
            val ap = java.text.Normalizer.normalize(f.absolutePath, java.text.Normalizer.Form.NFC) // make sure it's in canonical form
            return when {
                isWin -> {
                    when {
                        ap.matches(reWinPath) -> reWinPath.find(ap)!!.let {
                            "/${it.groupValues[1]}/${it.groupValues[2].replace("\\", "/")}"
                        }
                        ap.matches(reWinUNC) -> ap.replace("\\", "/")
                        else -> throw Exception("Unknown Windows path type: $ap")
                    }
                }
                else -> ap
            }
        }
        fun ospathFromIP(ip: String): String = when {
            isWin -> when {
                ip.matches("/./.*".toRegex()) -> "${ip[1]}:${ip.substring(2).replace("/", "\\")}"
                ip.matches("//.*/.*".toRegex()) -> ip.replace("/","\\")
                else -> throw Exception("Unknown internal path: $ip")
            }
            else -> ip
        }
        fun move(from: String, to: String, vararg options: CopyOption) { // internal paths
            Files.move(MFile(from).asPath(), MFile(to).asPath(), *options)
        }
        fun copy(from: String, to: String, vararg options: CopyOption) { // internal paths
            Files.copy(MFile(from).asPath(), MFile(to).asPath(), *options)
        }

        // helper functions for internalpaths, without calling anything os-specific
        fun getIPFileName(ip: String): String = ip.removeSuffix("/").substringAfterLast("/")
        fun getIPFileExt(ip: String) = getIPFileName(ip).substringAfterLast(".")
        fun getIPFileParent(ip: String): String? = ip.removeSuffix("/").substringBeforeLast("/")

        fun testit() {
            if (isWin) {
                val files = arrayOf("d:\\asdf\\fdsa.txt", "\\\\nix\\storage\\stuff\\all-05.blend")
                fun printit(mf: MFile) {
                    logger.info("* $mf (exists=${mf.exists()}:")
                    logger.info("ip=${mf.internalPath}")
                    logger.info("osp=${mf.getOSPath()}")
                    logger.info("par=${mf.parent()}")
                }
                files.forEach {
                    logger.info("***** $it")
                    val mff = MFile(File(it))
                    logger.info("from File:")
                    printit(mff)
                    val mfp = MFile(Paths.get(it))
                    logger.info("from Paths.get:")
                    printit(mfp)
                }
                val ips = arrayOf("/e/asdf/fdas.txt", "//nix/storage/stuff/all-05.blend")
                ips.forEach {
                    logger.info("***** $it")
                    val mff = MFile(it)
                    printit(mff)
                }
                val f = chooseFile("choose some file (try UNC!)", arrayOf(FileChooser.ExtensionFilter("all", "*"))).asMFile()
                f.forEach { printit(it) }
                val d = chooseDirectory("choose some directory")?.asMFile()
                d?.let { printit(it) }
            }
        }

        fun getClassBuildTime(): Date? { // https://stackoverflow.com/a/22404140
            var d: Date? = null
            val currentClass = object : Any() {

            }.javaClass.enclosingClass
            val resource = currentClass.getResource(currentClass.simpleName + ".class")
            if (resource != null) {
                when(resource.protocol) {
                    "file" -> try {
                        d = Date(File(resource.toURI()).lastModified())
                    } catch (ignored: URISyntaxException) {
                    }
                    "jar" -> {
                        val path = resource.path
                        d = Date(File(path.substring(5, path.indexOf("!"))).lastModified())
                    }
                    "zip" -> {
                        val path = resource.path
                        val jarFileOnDisk = File(path.substring(0, path.indexOf("!")))
                        //long jfodLastModifiedLong = jarFileOnDisk.lastModified ();
                        //Date jfodLasModifiedDate = new Date(jfodLastModifiedLong);
                        try {
                            JarFile(jarFileOnDisk).use { jf ->
                                val ze = jf.getEntry(path.substring(path.indexOf("!") + 2))//Skip the ! and the /
                                val zeTimeLong = ze.time
                                val zeTimeDate = Date(zeTimeLong)
                                d = zeTimeDate
                            }
                        } catch (ignored: IOException) {
                        } catch (ignored: RuntimeException) {
                        }

                    }
                }
            }
            return d
        }
    } // companion

    constructor(f: File) : this(ipFromFile(f))
    constructor(p: Path) : this(ipFromFile(p.toFile()))

    fun asPath(): Path = file.toPath()
    fun getOSPath() = ospathFromIP(internalPath)
    fun readFileToString(): String {
        val enc = Files.readAllBytes(file.toPath())
        return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(enc)).toString()
    }
    fun relativeTo(base: MFile) = file.relativeTo(base.file).asMFile()
    fun lastModified() = file.lastModified()

    // direct copies from File or Path
    fun exists() = file.exists()
    fun createDirectories() { Files.createDirectories(this.asPath()) }
    fun createNewFile() = file.createNewFile()
    fun listFiles() = file.listFiles().map { MFile(it) }
    fun parent() = asPath().parent?.let { MFile(it) }
    fun newBufferedReader(filecharset: Charset): BufferedReader = Files.newBufferedReader(asPath(), filecharset)
    fun newBufferedWriter(sz: Int): BufferedWriter = BufferedWriter(FileWriter(file), sz)
    fun newFileWriter() = FileWriter(file)
    fun newFileReader() = FileReader(file)
    fun newDirectoryStreamList() = Files.newDirectoryStream(asPath()).map { MFile(it) }
    fun setPosixFilePermissions(perms: Set<PosixFilePermission>) { Files.setPosixFilePermissions(asPath(), perms) }
    fun getPosixFilePermissions(): MutableSet<PosixFilePermission> = Files.getPosixFilePermissions(asPath())
    fun delete() = file.delete()
    fun deleteThrow() { Files.delete(asPath()) } // this throws exception if failed
    fun setLastModifiedTime(mtime: Long) { Files.setLastModifiedTime(asPath(), FileTime.fromMillis(mtime)) }
    fun getLastModifiedTime() = Files.getLastModifiedTime(asPath()).toMillis()
    fun isDirectory(vararg options: LinkOption) = Files.isDirectory(asPath(), *options)
    fun isRegularFile(vararg options: LinkOption) = Files.isRegularFile(asPath(), *options)
    fun getSize() = Files.size(asPath())
    fun isReadable() = Files.isReadable(asPath())
}
