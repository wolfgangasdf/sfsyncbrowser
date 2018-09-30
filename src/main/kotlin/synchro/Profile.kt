package synchro

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
import synchro.Actions.A_USELOCAL
import synchro.Actions.A_USEREMOTE
import util.Helpers

import util.MyTask
import util.MyWorker
import util.StopWatch

//import javafx.concurrent as jfxc

private val logger = KotlinLogging.logger {}


class TransferProtocol (
  var uri: String,
  var basefolder: String
)

class Profile(server: Server, sync: Sync, subfolder: SubSet) { // TODO rename "subfolder"
  var cache: MutableList[VirtualFile] = null
  var local: GeneralConnection = _
  var remote: GeneralConnection = _
  val UIUpdateInterval = 0.5
  var profileInitialized = false
  val protocol = server.proto // TODO remove?

  class ProfileAbortedException(message: String = "", cause: Throwable = ""): RuntimeException(message, cause)

  val taskIni = MyTask<Unit>() {
    updateTit("Initialize connections...")
    Cache.loadCache(sync.cacheid)

    local = LocalConnection(server.proto, true).apply {
      remoteBasePath = sync.localfolder.value
    }
    val uri = MyURI(server.proto.protocoluri.value)
    logger.debug("puri = ${protocol.protocoluri.value}  proto = ${uri.protocol}")
    updateProgr(50, 100, "initialize remote connection...")

    remote = when(uri.protocol) {
      "sftp" -> SftpConnection(protocol, false, uri)
      "file" -> LocalConnection(protocol, false)
      else -> throw RuntimeException("wrong protocol: " + uri.protocol)
    }
    if (Helpers.failat == 1) throw UnsupportedOperationException("fail 1")
    remote.localBasePath = sync.localfolder.value
    remote.remoteBasePath = protocol.protocolbasefolder.getValueSafe
    profileInitialized = true
    updateProgr(100, 100, "done!")
  }



  val taskCompFiles = MyTask<Unit> {

    updateTit("CompareFiles...")
    val sw = StopWatch() // for timing meas

    // reset table
    updateProgr(0.0, 100.0, "resetting database...")

    var cacheall = false
    for (sf in subfolder.remotefolders) if (sf == "") cacheall = true
    // remove cache orphans (happens if user doesn't click synchronize
    Cache.cache.iterate((it, _, se) -> if (se.cSize == -1) it.remove())
    // ini files
    Cache.cache.iterate((_, path, se) -> {
      var addit = cacheall
      if (!cacheall) for (sf <- subfolder.subfolders) if (path.startsWith("/" + sf + "/")) addit = true
      if (addit) {
        se.action = A_UNCHECKED; se.lSize = -1; se.lTime = -1; se.rSize = -1; se.rTime = -1; se.relevant = true
      } else {
        se.relevant = false
      }
    })
    logger.debug("sw: resetting table: " + sw.getTimeRestart)

    updateProgr(50, 100, "Find files local and remote...")
    val swUIupdate = StopWatch()

    fun acLocRem(vf: VirtualFile, isloc: Boolean, updact: (VirtualFile) -> Unit): Unit = {
      //logger.debug("found loc=$isloc : " + vf)
      if (swUIupdate.doit(UIUpdateInterval)) updact(vf)

      Cache.cache.merge(vf.path,
        SyncEntry(A_UNCHECKED, if (isloc) vf.modTime else 0, if (isloc) vf.size else -1,
                                   if (!isloc) vf.modTime else 0, if (!isloc) vf.size else -1,
          0, 0, -1, vf.path.endsWith("/"), true),
        (ov: SyncEntry, _: SyncEntry) -> {
          if (isloc) {
            ov.lTime = vf.modTime
            ov.lSize = vf.size
          } else {
            ov.rTime = vf.modTime
            ov.rSize = vf.size
          }
          ov
        }
      )
    }
    val taskListLocal = MyTask<Unit> {
      updateTit("Find local file")
      subfolder.remotefolders.foreach(local.list(_, server.filterRegexp.getValueSafe, vf -> acLocRem(vf, isloc = true, vf -> updateMessage("found ${vf.path}")), recursive = true))
    }
    val taskListRemote = MyTask<Unit> {
      updateTit("Find remote file")
      subfolder.subfolders.foreach(remote.list(_, server.filterRegexp.getValueSafe, vf -> acLocRem(vf, isloc = false, vf -> updateMessage("found ${vf.path}")), recursive = true))
    }

    taskListLocal.onCancelled = () -> { logger.debug(" local cancelled!") }
    taskListRemote.onCancelled = () -> { logger.debug(" rem cancelled!") }
    taskListLocal.onSucceeded = () -> { logger.debug(" local succ!") }
    taskListRemote.onSucceeded = () -> { logger.debug(" rem succ!") }
    taskListLocal.onFailed = () -> { error(" local failed!") }
    taskListRemote.onFailed = () -> { logger.debug(" rem failed!") }

    MyWorker.runTask(taskListLocal)
    MyWorker.runTask(taskListRemote)

    while (!(taskListLocal.isDone && taskListRemote.isDone)) { // ignore exceptions / errors!
      Thread.sleep(100)
    }

    logger.debug("sw: finding files: " + sw.getTimeRestart)

    val res = runUIwait {
      logger.debug("state after list: " + taskListLocal.getState + "  remote:" + taskListRemote.getState)
      if (taskListLocal.getState == jfxc.Worker.State.FAILED) taskListLocal.getException
      else if (taskListRemote.getState == jfxc.Worker.State.FAILED) taskListRemote.getException
      else if (taskListLocal.getState == jfxc.Worker.State.CANCELLED) InterruptedException("Cancelled local task")
      else if (taskListRemote.getState == jfxc.Worker.State.CANCELLED) InterruptedException("Cancelled remote task")
      else null
    }
    if (res != null) throw res.asInstanceOf[Exception]

    // compare entries
    updateProgr(76, 100, "comparing...")
    sw.restart()
    logger.info("*********************** compare sync entrie")
    val haveChanges = Comparison.compareSyncEntries()
    logger.debug("havechanges1: " + haveChanges)

    logger.debug("sw: comparing: " + sw.getTimeRestart)
    set(haveChanges)
    updateProgr(100, 100, "done")
  }

