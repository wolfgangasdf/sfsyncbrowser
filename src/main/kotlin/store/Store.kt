package store

import CF
import SortedProperties
import javafx.application.Platform
import javafx.beans.property.BooleanProperty
import javafx.beans.property.IntegerProperty
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.beans.property.StringProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import mu.KotlinLogging
import store.DBSettings.getCacheFilePath
import synchro.*
import synchro.Actions.ALLACTIONS
import synchro.Actions.A_CACHEONLY
import synchro.Actions.A_ISEQUAL
import synchro.Actions.A_RMLOCAL
import synchro.Actions.A_RMREMOTE
import synchro.Actions.A_UNKNOWN
import synchro.Actions.A_USELOCAL
import synchro.Actions.A_USEREMOTE
import tornadofx.onChange
import util.*
import util.Helpers.dformat
import util.Helpers.filecharset
import util.Helpers.getSortedFilteredList
import util.Helpers.tokMGTPE
import java.util.*
import kotlin.math.abs

private val logger = KotlinLogging.logger {}

object DBSettings {
    private var settpath = ""
    private var dbdir = ""

    init {
        settpath = MFile.fromOSPath(when {
            Helpers.isMac() -> System.getProperty("user.home") + "/Library/Application Support/SFSyncBrowser"
            Helpers.isLinux() -> System.getProperty("user.home") + "/.sfsyncbrowser"
            Helpers.isWin() -> Helpers.toJavaPathSeparator(System.getenv("APPDATA")) + "\\SFSyncBrowser"
            else -> throw Exception("operating system not found")
        }).internalPath
        dbdir = "$settpath/cache"
        if (!MFile(dbdir).exists()) {
            logger.info("creating db dir $dbdir")
            MFile(dbdir).createDirectories()
        }
    }

    val lockFile = MFile("$settpath/SSB.lock")
    var logFile: MFile? = null
    val knownHostsFile = MFile("$settpath/known_hosts")

    init {
        knownHostsFile.createNewFile()
    }

    fun getSettingFile(): MFile = MFile("$settpath/sfsyncbrowser.properties")

    fun getLock(): Boolean = lockFile.createNewFile()

    fun releaseLock(): Boolean = lockFile.deletePlain()

    fun getCacheFilePath(cacheid: String) = "$dbdir/$cacheid-cache.txt"

    fun getCacheFolder(cacheid: String) = "$dbdir/$cacheid-cache/" // for temp and file syncs

    fun removeCacheFile(cacheid: String) {
        logger.info("delete cache database $cacheid")
        MFile(getCacheFilePath(cacheid)).delete()
    }
    fun removeCacheFolder(cacheid: String) {
        logger.info("delete cache folder $cacheid recursively")
        MFile(getCacheFolder(cacheid)).deleteRecursively()
    }
}

///////////////////////// settings

class SSBSettings(val editor: StringProperty = SimpleStringProperty(""),
                  val browsercols: StringProperty = SimpleStringProperty(""),
                  val onExitRemoveFilesyncs: BooleanProperty = SimpleBooleanProperty(false),
                  val showHiddenfiles: BooleanProperty = SimpleBooleanProperty(false)
)

enum class SyncType { NORMAL, FILE, TEMP }

// title is filepath for file sync
class Sync(val type: SyncType, val title: StringProperty, val status: StringProperty, val localfolder: StringProperty, val remoteFolder: StringProperty,
           val excludeFilter: StringProperty = SimpleStringProperty(Helpers.defaultOSexcludeFilter()),
           val cacheid: StringProperty = SimpleStringProperty(Date().time.toString()), val server: Server,
           val subsets: ObservableList<SubSet> = getSortedFilteredList(),
           val auto: BooleanProperty = SimpleBooleanProperty(false), val disableFullSync: BooleanProperty = SimpleBooleanProperty(false),
           val permsOverride: StringProperty = SimpleStringProperty("")) {
    private fun getNiceName() = when(type) {
        SyncType.NORMAL -> "Sync"
        SyncType.FILE -> "FileSync"
        SyncType.TEMP -> "TempSync"
    }
    override fun toString() = "[${getNiceName()}] ${title.value}"
    var fileWatcher: FileWatcher? = null
}

