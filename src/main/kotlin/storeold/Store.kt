@file:Suppress("unused") // TODO

package storeold

import CF
import Helpers
import Helpers.filecharset
import javafx.beans.property.Property
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import mu.KotlinLogging
import storeold.Tools.splitsetting
import synchro.Actions.ALLACTIONS
import synchro.Actions.A_CACHEONLY
import synchro.Actions.A_ISEQUAL
import synchro.Actions.A_RMLOCAL
import synchro.Actions.A_RMREMOTE
import synchro.Actions.A_UNKNOWN
import synchro.Actions.A_USELOCAL
import synchro.Actions.A_USEREMOTE
import tornadofx.onChange
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

private val logger = KotlinLogging.logger {}

object DBSettings {
    private var settpath = ""
    private var dbdir = ""

    init {
        when {
            Helpers.isMac() -> {
                settpath = System.getProperty("user.home") + "/Library/Application Support/SFSync"
                dbdir = "$settpath/cache"
            }
            Helpers.isLinux() -> {
                settpath = System.getProperty("user.home") + "/.sfsync"
                dbdir = "$settpath/cache"
            }
            Helpers.isWin() -> {
                settpath = Helpers.toJavaPathSeparator(System.getenv("APPDATA")) + "/SFSync"
                dbdir = "$settpath/cache"
            }
            else -> throw Exception("operating system not found")
        }
    }

    private val lockFile = java.io.File("$settpath/sfsync.lock")

    private val knownHostsFile = java.io.File("$settpath/known_hosts")

    init {
        knownHostsFile.createNewFile()
    }

    fun dbpath(name: String): String = "$dbdir/$name"

    fun getSettingPath(): String = "$settpath/sfsyncsettings.txt"

    fun getLock(): Boolean = lockFile.createNewFile()

    fun releaseLock(): Boolean = lockFile.delete()

    fun getLines(): Array<String> {
        val fff = Paths.get(getSettingPath())
        if (!Files.exists(fff)) {
            logger.info("creating setting file " + fff.toString())
            Files.createDirectories(fff.getParent())
            Files.createFile(fff)
        }
        return Files.readAllLines(fff, Helpers.filecharset).toTypedArray()
    }


}

object Tools {
    // splits safely: at first comma
    fun splitsetting(ss: String): List<String> {
        val commapos = ss.indexOf(",")
        val tag = ss.substring(0, commapos)
        val content = ss.substring(commapos + 1).trim()
        //    debug(tag+" , " + content)
        return listOf(tag, content)
    }
//  val crypto = JavaCryptoEncryption("DES")
}

// TODO removed JavaCryptoEncryption do plain text in settings file (dont show in UI)

class Config {
    var servers = FXCollections.observableArrayList<Server>()
    val width = SimpleIntegerProperty(800)
    val height = SimpleIntegerProperty(600)
    val x = SimpleIntegerProperty(100)
    val y = SimpleIntegerProperty(100)
}

open class ListableThing : Comparable<ListableThing> {
    val name = SimpleStringProperty("<new>")
    override fun compareTo(other: ListableThing): Int =
            this.name.valueSafe.compareTo(other.name.valueSafe)
}

class Server : ListableThing() {
    val id = SimpleStringProperty(java.util.Date().time.toString())
    val localFolder = SimpleStringProperty("")
    val filterRegexp = SimpleStringProperty("(/._.*)|(.DS_Store)|(.AppleDouble)")
    var protocols = FXCollections.observableArrayList<Protocol>()
    val currentProtocol = SimpleIntegerProperty(-1)
    var subfolders = FXCollections.observableArrayList<SubFolder>()
    val currentSubFolder = SimpleIntegerProperty(-1)
    override fun toString(): String = name.valueSafe // used for listview
}

class Protocol : ListableThing() {
    val protocoluri = SimpleStringProperty("file:///")
    val protocolbasefolder = SimpleStringProperty("")
    val doSetPermissions = SimpleBooleanProperty(false)
    val remGroupWrite = SimpleBooleanProperty(false)
    val remOthersWrite = SimpleBooleanProperty(false)
    val cantSetDate = SimpleBooleanProperty(false)
    val executeBefore = SimpleStringProperty("")
    val executeAfter = SimpleStringProperty("")
    override fun toString(): String = name.valueSafe
}

class SubFolder : ListableThing() {
    var subfolders = FXCollections.observableArrayList<String>()
    override fun toString(): String = name.valueSafe
}


object Store {
    var config = Config()

