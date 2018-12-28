@file:Suppress("ConstantConditionIf")

package synchro

import javafx.concurrent.Worker
import mu.KotlinLogging
import store.*
import synchro.Actions.A_CACHEONLY
import synchro.Actions.A_ISEQUAL
import synchro.Actions.A_MERGE
import synchro.Actions.A_RMBOTH
import synchro.Actions.A_RMLOCAL
import synchro.Actions.A_RMREMOTE
import synchro.Actions.A_SKIP
import synchro.Actions.A_SYNCERROR
import synchro.Actions.A_UNCHECKED
import synchro.Actions.A_UNKNOWN
import synchro.Actions.A_USELOCAL
import synchro.Actions.A_USEREMOTE
import util.*
import util.Helpers.dialogOkCancel
import util.Helpers.runUIwait

private val logger = KotlinLogging.logger {}

// subfolder can be single-file entry (for sync single file)
class Profile(private val server: Server, private val sync: Sync, private val subfolder: SubSet) {

    var cache = Cache(sync.cacheid.value)
    var local: GeneralConnection? = null
    var remote: GeneralConnection? = null
    private val uiUpdateInterval = 0.5
    var profileInitialized = false

    fun taskIni() = MyTask<Unit> {
        updateTit("Initialize connections...")
        cache.loadCache()

        val localproto = Protocol(server, SSP("file:///"), SBP(false),
                SSP(""), SBP(false),
                SSP(sync.localfolder.value), SSP(""), SSP(""), SSP(SettingsStore.tunnelModes[0]))
        local = LocalConnection(localproto)
        local!!.assignRemoteBasePath("")

        val uri = MyURI(server.getProtocol().protocoluri.value)
        logger.debug("puri = ${server.getProtocol().protocoluri.value}  proto = ${uri.protocol}")
        updateProgr(50, 100, "initialize remote connection...")

        remote = server.getConnection(sync.remoteFolder.value)

        if (Helpers.failat == 1) throw UnsupportedOperationException("fail 1")
        profileInitialized = true
        updateProgr(100, 100, "done!")
    }