class Protocol(val server: Server, val name: StringProperty, val protocoluri: StringProperty, val doSetPermissions: BooleanProperty, val perms: StringProperty,
               val cantSetDate: BooleanProperty, val baseFolder: StringProperty, val password: StringProperty,
               val tunnelHost: StringProperty, val tunnelMode: StringProperty) {
    fun getmyuri() = MyURI(protocoluri.value)
    override fun toString(): String = name.value
    fun tunnelHostname() = tunnelHost.value.split(":").first()
    fun tunnelPort() = tunnelHost.value.split(":").getOrElse(1) { "22" }.toInt()
}

class SubSet(val title: StringProperty, val status: StringProperty, // if title is empty, single file subset!
             val subfolders: ObservableList<String> = getSortedFilteredList(),
             val sync: Sync) {
    override fun toString() = "[SubSet] ${title.value}"
    val isAll: Boolean get() = title.value == "<all>"
    val isSingleFile: Boolean get() = title.value == "<singlefile>"
    companion object {
        fun all(sync: Sync) = SubSet(SimpleStringProperty("<all>"), SimpleStringProperty(""), sync = sync).apply {
            subfolders += "" // this is needed for tasklist[local,remote]!
        }
        fun singlefile(sync: Sync) = SubSet(SimpleStringProperty("<singlefile>"), SimpleStringProperty(""), sync = sync).apply {
            subfolders += sync.title.value // this is filepath!
        }
    }
}

class BrowserBookmark(val server: Server, val path: StringProperty) {
    override fun toString() = "[Bookmark] ${path.value}"

}

class Server(val title: StringProperty, val status: StringProperty, val currentProtocol: IntegerProperty,
             val protocols: ObservableList<Protocol> = getSortedFilteredList(), val syncs: ObservableList<Sync> = getSortedFilteredList(),
             val bookmarks: ObservableList<BrowserBookmark> = getSortedFilteredList()) {
    val protoUI = SimpleObjectProperty<Protocol>().apply {
        onChange {
            closeConnection() ; currentProtocol.set(protocols.indexOf(it))
        }
    }
    override fun toString() = "[Server] ${title.value}"
    fun getConnection(remoteFolder: String): GeneralConnection {
        if (Platform.isFxApplicationThread()) throw Exception("must not be called from JFX thread (blocks, might open dialogs)")
        val proto = getProtocol()
        logger.info("server.getconnection: opening new connection to $proto")
        val connection = when {
            proto.protocoluri.value.startsWith("sftp") -> SftpConnection(proto)
            proto.protocoluri.value.startsWith("file") -> LocalConnection(proto)
            else -> throw java.lang.Exception("unknown connection type")
        }
        connection.assignRemoteBasePath(remoteFolder)
        connection.interrupted.set(false)
        return connection
    }
    fun closeConnection() {
        logger.debug("close connection server $title")
        syncs.forEach {
            logger.debug("stopping watching ${it.fileWatcher?.file}")
            it.fileWatcher?.stop()
        }
        // TODO how to cleanup connections?
//        connection?.cleanUp()
//        connection = null
    }
    fun getProtocol(): Protocol = protocols[currentProtocol.value]
    fun removeSync(sync: Sync) {
        sync.fileWatcher?.stop()
        DBSettings.removeCacheFile(sync.cacheid.value)
        DBSettings.removeCacheFolder(sync.cacheid.value)
        syncs.remove(sync)
    }
}

object SettingsStore {
    val servers = getSortedFilteredList<Server>()
    val ssbSettings = SSBSettings()
    val tunnelModes = FXCollections.observableArrayList("Off", "On", "Auto")!!

