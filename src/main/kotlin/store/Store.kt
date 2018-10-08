@file:Suppress("unused") // TODO
package store

import javafx.beans.property.*
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import mu.KotlinLogging
import synchro.Actions.ALLACTIONS
import synchro.Actions.A_CACHEONLY
import synchro.Actions.A_ISEQUAL
import synchro.Actions.A_RMLOCAL
import synchro.Actions.A_RMREMOTE
import synchro.Actions.A_UNKNOWN
import synchro.Actions.A_USELOCAL
import synchro.Actions.A_USEREMOTE
import synchro.GeneralConnection
import synchro.LocalConnection
import synchro.MyURI
import synchro.SftpConnection
import tornadofx.onChange
import util.Helpers
import util.Helpers.filecharset
import java.io.FileReader
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*


private val logger = KotlinLogging.logger {}

object DBSettings {
    private var settpath = ""
    private var dbdir = ""

    init {
        when {
            Helpers.isMac() -> {
                settpath = System.getProperty("user.home") + "/Library/Application Support/SSyncBrowser"
                dbdir = "$settpath/cache"
            }
            Helpers.isLinux() -> {
                settpath = System.getProperty("user.home") + "/.ssyncbrowser"
                dbdir = "$settpath/cache"
            }
            Helpers.isWin() -> {
                settpath = Helpers.toJavaPathSeparator(System.getenv("APPDATA")) + "/SSyncBrowser"
                dbdir = "$settpath/cache"
            }
            else -> throw Exception("operating system not found")
        }
        if (!Files.exists(Paths.get(dbdir))) {
            logger.info("creating db dir $dbdir")
            Files.createDirectories(Paths.get(dbdir))
        }
    }

    private val lockFile = java.io.File("$settpath/SSB.lock")

    val knownHostsFile = java.io.File("$settpath/known_hosts")

    init {
        knownHostsFile.createNewFile()
    }

    fun dbpath(name: String): String = "$dbdir/$name"

    fun getSettingPath(): String = "$settpath/ssyncbrowser.properties"

    fun getLock(): Boolean = lockFile.createNewFile()

    fun releaseLock(): Boolean = lockFile.delete()

}


///////////////////////// settings

class Sync(val type: StringProperty, val title: StringProperty, val status: StringProperty, val localfolder: StringProperty,
           val cacheid: StringProperty = SimpleStringProperty(java.util.Date().time.toString()), val server: Server,
           val subsets: ObservableList<SubSet> = FXCollections.observableArrayList<SubSet>() ) {
    override fun toString() = "[Sync] ${title.value}"
}

class Protocol(val protocoluri: StringProperty, val doSetPermissions: BooleanProperty, val perms: StringProperty,
               val cantSetDate: BooleanProperty, val baseFolder: StringProperty, val password: StringProperty,
               val tunnelHost: StringProperty) {
    fun getmyuri() = MyURI(protocoluri.value)
    override fun toString(): String = "[Protocol ${protocoluri.value}]"
}

class SubSet(val title: StringProperty, val status: StringProperty, val excludeFilter: StringProperty,
             val subfolders: ObservableList<String> = FXCollections.observableArrayList<String>(),// TODO rename to subfolders
             val sync: Sync,
             val type: StringProperty = SimpleStringProperty("subset")) {
    override fun toString() = "[SubSet] ${title.value}"
}

class Server(val type: StringProperty, val title: StringProperty, val status: StringProperty, val proto: Protocol,
             val syncs: ObservableList<Sync>) {
    private var connection: GeneralConnection? = null
    override fun toString() = "[Server] ${title.value}"
    fun getConnection(): GeneralConnection {
        if (connection?.isAlive() != true) {
            logger.info("opening new connection to $proto")
            connection = when {
                proto.protocoluri.value.startsWith("sftp") -> SftpConnection(proto)
                proto.protocoluri.value.startsWith("file") -> LocalConnection(proto)
                else -> throw java.lang.UnsupportedOperationException("asdf")
            }
        }
        return connection!!
    }
}

object SettingsStore {
    val servers = FXCollections.observableArrayList<Server>()!!

    fun saveSettings() {
        val props = Properties()
        props["settingsversion"] = "1"
        props["servers"] = servers.size.toString()
        servers.forEachIndexed { idx, server ->
            props["se.$idx.type"] = server.type.value
            props["se.$idx.title"] = server.title.value
            props["se.$idx.protocoluri"] = server.proto.protocoluri.value
            props["se.$idx.cantSetDate"] = server.proto.cantSetDate.value.toString()
            props["se.$idx.doSetPermissions"] = server.proto.doSetPermissions.value.toString()
            props["se.$idx.perms"] = server.proto.perms.value
            props["se.$idx.protoBaseFolder"] = server.proto.baseFolder.value
            props["se.$idx.protoPassword"] = server.proto.password.value
            props["se.$idx.tunnelHost"] = server.proto.tunnelHost.value
            props["se.$idx.childs"] = server.syncs.size.toString()
            server.syncs.forEachIndexed { idx2, sync ->
                if (sync is Sync) {
                    props["sy.$idx.$idx2.type"] = sync.type.value
                    props["sy.$idx.$idx2.title"] = sync.title.value
                    props["sy.$idx.$idx2.cacheid"] = sync.cacheid.value
                    props["sy.$idx.$idx2.localfolder"] = sync.localfolder.value
                    props["sy.$idx.$idx2.subsets"] = sync.subsets.size.toString()
                    sync.subsets.forEachIndexed { iss, subSet ->
                        props["ss.$idx.$idx2.$iss.title"] = subSet.title.value
                        props["ss.$idx.$idx2.$iss.excludeFilter"] = subSet.excludeFilter.value
                        props["ss.$idx.$idx2.$iss.status"] = subSet.status.value
                        props["ss.$idx.$idx2.$iss.subfolders"] = subSet.subfolders.size.toString()
                        subSet.subfolders.forEachIndexed { irf, s ->
                            props["sssf.$idx.$idx2.$iss.$irf"] = s
                        }
                    }
                }
            }
        }
        val fw = FileWriter(DBSettings.getSettingPath())
        props.store(fw, null)
        logger.info("settings saved!")
    }