    fun save() {
        logger.info("-----------save $config")
        val fff = Paths.get(DBSettings.getSettingPath())
        Files.delete(fff)
        Files.createFile(fff)
        fun saveVal(key: String, what: Property<*>) {
            Files.write(fff, (key + "," + what.value + "\n").toByteArray(filecharset), StandardOpenOption.APPEND)
        }

        fun saveString(key: String, what: String) {
            Files.write(fff, ("$key,$what\n").toByteArray(filecharset), StandardOpenOption.APPEND)
        }
        Files.write(fff, "sfsyncsettingsversion,1\n".toByteArray(filecharset), StandardOpenOption.APPEND)
        saveVal("width", config.width)
        saveVal("height", config.height)
        for (server in config.servers) {
            saveVal("server", server.name)
            saveVal("localfolder", server.localFolder)
            saveVal("filterregexp", server.filterRegexp)
            saveVal("id", server.id)
            saveVal("protocolcurr", server.currentProtocol)
            for (proto in server.protocols) {
                saveVal("protocol", proto.name)
                saveVal("protocoluri", proto.protocoluri)
                saveVal("protocolbasefolder", proto.protocolbasefolder)
                saveVal("protocoldosetpermissions", proto.doSetPermissions)
                saveVal("protocolremgroupwrite", proto.remGroupWrite)
                saveVal("protocolremotherswrite", proto.remOthersWrite)
                saveVal("protocolcantsetdate", proto.cantSetDate)
                saveVal("protocolexbefore", proto.executeBefore)
                saveVal("protocolexafter", proto.executeAfter)
            }
            saveVal("subfoldercurr", server.currentSubFolder)
            for (subf in server.subfolders) {
                saveVal("subfolder", subf.name)
                for (subff in subf.subfolders) {
                    saveString("subfolderfolder", subff)
                }
            }
        }
        logger.info("-----------/save")
    }

    fun load() {
        var lastserver = Server() // TODO was null...
        var lastprotocol = Protocol()
        var lastsubfolder = SubFolder()
        logger.info("----------load")
        val lines = DBSettings.getLines()
        if (lines.isEmpty()) {
            logger.info("no config file...")
            config = Config()
        } else {
            lines.forEach { lll ->
                val sett = splitsetting(lll)
                when (sett.first()) {
                    "sfsyncsettingsversion" -> {
                        if (!sett[1].equals("1")) throw RuntimeException("wrong settings version")
                        config = Config()
                    }
                    "width" -> config.width.value = sett[1].toInt()
                    "height" -> config.height.value = sett[1].toInt()
                    "server" -> {
                        lastserver = Server()
                        lastserver.name.set(sett[1])
                        config.servers.add(lastserver)
                    }
                    "localfolder" -> lastserver.localFolder.value = sett[1]
                    "filterregexp" -> lastserver.filterRegexp.value = sett[1]
                    "id" -> lastserver.id.value = sett[1]
                    "protocolcurr" -> lastserver.currentProtocol.value = sett[1].toInt()
                    "protocol" -> {
                        lastprotocol = Protocol()
                        lastprotocol.name.set(sett[1])
                        lastserver.protocols.add(lastprotocol)
                    }
                    "protocoluri" -> lastprotocol.protocoluri.value = sett[1]
                    "protocolbasefolder" -> lastprotocol.protocolbasefolder.value = sett[1]
                    "protocoldosetpermissions" -> lastprotocol.doSetPermissions.value = sett[1].toBoolean()
                    "protocolremgroupwrite" -> lastprotocol.remGroupWrite.value = sett[1].toBoolean()
                    "protocolremotherswrite" -> lastprotocol.remOthersWrite.value = sett[1].toBoolean()
                    "protocolcantsetdate" -> lastprotocol.cantSetDate.value = sett[1].toBoolean()
                    "protocolexbefore" -> lastprotocol.executeBefore.value = sett[1]
                    "protocolexafter" -> lastprotocol.executeAfter.value = sett[1]
                    "subfoldercurr" -> lastserver.currentSubFolder.value = sett[1].toInt()
                    "subfolder" -> {
                        lastsubfolder = SubFolder()
                        lastsubfolder.name.set(sett[1])
                        lastserver.subfolders.add(lastsubfolder)
                    }
                    "subfolderfolder" -> lastsubfolder.subfolders.add(sett[1])
                    else -> logger.warn("unknown tag in config file: <" + sett.first() + ">")
                }
            }
        }
    }

    fun dumpConfig() {
        logger.info("--------------dumpconfig")
        for (server in config.servers) {
            logger.info("server: " + server + " currprot=" + server.currentProtocol + " currsf=" + server.currentSubFolder)
            server.protocols.forEach { proto -> logger.info("  proto: $proto") }
            server.subfolders.forEach { sf -> logger.info("  subfolder: $sf [${sf.subfolders}]") }
        }
        logger.info("--------------/dumpconfig")
    }

    init {
        load()
    }

}