  val taskSynchronize = MyTask<Unit> {
    logger.info("*********************** synchronize")
    updateTitle("Synchronize")
    updateProgr(0, 100, "startup...")

    var syncLog = ""
    val swUIupdate = StopWatch
    var totalTransferSize = 0.0
    var transferredSize = 0.0
    Cache.cache.iterate((_, _, se) -> if (se.relevant) {
      se.action match {
        case A_USELOCAL -> totalTransferSize += se.lSize
        case A_USEREMOTE -> totalTransferSize += se.rSize
        case _ ->
      }
    })
    var ignoreErrors = false

    fun dosync(path: String, se: SyncEntry) {
      var showit = false
      val relevantSize = if (se.action == A_USELOCAL) se.lSize else if (se.action == A_USEREMOTE) se.rSize else 0
      if (relevantSize > 10000) showit = true

      var msg = ""
      remote.onProgress = (progressVal: Double, bytesPerSecond: Double) -> {
        val pv = (100 * progressVal).toInt
        updateMessage(" [${CF.amap(se.action)}]: $path...$pv% (${Helpers.tokMGTPE(bytesPerSecond)}B/s)")
      }

      if (showit || swUIupdate.doit(UIUpdateInterval)) {
        msg = " [${CF.amap(se.action)}]: $path..."
        updateProgr(transferredSize, totalTransferSize, msg)
      }

      try {
        if (Helpers.failat == 5) throw UnsupportedOperationException("fail 5")
        when (se.action) {
          A_MERGE -> throw UnsupportedOperationException("Merge not implemented yet!")
          A_RMLOCAL -> { local.deletefile(path, se.lTime); se.delete = true; se.relevant = false }
          A_RMREMOTE -> { remote.deletefile(path, se.rTime); se.delete = true; se.relevant = false }
          A_RMBOTH -> { local.deletefile(path, se.lTime); remote.deletefile(path, se.rTime); se.delete = true; se.relevant = false }
          A_USELOCAL -> { val nrt = remote.putfile(path, se.lTime)
            se.rTime = nrt; se.rSize = se.lSize; se.cSize = se.lSize; se.lcTime = se.lTime; se.rcTime = nrt; se.relevant = false
            transferredSize += se.lSize }
          A_USEREMOTE -> { remote.getfile(path, se.rTime)
            se.lTime = se.rTime; se.lSize = se.rSize; se.cSize = se.rSize; se.rcTime = se.rTime; se.lcTime = se.rTime; se.relevant = false
            transferredSize += se.rSize }
          A_ISEQUAL -> { se.cSize = se.rSize; se.lcTime = se.lTime; se.rcTime = se.rTime; se.relevant = false }
          A_SKIP -> {}
          A_CACHEONLY -> se.delete = true
          else -> throw UnsupportedOperationException("unknown action: " + se.action)
        }
      } catch(e: InterruptedException) {
        throw e
      } catch(e: Exception) {
        // TODO on first sync exception, ask user if to stop, continue, or continue ignoring errors
        logger.error("sync exception:", e)
        se.action = A_SYNCERROR
        se.delete = false
        syncLog += (e + "[" + path + "]" + "\n")
        updateMsg("Failed: " + path + ": " + e)
        if (!ignoreErrors) {
          if (runUIwait(dialogOkCancel("Error", "Synchronization Error. Press OK to continue ignoring errors, Cancel to abort.", "File: $path:\n${e.getMessage}")) == true)
          ignoreErrors = true
          else
          throw Exception("Exception(s) during synchronize:\n" + syncLog)
        }
        // many exceptions are very slow, problem is stacktrace: http://stackoverflow.com/a/569118. Impossible to disable ST via Runtime.getRuntime()
        Thread.sleep(600) // to keep sfsync responsive...
      }


      catch {
        case e: InterruptedException -> throw e
        case e: Exception ->
      }
    }

    for (state <- List(1, 2)) {
      // delete and add dirs must be done in reverse order!
      logger.debug("syncing state = " + state)
      state match {
        case 1 -> // delete
          Cache.cache.iterate((_, path, se) -> {
            if (local.interrupted.get || remote.interrupted.get) throw InterruptedException("profile: connections interrupted")
            if (se.relevant && List(A_RMBOTH, A_RMLOCAL, A_RMREMOTE).contains(se.action)) {
              dosync(path, se)
            }
          }, reversed = true)
        case _ -> // put/get and others
          Cache.cache.iterate((_, path, se) -> {
            if (local.interrupted.get || remote.interrupted.get) throw InterruptedException("profile: connections interrupted")
            if (se.relevant) dosync(path, se)
          })
      }
      // update cache: remove removed/cacheonly files
      Cache.cache.iterate((it, _, se) -> {
        if (se.delete) it.remove()
      })
    }
    if (syncLog != "") throw Exception("Exception(s) during synchronize:\n" + syncLog)
  }