    fun taskCompFiles(isSingleFileSync: Boolean = false) = MyTask<Boolean> {
        updateTit("CompareFiles...")
        val sw = StopWatch() // for timing meas

        // reset table
        updateProgr(0, 100, "resetting database...")

        var cacheall = false
        for (sf in subfolder.subfolders) if (sf == "") cacheall = true
        // remove cache orphans (happens if user doesn't click synchronize
        cache.cache.iterate { iter, _, se -> if (se.cSize == -1L) iter.remove() }
        // ini files
        cache.cache.iterate { _, path, se ->
            var addit = cacheall
            if (!cacheall) for (sf in subfolder.subfolders) if (path.startsWith(sf)) addit = true
            if (addit) {
                se.action = A_UNCHECKED; se.lSize = -1; se.lTime = -1; se.rSize = -1; se.rTime = -1; se.relevant = true
            } else {
                se.relevant = false
            }
        }
        logger.debug("sw: resetting table: " + sw.getTimeRestart())

        updateProgr(50, 100, "Find files local and remote...")
        val swUIupdate = StopWatch()

        fun acLocRem(vf: VirtualFile, isloc: Boolean, updact: (VirtualFile) -> Unit) {
            logger.debug("found loc=$isloc : $vf")
            if (swUIupdate.doit(uiUpdateInterval)) updact(vf)

            cache.cache.merge(vf.path,
                    SyncEntry(A_UNCHECKED, if (isloc) vf.modTime else 0, if (isloc) vf.size else -1,
                            if (!isloc) vf.modTime else 0, if (!isloc) vf.size else -1,
                            0, 0, -1, vf.isDir(), true)
            ) { ov, _ ->
                if (isloc) {
                    ov.lTime = vf.modTime
                    ov.lSize = vf.size
                } else {
                    ov.rTime = vf.modTime
                    ov.rSize = vf.size
                }
                ov
            }
        }

        val taskListLocal = MyTask<Unit> {
            updateTit("Find local file")
            subfolder.subfolders.forEach {
                local!!.list(it, subfolder.excludeFilter.valueSafe, true, false) { vf -> acLocRem(vf, true) { vf2 -> updateMsg("found ${vf2.path}") } }
            }
        }
        val taskListRemote = MyTask<Unit> {
            updateTit("Find remote file")
            subfolder.subfolders.forEach {
                remote!!.list(it, subfolder.excludeFilter.valueSafe, true, false) { vf -> acLocRem(vf, false) { vf2 -> updateMsg("found ${vf2.path}") } }
            }
        }

        taskListLocal.setOnCancelled { logger.debug(" local cancelled!") }
        taskListRemote.setOnCancelled { logger.debug(" rem cancelled!") }
        taskListLocal.setOnSucceeded { logger.debug(" local succ!") }
        taskListRemote.setOnSucceeded { logger.debug(" rem succ!") }
        taskListLocal.setOnFailed { error(" local failed!") }
        taskListRemote.setOnFailed { logger.debug(" rem failed!") }

        tornadofx.runLater { MyWorker.runTask(taskListLocal) }
        tornadofx.runLater { MyWorker.runTask(taskListRemote) }

        while (!(taskListLocal.isDone && taskListRemote.isDone)) { // ignore exceptions / errors!
            Thread.sleep(100)
        }

        logger.debug("sw: finding files: " + sw.getTimeRestart())
        // cache.dumpAll()

        val res = runUIwait {
            logger.debug("state after list: " + taskListLocal.state + "  remote:" + taskListRemote.state)
            when {
                taskListLocal.state == Worker.State.FAILED -> taskListLocal.exception
                taskListRemote.state == Worker.State.FAILED -> taskListRemote.exception
                taskListLocal.state == Worker.State.CANCELLED -> InterruptedException("Cancelled local task")
                taskListRemote.state == Worker.State.CANCELLED -> InterruptedException("Cancelled remote task")
                else -> null
            }
        }
        if (res != null) throw res

        // compare entries
        updateProgr(76, 100, "comparing...")
        sw.restart()
        logger.info("*********************** compare sync entrie")
        val haveChanges = Comparison.compareSyncEntries(cache, isSingleFileSync)
        logger.debug("havechanges1: $haveChanges")

        logger.debug("sw: comparing: " + sw.getTimeRestart())
        updateProgr(100, 100, "done")
        haveChanges
    }