    fun saveSettings() {
        if (servers.isEmpty()) return
        val props = SortedProperties()
        props["settingsversion"] = "1"
        props["ssb.editor"] = ssbSettings.editor.value
        props["ssb.browsercols"] = ssbSettings.browsercols.value
        props["ssb.onExitRemoveFilesyncs"] = ssbSettings.onExitRemoveFilesyncs.value.toString()
        props["ssb.showHiddenfiles"] = ssbSettings.showHiddenfiles.value.toString()
        props["servers"] = servers.size.toString()
        servers.forEachIndexed { idx, server ->
            props["se.$idx.title"] = server.title.value
            props["se.$idx.currentProtocol"] = server.currentProtocol.value.toString()
            props["se.$idx.protocols"] = server.protocols.size.toString()
            server.protocols.forEachIndexed { idx2, proto ->
                props["sp.$idx.$idx2.name"] = proto.name.value
                props["sp.$idx.$idx2.uri"] = proto.protocoluri.value
                props["sp.$idx.$idx2.cantSetDate"] = proto.cantSetDate.value.toString()
                props["sp.$idx.$idx2.doSetPermissions"] = proto.doSetPermissions.value.toString()
                props["sp.$idx.$idx2.perms"] = proto.perms.value
                props["sp.$idx.$idx2.baseFolder"] = proto.baseFolder.value
                props["sp.$idx.$idx2.password"] = proto.password.value
                props["sp.$idx.$idx2.tunnelHost"] = proto.tunnelHost.value
                props["sp.$idx.$idx2.tunnelMode"] = proto.tunnelMode.value
            }
            props["se.$idx.bookmarks"] = server.bookmarks.size.toString()
            server.bookmarks.forEachIndexed { idx2, bookmark ->
                props["sb.$idx.$idx2.path"] = bookmark.path.value
            }
            props["se.$idx.syncs"] = server.syncs.size.toString()
            server.syncs.forEachIndexed { idx2, sync ->
                props["sy.$idx.$idx2.type"] = sync.type.name
                props["sy.$idx.$idx2.title"] = sync.title.value
                props["sy.$idx.$idx2.cacheid"] = sync.cacheid.value
                props["sy.$idx.$idx2.localfolder"] = sync.localfolder.value
                props["sy.$idx.$idx2.remoteFolder"] = sync.remoteFolder.value
                props["sy.$idx.$idx2.excludeFilter"] = sync.excludeFilter.value
                props["sy.$idx.$idx2.auto"] = sync.auto.value.toString()
                props["sy.$idx.$idx2.disableFullSync"] = sync.disableFullSync.value.toString()
                props["sy.$idx.$idx2.permsOverride"] = sync.permsOverride.value.toString()
                props["sy.$idx.$idx2.subsets"] = sync.subsets.size.toString()
                sync.subsets.forEachIndexed { iss, subSet ->
                    props["ss.$idx.$idx2.$iss.title"] = subSet.title.value
                    props["ss.$idx.$idx2.$iss.subfolders"] = subSet.subfolders.size.toString()
                    subSet.subfolders.forEachIndexed { irf, s ->
                        props["sssf.$idx.$idx2.$iss.$irf"] = s
                    }
                }
            }
        }
        val fw = DBSettings.getSettingFile().newFileWriter()
        props.store(fw, null)
        logger.info("settings saved!")
    }

    private fun loadSettings() {
        logger.info("load settings ${DBSettings.getSettingFile()}")
        servers.clear()
        if (DBSettings.getSettingFile().exists()) {
            val propsx = Properties()
            val fr = DBSettings.getSettingFile().newFileReader()
            propsx.load(fr)
            val props = propsx.map { (k, v) -> k.toString() to v.toString() }.toMap()
            if (props["settingsversion"] != "1") throw UnsupportedOperationException("wrong settingsversion!")
            fun p2sp(key: String) = SimpleStringProperty(props.getOrDefault(key, ""))
            fun p2bp(key: String) = SimpleBooleanProperty(props.getOrDefault(key, "0").toBoolean())
            fun p2ip(key: String) = SimpleIntegerProperty(props.getOrDefault(key, "0").toInt())
            try {
                ssbSettings.editor.set(props.getOrDefault("ssb.editor", ""))
                ssbSettings.browsercols.set(props.getOrDefault("ssb.browsercols", ""))
                ssbSettings.onExitRemoveFilesyncs.set(props.getOrDefault("ssb.onExitRemoveFilesyncs", "0").toBoolean())
                ssbSettings.showHiddenfiles.set(props.getOrDefault("ssb.showHiddenfiles", "0").toBoolean())
                for (idx in 0 until props.getOrDefault("servers", "0").toInt()) {
                    val server = Server(p2sp("se.$idx.title"),
                            SimpleStringProperty(""), p2ip("se.$idx.currentProtocol"))
                    for (idx2 in 0 until props.getOrDefault("se.$idx.protocols", "0").toInt()) {
                        server.protocols += Protocol(server, p2sp("sp.$idx.$idx2.name"), p2sp("sp.$idx.$idx2.uri"), p2bp("sp.$idx.$idx2.doSetPermissions"),
                                p2sp("sp.$idx.$idx2.perms"), p2bp("sp.$idx.$idx2.cantSetDate"), p2sp("sp.$idx.$idx2.baseFolder"),
                                p2sp("sp.$idx.$idx2.password"), p2sp("sp.$idx.$idx2.tunnelHost"), p2sp("sp.$idx.$idx2.tunnelMode"))
                    }
                    for (idx2 in 0 until props.getOrDefault("se.$idx.bookmarks", "0").toInt()) {
                        server.bookmarks += BrowserBookmark(server, p2sp("sb.$idx.$idx2.path"))
                    }
                    if (server.currentProtocol.value > -1) server.protoUI.set(server.getProtocol())
                    for (idx2 in 0 until props.getOrDefault("se.$idx.syncs", "0").toInt()) {
                        val sync = Sync(SyncType.valueOf(props.getOrDefault("sy.$idx.$idx2.type", SyncType.NORMAL.name)), p2sp("sy.$idx.$idx2.title"),
                                SimpleStringProperty(""), p2sp("sy.$idx.$idx2.localfolder"), p2sp("sy.$idx.$idx2.remoteFolder"), p2sp("sy.$idx.$idx2.excludeFilter"), p2sp("sy.$idx.$idx2.cacheid"), server,
                                auto = p2bp("sy.$idx.$idx2.auto"), disableFullSync = p2bp("sy.$idx.$idx2.disableFullSync"),
                                permsOverride = p2sp("sy.$idx.$idx2.permsOverride"))
                        if (sync.auto.get()) sync.status.set("need to re-start auto sync!")
                        for (iss in 0 until props.getOrDefault("sy.$idx.$idx2.subsets", "0").toInt()) {
                            val subSet = SubSet(p2sp("ss.$idx.$idx2.$iss.title"), SimpleStringProperty(""), sync=sync)
                            for (irf in 0 until props.getValue("ss.$idx.$idx2.$iss.subfolders").toInt()) subSet.subfolders += props.getValue("sssf.$idx.$idx2.$iss.$irf")
                            sync.subsets += subSet
                        }
                        server.syncs += sync
                    }
                    servers += server
                }
            } catch (e: Exception) {
                logger.error("error loading settings: ${e.message}")
                e.printStackTrace()
            }
            logger.info("settings loaded!")
        }
    }