class SyncEntry2(var path: String, var se: SyncEntry) {
    override fun toString(): String = "[path=$path action=[${CF.amap[se.action]}] lTime=${se.lTime} lSize=${se.lSize} rTime=${se.rTime} rSize=${se.rSize} lcTime=${se.lcTime} rcTime=${se.rcTime} cSize=${se.cSize} rel=${se.relevant}"
    fun toStringNice(): String =
            """
     |Path: TODO
     |Local : ${se.detailsLocal().value}
     |Remote: ${se.detailsRemote().value}
     |LCache : ${se.detailsLCache().value}
     |RCache : ${se.detailsRCache().value} (${se.hasCachedParent})
    """.trimMargin()

}

// if path endswith '/', it's a dir!!!
// if ?Size == -1: file does not exist
class SyncEntry(var action: Int,
                var lTime: Long, var lSize: Long,
                var rTime: Long, var rSize: Long,
                var lcTime: Long, var rcTime: Long, var cSize: Long,
                var isDir: Boolean,
                var relevant: Boolean,
                var selected: Boolean = false,
                var delete: Boolean = false
) {
    var hasCachedParent = false // only used for folders!
    private fun sameTime(t1: Long, t2: Long): Boolean = Math.abs(t1 - t2) < 2000 // in milliseconds

    fun status() = SimpleStringProperty(this, "status", CF.amap[action])
    private fun dformat() = java.text.SimpleDateFormat("dd-MM-yyyy HH:mm:ss")
    fun detailsLocal() = SimpleStringProperty(this, "detailsl",
            if (lSize != -1L) dformat().format(java.util.Date(lTime)) + "(" + lSize + ")" else "none")

    fun detailsRemote() = SimpleStringProperty(this, "detailsr",
            if (rSize != -1L) dformat().format(java.util.Date(rTime)) + "(" + rSize + ")" else "none")

    fun detailsRCache() = SimpleStringProperty(this, "detailsrc",
            if (cSize != -1L) dformat().format(java.util.Date(rcTime)) + "(" + cSize + ")" else "none")

    fun detailsLCache() = SimpleStringProperty(this, "detailslc",
            if (cSize != -1L) dformat().format(java.util.Date(lcTime)) + "(" + cSize + ")" else "none")

    //  fun isDir = path.endsWith("/")
    private fun isEqual(): Boolean =
            if (isDir) {
                if (lSize != -1L && rSize != -1L) true else false
            } else {
                if (lSize != -1L && lSize == rSize && sameTime(lTime, rTime)) true else false
            }

    private fun isLeqC(): Boolean =
            if (isDir) {
                if (lSize != -1L && cSize != -1L) true else false
            } else {
                if (lSize != -1L && lSize == cSize && sameTime(lTime, lcTime)) true else false
            }

    private fun isReqC(): Boolean =
            if (isDir) {
                if (rSize != -1L && cSize != -1L) true else false
            } else {
                if (rSize != -1L && rSize == cSize && sameTime(rTime, rcTime)) true else false
            }


    fun compareSetAction(newcache: Boolean): SyncEntry {
        action = -9
        if (lSize == -1L && rSize == -1L) { // cache only?
            action = A_CACHEONLY
        } else if (isEqual()) { // just equal?
            action = A_ISEQUAL
        } else if (cSize == -1L) { // not in remote cache
            action = if (newcache) { // not equal, not in cache because cache new
                A_UNKNOWN
            } else { // not in cache but cache not new: new file?
                if (lSize != -1L && rSize == -1L) A_USELOCAL // new local (cache not new)
                else if (lSize == -1L && rSize != -1L) A_USEREMOTE // new remote (cache not new)
                else A_UNKNOWN // not in cache but both present
            }
        } else { // in cache, not equal
            action = if (isLeqC() && isReqC()) A_ISEQUAL // apparently, cantSetDate=true
            else if (isLeqC() && rSize == -1L) A_RMLOCAL // remote was deleted (local still in cache)
            else if (lSize == -1L && isReqC()) A_RMREMOTE // local was deleted (remote still in cache)
            // both exist, as does fcache
            else if (isLeqC() && rTime > rcTime) A_USEREMOTE // flocal unchanged, remote newer
            else if (isReqC() && lTime > lcTime) A_USELOCAL // fremote unchanged, local newer
            else A_UNKNOWN // both changed and all other strange things that might occur
        }
        assert(action != -9)
        return this
    }

    override fun toString() = "[action=[${CF.amap[action]}] lTime=$lTime lSize=$lSize rTime=$rTime rSize=$rSize lcTime=$lcTime rcTime=$rcTime cSize=$cSize rel=$relevant"

}

