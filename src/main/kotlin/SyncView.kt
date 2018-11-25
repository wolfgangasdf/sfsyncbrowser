
import javafx.scene.control.Button
import mu.KotlinLogging
import store.Server
import store.SubSet
import store.Sync
import store.SyncEntry2
import synchro.Actions.ALLACTIONS
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

private val logger = KotlinLogging.logger {}


object CF {
    val amap = mapOf(
            A_MERGE to "M",
    A_ISEQUAL to "==",
    A_RMLOCAL to "<-rm",
    A_RMREMOTE to "rm->",
    A_UNKNOWN to "?",
    A_USELOCAL to "->",
    A_USEREMOTE to "<-",
    A_CACHEONLY to "C",
    A_RMBOTH to "<-rm->",
    A_UNCHECKED to "???",
    A_SYNCERROR to "SE!",
    A_SKIP to "skip"
    )
    private fun stringToAction(actionString: String): Int {
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

class SyncView(server: Server, sync: Sync, subset: SubSet) : View("Sync view") {
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


    private val fileTableView = tableview(profile.cache.observableList) {
        isEditable = false
        readonlyColumn("Local", SyncEntry2::se).cellFormat {
            text = it.detailsLocal().value
        }
        readonlyColumn("Status", SyncEntry2::se).cellFormat {
            text = it.status().value
            // TODO style =
        }
        readonlyColumn("Remote", SyncEntry2::se).cellFormat {
            text = it.detailsRemote().value
        }
        readonlyColumn("Path", SyncEntry2::path).remainingWidth() // TODO tooltip
    }

    private object AdvActions {
        const val debug = "Debug info"
        const val asRemote = "Make local as remote"
        const val asLocal = "Make remote as local"
        const val revealLocal = "Reveal local file"
        fun getList() = listOf(debug, asRemote, asLocal, revealLocal)
    }
    private val cbAdvanced = combobox(null, AdvActions.getList()) {
        promptText = "Advanced..."
        setOnAction {
            when (this.value) {
                AdvActions.debug -> logger.debug("SE: " + fileTableView.selectedItem)
                AdvActions.asRemote -> {
                    profile.iniLocalAsRemote()
                    profile.cache.updateObservableBuffer()
                    updateSyncButton()
                }
                AdvActions.asLocal -> {
                    profile.iniRemoteAsLocal()
                    profile.cache.updateObservableBuffer()
                    updateSyncButton()
                }
                AdvActions.revealLocal -> {
                    val se2 = fileTableView.selectedItem
                    if (se2 != null) {
                        if (se2.se.lSize >= 0) {
                            // TODO (don't expose rbp but construct is here again!???
                            // revealFile(Paths.get(profile.local!!.remoteBasePath + "/" + se2.path).toFile)
                        }
                    }
                }
            }
            println("act: " + it.toString())
        }
    }

    private fun createActionButton(lab: String, action: Int): Button {
        val b = Button(lab)
        b.setOnAction {
            for (idx in fileTableView.selectionModel.selectedItems) {
                idx.se.action = action
                fileTableView.refresh() // TODO needed?
            }
            // advance
            fileTableView.selectionModel.clearAndSelect(fileTableView.selectionModel.selectedIndices.max()?:0 + 1)
            updateSyncButton()
        }
        return b
    }

    private val btUseLocal = createActionButton("Use local", A_USELOCAL)
    private val btUseRemote = createActionButton("Use remote", A_USEREMOTE)
    private val btRmLocal = createActionButton("Delete local", A_RMLOCAL)
    private val btRmRemote = createActionButton("Delete remote", A_RMREMOTE)
    private val btMerge = createActionButton("Merge", A_MERGE)
    private val btSkip = createActionButton("Skip", A_SKIP)
    private val btRmBoth = createActionButton("Delete both", A_RMBOTH)

    fun updateActionButtons() {
        // TODO
//        debug("update action buttons")
//        List(btRmLocal, btUseLocal, btMerge, btSkip, btRmBoth, btUseRemote, btRmRemote).foreach(bb => bb.setDisable(true))
//        var allEqual = true
//        var allowAction = true
//        var legal = true // all have same file exist status
//        var existCheck: (Boolean, Boolean) = null // (alllocalexists, allremoteexists)
//        for (se2 <- tv.selectionModel().getSelectedItems) {
//            if (existCheck == null)
//                existCheck = (se2.se.lSize != -1, se2.se.rSize != -1)
//            else
//            if (existCheck != (se2.se.lSize != -1, se2.se.rSize != -1)) legal = false
//            if (!se2.se.isEqual) allEqual = false
//            if (se2.se.action == A_UNCHECKED || se2.se.action == A_CACHEONLY) allowAction = false
//        }
//        if (allowAction) {
//            btSkip.setDisable(false)
//            if (legal) {
//                if (allEqual) {
//                    if (existCheck == (true,true)) btRmBoth.setDisable(false)
//                } else {
//                    if (existCheck == (true,true)) List(btUseLocal,btUseRemote,btMerge,btRmBoth).foreach(bb=>bb.setDisable(false))
//                    else if (existCheck == (true,false)) List(btUseLocal,btRmLocal).foreach(bb => bb.setDisable(false))
//                    else if (existCheck == (false,true)) List(btUseRemote,btRmRemote).foreach(bb => bb.setDisable(false))
//                }
//            }
//        }
    }

    private object Filters {
        const val all="all"
        const val changes="changes"
        const val problems="problems"
        fun getList() = listOf(changes,all,problems)
        fun getFilter(s: String): List<Int> {
            return when(s) {
                Filters.all -> ALLACTIONS
                Filters.changes -> ALLACTIONS.filter { it != A_ISEQUAL && it != A_UNCHECKED }
                Filters.problems -> listOf(A_UNKNOWN, A_UNCHECKED, A_SKIP, A_CACHEONLY, A_SYNCERROR)
                else -> ALLACTIONS
            }
        }
    }
    private val cFilter = combobox(null, Filters.getList()) {
        setOnAction {
            profile.cache.filterActions = Filters.getFilter(this.value)
            logger.debug("setting filter to " + profile.cache.filterActions.joinToString(","))
            profile.cache.updateObservableBuffer()
        }
        selectionModel.select(Filters.all)
        profile.cache.filterActions = Filters.getFilter(this.value) // TODO dupli code, solve.
    }

    private val btDiff = Button("Quick diff").apply {
        // TODO
//        onAction = (_: ActionEvent) => {
//        if (profile != null) if (profile.profileInitialized) {
//            val se2 = tv.selectionModel().getSelectedItem
//            if (se2 != null) {
//                if (se2.se.lSize + se2.se.rSize < 100000) {
//                    val lf = Files.createTempFile("sfsync-localfile", ".tmp")
//                    val rf = Files.createTempFile("sfsync-remotefile", ".tmp")
//                    profile.local.getfile(se2.path, se2.se.lTime, lf.toString)
//                    profile.remote.getfile(se2.path, se2.se.rTime, rf.toString)
//                    val lfc = readFileToString(lf)
//                    val rfc = readFileToString(rf)
//                    val diff = new diff_match_patch {
//                        Diff_Timeout = 10
//                    }
//                    // debug("lfc:\n" + lfc)
//                    // debug("rfc:\n" + rfc)
//                    val (d, msg) = if (se2.se.action == A_USELOCAL)
//                        (diff.diff_main(rfc, lfc), "Changes remote -> local:")
//                    else (diff.diff_main(lfc, rfc), "Changes local -> remote:")
//                    diff.diff_cleanupSemantic(d)
//                    val res = diff.diff_prettyHtml(d)
//                    dialogMessage(AlertType.Information, "Quick diff", msg, res)
//                }
//            }
//        }
//        debug("SE: " + tv.selectionModel().getSelectedItem)
//    }
    }


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
        this += fileTableView
        fileTableView.smartResize()
        this += hbox {
            children.addAll(listOf(cFilter,btRmLocal, btUseLocal, btMerge,
            btSkip, btRmBoth, btUseRemote, btRmRemote, btDiff, cbAdvanced))
        }
    }

    init {

    }
}