    fun shutdown() {
        servers.forEach { it.closeConnection() }
    }

    init {
        loadSettings()
    }

}




///////////////////////////////////// cache


class SyncEntry2(var path: String, var se: SyncEntry) {
    override fun toString(): String = "[path=$path action=[${CF.amap[se.action]}] lTime=${se.lTime} lSize=${se.lSize} rTime=${se.rTime} rSize=${se.rSize} lcTime=${se.lcTime} rcTime=${se.rcTime} cSize=${se.cSize} rel=${se.relevant}"
    fun toStringNice(): String =
            """
     |Path: $path (${(if (se.isDir) "dir" else "file")} ${se.hasCachedParent})
     |Local : ${se.detailsLocal().value}
     |Remote: ${se.detailsRemote().value}
     |LCache : ${se.detailsLCache().value}
     |RCache : ${se.detailsRCache().value}
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
                var delete: Boolean = false
) {
    var hasCachedParent = false // only used for folders!
    private fun sameTime(t1: Long, t2: Long): Boolean = abs(t1 - t2) < 2000 // in milliseconds

    fun status() = SimpleStringProperty(this, "status", CF.amap[action])
    fun detailsLocal() = SimpleStringProperty(this, "detailsl",
            if (lSize != -1L) dformat().format(Date(lTime)) + "(" + tokMGTPE(lSize) + ")" else "none")

    fun detailsRemote() = SimpleStringProperty(this, "detailsr",
            if (rSize != -1L) dformat().format(Date(rTime)) + "(" + tokMGTPE(rSize) + ")" else "none")

    fun detailsRCache() = SimpleStringProperty(this, "detailsrc",
            if (cSize != -1L) dformat().format(Date(rcTime)) + "(" + tokMGTPE(cSize) + ")" else "none")

    fun detailsLCache() = SimpleStringProperty(this, "detailslc",
            if (cSize != -1L) dformat().format(Date(lcTime)) + "(" + tokMGTPE(cSize) + ")" else "none")

    //  fun isDir = path.endsWith("/")
    fun isEqual(): Boolean =
            if (isDir) {
                lSize != -1L && rSize != -1L
            } else {
                lSize != -1L && lSize == rSize && sameTime(lTime, rTime)
            }

    private fun isLeqC(): Boolean =
            if (isDir) {
                lSize != -1L && cSize != -1L
            } else {
                lSize != -1L && lSize == cSize && sameTime(lTime, lcTime)
            }

    private fun isReqC(): Boolean =
            if (isDir) {
                rSize != -1L && cSize != -1L
            } else {
                rSize != -1L && rSize == cSize && sameTime(rTime, rcTime)
            }


    fun compareSetAction(useNewFiles: Boolean): SyncEntry {
        action = -9
        if (lSize == -1L && rSize == -1L) { // cache only?
            action = A_CACHEONLY
        } else if (isEqual()) { // just equal?
            action = A_ISEQUAL
        } else if (cSize == -1L) { // not in remote cache
            action = if (!useNewFiles) { // not equal, not in cache
                A_UNKNOWN
            } else { // not in cache but useNewFiles: use file no matter from where if no conflict
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
    fun iterate(reversed: Boolean = false, func: (MutableIterator<Map.Entry<K, V>>, K, V) -> Any) {
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
class Cache(private val cacheid: String) {
    private val cacheversion = "V1"
    var cache = MyTreeMap<String, SyncEntry>()
    private var observableListSleep = false
    var observableList = FXCollections.observableArrayList<SyncEntry2>()!!

    init {
        observableList.onChange { op ->
            // automatically update treemap from UI changes.
            if (!observableListSleep) {
                while (op.next()) {
                    if (op.wasUpdated()) {
                        observableList.subList(op.from, op.to).forEach { se2 ->
                            logger.debug("changed se2: " + se2.toStringNice())
                            cache[se2.path] = se2.se
                        }
                    } else throw NotImplementedError("obslist wants to do something else! $op")
                }
            }
        }
    }

    @Suppress("unused")
    fun dumpAll() {
        logger.debug("--- cache dumpall:")
        cache.iterate { _, path, se -> logger.debug("$path: $se") }
    }

    private fun iniCache() {
        cache = MyTreeMap()
        observableListSleep = true
        Platform.runLater { observableList.clear() }
        observableListSleep = false
    }

    // splits safely: at first comma
    private fun splitsetting(ss: String): List<String> {
        val commapos = ss.indexOf(",")
        val tag = ss.substring(0, commapos)
        val content = ss.substring(commapos + 1).trim()
        //    debug(tag+" , " + content)
        return listOf(tag, content)
    }

    fun loadCache() {
        logger.info("load cache database...$cacheid")
        iniCache()

        val fff = MFile(getCacheFilePath(cacheid))
        if (!fff.exists()) {
            logger.info("create cache file!")
            if (!fff.parent()!!.exists()) fff.parent()!!.createDirectories()
            fff.createNewFile()
        }
        val br = fff.newBufferedReader(filecharset)
        val cacheVersion = br.readLine()
        if (cacheVersion == cacheversion) {

            for (lll in br.lines()) {
                var sett = splitsetting(lll)
                val lcTime = sett.first().toLong()
                sett = splitsetting(sett[1])
                val rcTime = sett.first().toLong()
                sett = splitsetting(sett[1])
                val size = sett.first().toLong()
                val path = sett[1] // this is safe, also commas in filename ok
                val vf = SyncEntry(A_UNKNOWN, -1, -1, -1, -1, lcTime, rcTime, size, VirtualFile.isDir(path), false)
                cache[path] = vf
            }
        } else {
            logger.info("Don't load cache, wrong cache version $cacheVersion <> $cacheversion")
        }
        br.close()
        updateObservableBuffer()
        logger.info("cache database loaded (${cache.size} entries)!")
    }

    fun saveCache() {
        logger.info("save cache database...$cacheid")
        val fff = MFile(getCacheFilePath(cacheid))
        if (fff.exists()) fff.delete()
        val out = fff.newBufferedWriter(1000000)
        out.write(cacheversion + "\n")
        for ((path, cf: SyncEntry) in cache) {
            out.write("" + cf.lcTime + "," + cf.rcTime + "," + cf.cSize + "," + path + "\n")
        }
        out.close()
        logger.info("cache database saved!")
    }

    // for listview
    var filterActions = ALLACTIONS

    fun updateObservableBuffer() {
        logger.debug("update obs buffer...")
        observableListSleep = true
        observableList.clear()

        cache.iterate { _, path, se ->
            if (se.relevant && filterActions.contains(se.action)) {
                observableList.add(SyncEntry2(path, se))
            } else true
        }
        observableListSleep = false
        logger.debug("update obs buffer done! ${observableList.size}")
    }

    fun canSync(): Boolean {
        for ((_, se: SyncEntry) in cache) {
            if (se.relevant && se.action == A_UNKNOWN) return false
        }
        return true
    }


}
