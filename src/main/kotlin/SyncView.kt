@file:Suppress("unused")

import mu.KotlinLogging
import store.Server
import store.SubSet
import store.Sync
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
import synchro.Profile
import tornadofx.*
import util.Helpers.dialogMessage
import util.Helpers.runUIwait
import util.MyTask
import util.MyWorker
import java.util.*

private val logger = KotlinLogging.logger {}


object CF {
    val amap = mapOf(
            A_MERGE to "M",
    A_ISEQUAL to "==",
    A_RMLOCAL to "<-(rm)",
    A_RMREMOTE to "(rm)to",
    A_UNKNOWN to "?",
    A_USELOCAL to "to",
    A_USEREMOTE to "<-",
    A_CACHEONLY to "C",
    A_RMBOTH to "<-rmto",
    A_UNCHECKED to "???",
    A_SYNCERROR to "SE!",
    A_SKIP to "skip"
    )
    fun stringToAction(actionString: String): Int {
        val x = amap.entries.associate{(k,v)-> v to k}
        return x[actionString]!!
    }
    fun stringToColor(actionString: String): String {
        val cmap = mapOf( // http://docs.oracle.com/javafx/2/api/javafx/scene/doc-files/cssref.html#typecolor
                A_MERGE to "salmon",
        A_ISEQUAL to "white",
        A_RMLOCAL to "salmon",
        A_RMREMOTE to "salmon",
        A_UNKNOWN to "red",
        A_USELOCAL to "lightgreen",
        A_USEREMOTE to "lightgreen",
        A_CACHEONLY to "salmon",
        A_RMBOTH to "salmon",
        A_UNCHECKED to "red",
        A_SYNCERROR to "red",
        A_SKIP to "salmon"
        )
        val a = stringToAction(actionString)
        return cmap[a]!!
    }
}

// TODO add all the logic from FilesView.scala.
class SyncView(server: Server, sync: Sync, subset: SubSet) : View("Sync view") {
    val id = UUID.randomUUID()!!

    private var profile = Profile(server, sync, subset)

    private var syncEnabled = false

    private fun handleFailed(task: MyTask<*>) {
        runUIwait {
            logger.info("Failed!")
            dialogMessage("Error", task.title, task.exception.toString())
            task.exception.printStackTrace()
            profile.cache.updateObservableBuffer()
            btSync.isDisable = true ; btCompare.isDisable = false
        }
    }
    private fun handleCancelled() {
        runUIwait {
            logger.info("Cancelled!")
            profile.cache.updateObservableBuffer()
            btSync.isDisable = true ; btCompare.isDisable = false
        }
    }


    fun runCompare() {
        logger.info("Compare...")
        btSync.isDisable = true
        val ctask = MyTask<Unit> {
            updateTit("A Compare files")
            updateProgr(0, 100, "Initialize local and remote...")
            profile.taskIni.setOnSucceeded {
                updateProgr(50, 100, "Run comparison...")

                profile.taskCompFiles.setOnSucceeded {
                    val haveChanges = profile.taskCompFiles.get()
                    btCompare.isDisable = false
                    runUIwait { profile.cache.updateObservableBuffer() }
                    logger.debug("havechanges=$haveChanges")
                    val canSync = updateSyncButton(allow = true)
                    if (!haveChanges && canSync) {
                        logger.info("Finished compare, no changes found. Synchronizing...")
                        // TODO re-enable if safe! runSynchronize()
                    } else {
                        logger.info("Finished compare")
                        updateSyncButton(allow = true)
                    }
                }
                profile.taskCompFiles.setOnFailed { handleFailed(profile.taskCompFiles) }
                profile.taskCompFiles.setOnCancelled { handleCancelled() }
                MyWorker.runTask(profile.taskCompFiles)
            }
            profile.taskIni.setOnFailed { handleFailed(profile.taskIni) }
            profile.taskIni.setOnCancelled { handleCancelled() }
            runUIwait { MyWorker.runTask(profile.taskIni) }

            updateProgr(100, 100, "ended!")
        }
        MyWorker.runTask(ctask)
    }

    fun runSynchronize() {
        profile.taskSynchronize.setOnSucceeded {
            logger.info("Synchronization finished!")
            MyWorker.runTask(profile.taskCleanup)
        }
        profile.taskSynchronize.setOnFailed { handleFailed(profile.taskSynchronize) }
        profile.taskSynchronize.setOnCancelled {
            profile.local!!.interrupted.set(true)
            profile.remote!!.interrupted.set(true)
            handleCancelled()
        }
        MyWorker.runTask(profile.taskSynchronize)
    }

    private fun updateSyncButton(allow: Boolean): Boolean {
        syncEnabled = allow
        return updateSyncButton()
    }
    // returns true if can synchronize
    private fun updateSyncButton(): Boolean {
        logger.debug("update sync button")
        val canSync = if (syncEnabled) profile.cache.canSync() else false
        //noinspection FieldFromDelayedInit
        btSync.isDisable = !canSync
        return canSync
    }











    private val btSync = button("Sync!") { action {
        logger.info("sync!")
        // TODO
    }}

    private val btCompare = button("Compare!") { action {
        logger.info("compare!")
        // TODO
    }}

    override val root = vbox {
        prefWidth = 800.0
        prefHeight = 600.0
        toolbar {
            this += btCompare
            this += btSync
            button("Close") { action {
                close()
            } }
        }
        label("syncview")
    }
}