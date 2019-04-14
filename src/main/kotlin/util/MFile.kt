package util

import java.io.*
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission


/* This must be used always if local files are handled, instead of java.io.File and java.nio.file.Path
    it does not use the virtualfile convention that directories end with "/"!
    internalpath: unix-like path with forward slashes, allows easy string manipulation
    For windows, it maps internalpath -> something (TODO):
        /c/folder/ -> "c:/folder/" (i.e., on windows, all absolute paths start with drive letter)
            works with File("c:/asdf")
        //SERVER/some/path is UNC path (works with File("//SERVER/some/path") )
 */

fun File.asMFile() = MFile(this)

// immutable!
class MFile(val internalPath: String) {
    override fun toString(): String = internalPath
    val file = File(ospathFromIP(internalPath))
    val name: String = file.name

    companion object {
        private val isWin = System.getProperty("os.name").toLowerCase().contains("win")
        fun createTempFile(prefix: String, suffix: String) = MFile(Files.createTempFile(prefix, suffix).toFile())
        fun createTempDirectory(prefix: String) = MFile(Files.createTempDirectory(prefix))
        fun ipFromFile(f: File): String {
            val ap = java.text.Normalizer.normalize(f.absolutePath, java.text.Normalizer.Form.NFC) // make sure it's in canonical form
            return when {
                isWin -> ap // TODO win
                else -> ap
            }
        }
        fun ospathFromIP(ip: String): String = when {
            isWin -> ip // TODO win
            else -> ip
        }
        fun move(from: String, to: String, vararg options: CopyOption) { // internal paths
            Files.move(MFile(from).asPath(), MFile(to).asPath(), *options)
        }
        fun copy(from: String, to: String, vararg options: CopyOption) { // internal paths
            Files.copy(MFile(from).asPath(), MFile(to).asPath(), *options)
        }
        // helper functions for internalpaths, without calling anything os-specific (TODO: check windows)
        fun getIPFileName(ip: String): String = File(ip).name
        fun getIPFileExt(ip: String) = File(ip).extension
        fun getIPFileParent(ip: String): String? = File(ip).parent
    }

    constructor(f: File) : this(ipFromFile(f))
    constructor(p: Path) : this(ipFromFile(p.toFile()))
    constructor(uri: URI) : this(File(uri))

    fun asPath(): Path = file.toPath()
    fun getOSPath() = ospathFromIP(internalPath)
    fun readFileToString(): String {
        val enc = Files.readAllBytes(file.toPath())
        return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(enc)).toString()
    }
    fun relativeTo(base: MFile) = file.relativeTo(base.file).asMFile()
    fun lastModified() = file.lastModified()

    // direct copies from File or Path
    fun getParent(): String? = file.parent
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
    fun delete() = file.delete()
    fun deleteThrow() { Files.delete(asPath()) } // this throws exception if failed
    fun setLastModifiedTime(mtime: Long) { Files.setLastModifiedTime(asPath(), FileTime.fromMillis(mtime)) }
    fun getLastModifiedTime() = Files.getLastModifiedTime(asPath()).toMillis()
    fun isDirectory(vararg options: LinkOption) = Files.isDirectory(asPath(), *options)
    fun isRegularFile(vararg options: LinkOption) = Files.isRegularFile(asPath(), *options)
    fun getSize() = Files.size(asPath())
    fun getPosixFilePermissions(): MutableSet<PosixFilePermission> = Files.getPosixFilePermissions(asPath())
    fun isReadable() = Files.isReadable(asPath())
}