  // init action to make local as remote
  fun iniLocalAsRemote(): Unit = {
    Cache.cache.iterate( (_, _, se) -> {
      if (se.relevant && se.action != A_ISEQUAL) {
        se.action = se.action match {
          case A_RMREMOTE -> A_USEREMOTE
          case A_USELOCAL -> if (se.rSize > -1) A_USEREMOTE else A_RMLOCAL
          case A_UNKNOWN -> if (se.rSize > -1) A_USEREMOTE else A_RMLOCAL
          case x -> x
        }
      }
    })
  }

  // init action to make local as remote
  fun iniRemoteAsLocal(): Unit = {
    Cache.cache.iterate( (_, _, se) -> {
      if (se.relevant && se.action != A_ISEQUAL) {
        se.action = se.action match {
          case A_RMLOCAL -> A_USELOCAL
          case A_USEREMOTE -> if (se.lSize > -1) A_USELOCAL else A_RMREMOTE
          case A_UNKNOWN -> if (se.lSize > -1) A_USELOCAL else A_RMREMOTE
          case x -> x
        }
      }
    })
  }

  // cleanup (transfers must be stopped before)
  val taskCleanup = MyTask<Unit> {
    updateTit("Cleanup profile...")
    updateProgr(1, 100, "Save cache...")
    Cache.saveCache(server.id.getValueSafe)
    updateProgr(50, 100, "Cleanup...")
    if (remote != null) remote.cleanUp()
    if (local != null) local.cleanUp()
    remote = null
    local = null

    updateProgr(100, 100, "done!")
  } }



}