    fun taskSynchronize() = MyTask<Unit> {
        logger.info("*********************** synchronize")
        updateTit("Synchronize")
        updateProgr(0, 100, "startup...")

        var syncLog = ""
        val swUIupdate = StopWatch()
        var totalTransferSize = 0.0
        var transferredSize = 0.0
        cache.cache.iterate { _, _, se ->
            if (se.relevant) {
                when (se.action) {
                    A_USELOCAL -> totalTransferSize += se.lSize
                    A_USEREMOTE -> totalTransferSize += se.rSize
                    else -> {}
                }
            }
        }
        var ignoreErrors = false

        fun dosync(path: String, se: SyncEntry) {
            var showit = false
            val relevantSize = if (se.action == A_USELOCAL) se.lSize else if (se.action == A_USEREMOTE) se.rSize else 0
            if (relevantSize > 10000) showit = true

            val msg: String
            remote!!.onProgress = { progressVal, bytesPerSecond ->
                val pv = (100 * progressVal).toInt()
                updateMsg(" [${CF.amap[se.action]}]: $path...$pv% (${Helpers.tokMGTPE(bytesPerSecond)}B/s)")
            }

            if (showit || swUIupdate.doit(uiUpdateInterval)) {
                msg = " [${CF.amap[se.action]}]: $path..."
                updateProgr(transferredSize.toInt(), totalTransferSize.toInt(), msg)
            }

            try {
                if (Helpers.failat == 5) throw UnsupportedOperationException("fail 5")
                when (se.action) {
                    A_MERGE -> throw UnsupportedOperationException("Merge not implemented yet!")
                    A_RMLOCAL -> {
                        local!!.deletefile(path); se.delete = true; se.relevant = false
                    }
                    A_RMREMOTE -> {
                        remote!!.deletefile(path); se.delete = true; se.relevant = false
                    }
                    A_RMBOTH -> {
                        local!!.deletefile(path); remote!!.deletefile(path); se.delete = true; se.relevant = false
                    }
                    A_USELOCAL -> {
                        val nrt = remote!!.putfile(sync.localfolder.value, path, se.lTime)
                        se.rTime = nrt; se.rSize = se.lSize; se.cSize = se.lSize; se.lcTime = se.lTime; se.rcTime = nrt; se.relevant = false
                        transferredSize += se.lSize
                    }
                    A_USEREMOTE -> {
                        remote!!.getfile(sync.localfolder.value, path, se.rTime)
                        se.lTime = se.rTime; se.lSize = se.rSize; se.cSize = se.rSize; se.rcTime = se.rTime; se.lcTime = se.rTime; se.relevant = false
                        transferredSize += se.rSize
                    }
                    A_ISEQUAL -> {
                        se.cSize = se.rSize; se.lcTime = se.lTime; se.rcTime = se.rTime; se.relevant = false
                    }
                    A_SKIP -> {
                    }
                    A_CACHEONLY -> se.delete = true
                    else -> throw UnsupportedOperationException("unknown action: " + se.action)
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                logger.error("sync exception:", e)
                se.action = A_SYNCERROR
                se.delete = false
                syncLog += (e.message + "[" + path + "]" + "\n")
                updateMsg("Failed: $path: $e")
                if (!ignoreErrors) {
                    if (runUIwait {
                                dialogOkCancel("Error", "Synchronization Error. Press OK to continue ignoring errors, Cancel to abort.",
                                        "File: $path:\n${e.message}")
                            })
                        ignoreErrors = true
                    else
                        throw Exception("Exception(s) during synchronize:\n$syncLog")
                }
                // many exceptions are very slow, problem is stacktrace: http://stackoverflow.com/a/569118. Impossible to disable ST via Runtime.getRuntime()
                Thread.sleep(600) // to keep sfsync responsive...
            }
        }

        for (state in listOf(1, 2)) {
            // delete and add dirs must be done in reverse order!
            logger.debug("syncing state = $state")
            when (state) {
                1 -> // delete
                    cache.cache.iterate(reversed = true) { _, path, se ->
                        if (local!!.interrupted.get() || remote!!.interrupted.get()) throw InterruptedException("profile: connections interrupted")
                        if (se.relevant && listOf(A_RMBOTH, A_RMLOCAL, A_RMREMOTE).contains(se.action)) {
                            dosync(path, se)
                        }
                    }
                else -> // put/get and others
                    cache.cache.iterate { _, path, se ->
                        if (local!!.interrupted.get() || remote!!.interrupted.get()) throw InterruptedException("profile: connections interrupted")
                        if (se.relevant) dosync(path, se)
                    }
            }
            // update cache: remove removed/cacheonly files
            cache.cache.iterate { it, _, se -> if (se.delete) it.remove() }
        }
        if (syncLog != "") throw Exception("Exception(s) during synchronize:\n$syncLog")
    }

    fun iniLocalAsRemote() {
        cache.cache.iterate { _, _, se ->
            if (se.relevant && se.action != A_ISEQUAL) {
                se.action = when (se.action) {
                    A_RMREMOTE -> A_USEREMOTE
                    A_USELOCAL -> if (se.rSize > -1) A_USEREMOTE else A_RMLOCAL
                    A_UNKNOWN -> if (se.rSize > -1) A_USEREMOTE else A_RMLOCAL
                    else -> se.action
                }
            }
        }
    }

    fun iniRemoteAsLocal() {
        cache.cache.iterate { _, _, se ->
            if (se.relevant && se.action != A_ISEQUAL) {
                se.action = when (se.action) {
                    A_RMLOCAL -> A_USELOCAL
                    A_USEREMOTE -> if (se.lSize > -1) A_USELOCAL else A_RMREMOTE
                    A_UNKNOWN -> if (se.lSize > -1) A_USELOCAL else A_RMREMOTE
                    else -> se.action
                }
            }
        }
    }

    // cleanup (transfers must be stopped before, connection is kept alive here.)
    fun taskCleanup() = MyTask<Unit> {
        updateTit("Cleanup profile...")
        updateProgr(1, 100, "Save cache...")
        cache.saveCache()
        updateProgr(50, 100, "Cleanup...")
        local?.cleanUp()
        local = null
        updateProgr(100, 100, "done!")
    }
}