    private fun loadSettings() {
        logger.info("load settings ${DBSettings.getSettingPath()}")
        servers.clear()
        val propsx = Properties()
        val fr = FileReader(DBSettings.getSettingPath())
        propsx.load(fr)
        val props = propsx.map { (k, v) -> k.toString() to v.toString() }.toMap()
        if (props["settingsversion"] != "1") throw UnsupportedOperationException("wrong settingsversion!")
        fun p2sp(key: String) = SimpleStringProperty(props.getOrDefault(key, ""))
        fun p2bp(key: String) = SimpleBooleanProperty(props.getOrDefault(key, "0").toBoolean())
        try {
            for (idx in 0 until props.getOrDefault("servers", "0").toInt()) {

                val proto = Protocol(p2sp("se.$idx.protocoluri"), p2bp("se.$idx.doSetPermissions"),
                        p2sp("se.$idx.perms"), p2bp("se.$idx.cantSetDate"), p2sp("se.$idx.protoBaseFolder"),
                        p2sp("se.$idx.protoPassword"), p2sp("se.$idx.tunnelHost"))

                val server = Server(p2sp("se.$idx.type"), p2sp("se.$idx.title"),
                        SimpleStringProperty(""), proto, FXCollections.observableArrayList())

                for (idx2 in 0 until props.getOrDefault("se.$idx.childs", "").toInt()) {
                    val sync = Sync(p2sp("sy.$idx.$idx2.type"), p2sp("sy.$idx.$idx2.title"),
                            SimpleStringProperty(""), p2sp("sy.$idx.$idx2.localfolder"), p2sp("sy.$idx.$idx2.cacheid"), server)
                    for (iss in 0 until props.getOrDefault("sy.$idx.$idx2.subsets", "0").toInt()) {
                        val subSet = SubSet(p2sp("ss.$idx.$idx2.$iss.title"), p2sp("ss.$idx.$idx2.$iss.status"), p2sp("ss.$idx.$idx2.$iss.excludeFilter"), sync=sync)
                        for (irf in 0 until props["ss.$idx.$idx2.$iss.subfolders"]!!.toInt()) subSet.subfolders += props["sssf.$idx.$idx2.$iss.$irf"]!!
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

    init {
        loadSettings()
    }

}




///////////////////////////////////// cache


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
                private var isDir: Boolean,
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
    private var observableList = FXCollections.emptyObservableList<SyncEntry2>()!!

    init {
        observableList.onChange { op ->
            // automatically update treemap from UI changes
            if (!observableListSleep) {
                if (op.wasUpdated()) {
                    observableList.subList(op.from, op.to).forEach { se2 ->
                        logger.debug("changed se2: " + se2.toStringNice())
                        cache[se2.path] = se2.se
                    }
                } else throw NotImplementedError("obslist wants to do something else! ")
            }
        }
    }

    fun dumpAll() {
        cache.iterate { _, path, se -> logger.debug("$path: $se") }
    }

    private fun getCacheFilename(name: String) = "" + DBSettings.dbpath(name) + "-cache.txt"

    private fun iniCache() {
        cache = MyTreeMap()
        observableListSleep = true
        observableList.clear()
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

        val fff = Paths.get(getCacheFilename(cacheid))
        if (!Files.exists(fff)) {
            logger.info("create cache file!")
            if (!Files.exists(fff.parent)) Files.createDirectories(fff.parent)
            Files.createFile(fff)
        }
        val br = Files.newBufferedReader(fff, filecharset)
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
                val vf = SyncEntry(A_UNKNOWN, -1, -1, -1, -1, lcTime, rcTime, size, path.endsWith("/"), false)
                cache[path] = vf
            }
        } else {
            logger.info("Don't load cache, wrong cache version $cacheVersion <> $cacheversion")
        }
        br.close()
        updateObservableBuffer()
        logger.info("cache database loaded!")
    }

    fun saveCache() {
        logger.info("save cache database...$cacheid")
        val fff = java.io.File(getCacheFilename(cacheid)) // forget scalax.io.file: much too slow
        if (fff.exists()) fff.delete()
        val out = java.io.BufferedWriter(java.io.FileWriter(fff), 1000000)
        out.write(cacheversion + "\n")
        for ((path, cf: SyncEntry) in cache) {
            out.write("" + cf.lcTime + "," + cf.rcTime + "," + cf.cSize + "," + path + "\n")
        }
        out.close()
        logger.info("cache database saved!")
    }

    fun clearCacheFile() {
        logger.info("delete cache database $cacheid")
        val fff = Paths.get(getCacheFilename(cacheid))
        if (Files.exists(fff)) {
            Files.delete(fff)
        }
        cache.clear()
        observableList.clear()
    }

    // for listview
    private var filterActions = ALLACTIONS

    fun updateObservableBuffer() {
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