// MUST be sorted like treemap: first, cache file is put here, then possibly new files added, which must be sorted.
// need fast access by path (==key): hashmap. only solution: treemap.
// must be synchronized: better ConcurrentSkipListMap as synchronizedSortedMap(TreeMap) locks whole thing
class MyTreeMap<K, V> : java.util.concurrent.ConcurrentSkipListMap<K, V>() {
    // old with treemap: this is 10x faster than foreach, can do it.remove(), return false to stop (or true/Unit for continue)
    fun iterate(reversed: Boolean = false, func: (Iterator<Map.Entry<K, V>>, K, V) -> Any) {
        val it = if (reversed)
            this.descendingMap().entries.iterator()
        else
            this.entries.iterator()
        var fres = true
        while (it.hasNext() && fres) {
            val ele = it.next()
            val fres1 = func(it, ele.key, ele.value)
            fres = when (fres1) {
                is Boolean -> fres1
                else -> true
            }
        }
    }
}


// this holds the main database of files. also takes care of GUI observable list
object Cache {
    private const val CACHEVERSION = "V1"
    var cache = MyTreeMap<String, SyncEntry>()
    private var observableListSleep = false
    var observableList = FXCollections.emptyObservableList<SyncEntry2>()!!

    init {
        observableList.onChange { op ->
            // automatically update treemap from UI changes
            if (!observableListSleep) {
                if (op.wasUpdated()) {
                    observableList.subList(op.from, op.to).forEach { se2 ->
                        logger.debug("changed se2: " + se2.toStringNice())
                        cache.put(se2.path, se2.se)
                    }
                } else throw NotImplementedError("obslist wants to do something else! ")
            }
        }
    }

    fun dumpAll() {
        cache.iterate { _, path, se -> println(path + ": " + se.toString()) }
    }

    private fun getCacheFilename(name: String) = "" + DBSettings.dbpath(name) + "-cache.txt"

    fun iniCache() {
        cache = MyTreeMap<String, SyncEntry>()
        observableListSleep = true
        observableList.clear()
        observableListSleep = false
    }

    fun loadCache(name: String) {
        logger.info("load cache database...$name")
        iniCache()

        val fff = Paths.get(getCacheFilename(name))
        if (!Files.exists(fff)) {
            logger.info("create cache file!")
            if (!Files.exists(fff.getParent())) Files.createDirectories(fff.getParent())
            Files.createFile(fff)
        }
        val br = Files.newBufferedReader(fff, filecharset)
        val cacheVersion = br.readLine()
        if (cacheVersion == CACHEVERSION) {

            for (lll in br.lines()) {
                var sett = splitsetting(lll)
                val lcTime = sett.first().toLong()
                sett = splitsetting(sett[1])
                val rcTime = sett.first().toLong()
                sett = splitsetting(sett[1])
                val size = sett.first().toLong()
                val path = sett[1] // this is safe, also commas in filename ok
                val vf = SyncEntry(A_UNKNOWN, -1, -1, -1, -1, lcTime, rcTime, size, path.endsWith("/"), false)
                cache.put(path, vf)
            }
        } else {
            logger.info("Don't load cache, wrong cache version $cacheVersion <> $CACHEVERSION")
        }
        br.close()
        updateObservableBuffer()
        logger.info("cache database loaded!")
    }

    fun saveCache(name: String) {
        logger.info("save cache database...$name")
        val fff = java.io.File(getCacheFilename(name)) // forget scalax.io.file: much too slow
        if (fff.exists()) fff.delete()
        val out = java.io.BufferedWriter(java.io.FileWriter(fff), 1000000)
        out.write(CACHEVERSION + "\n")
        for ((path, cf: SyncEntry) in cache) {
            out.write("" + cf.lcTime + "," + cf.rcTime + "," + cf.cSize + "," + path + "\n")
        }
        out.close()
        logger.info("cache database saved!")
    }

    fun clearCacheFile(name: String) {
        logger.info("delete cache database $name")
        val fff = Paths.get(getCacheFilename(name))
        if (Files.exists(fff)) {
            Files.delete(fff)
        }
        cache.clear()
        observableList.clear()
    }

    // for listview
    private var filterActions = ALLACTIONS

    private fun updateObservableBuffer() {
        logger.debug("update obs buffer...")
        observableListSleep = true
        observableList.clear()

        // fill obslist
        cache.iterate { _, path, se ->
            if (se.relevant && filterActions.contains(se.action)) {
                observableList.add(SyncEntry2(path, se))
            } else true
        }
        observableListSleep = false
        logger.debug("update obs buffer done!")
    }

    fun canSync(): Boolean {
        for ((_, se: SyncEntry) in cache) {
            if (se.relevant && se.action == A_UNKNOWN) return false
        }
        return true
    }


}
