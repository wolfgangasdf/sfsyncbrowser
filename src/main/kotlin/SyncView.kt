
import diffmatchpatch.diff_match_patch
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.TableView
import javafx.scene.layout.Priority
import javafx.scene.paint.Color
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
import util.Helpers.dformat
import util.Helpers.dialogMessage
import util.Helpers.revealFile
import util.Helpers.runUIwait
import util.Helpers.showNotification
import util.MFile
import util.MyTask
import util.MyWorker
import java.util.*

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
        return x.getValue(actionString)
    }
    fun stringToColor(actionString: String): Color {
        val cmap = mapOf(
                A_MERGE to Color.SALMON,
                A_ISEQUAL to Color.WHITE,
                A_RMLOCAL to Color.SALMON,
                A_RMREMOTE to Color.SALMON,
                A_UNKNOWN to Color.RED,
                A_USELOCAL to Color.LIGHTGREEN,
                A_USEREMOTE to Color.LIGHTGREEN,
                A_CACHEONLY to Color.SALMON,
                A_RMBOTH to Color.SALMON,
                A_UNCHECKED to Color.RED,
                A_SYNCERROR to Color.RED,
                A_SKIP to Color.SALMON
        )
        val a = stringToAction(actionString)
        return cmap.getValue(a)
    }
}

class SyncView(private val server: Server, private val sync: Sync, private val subset: SubSet) : MyView("Sync view $server $sync $subset") {
    // single-file constructor
    private var useNewFiles = false
    private var runCompareAndSync = false // after shown, run compare and sync (if no conflict)
    private var afterSuccessfulSyncCallback: () -> Unit = {}
    private var profile = Profile(server, sync, subset)
    private var syncEnabled = false

    companion object {
        fun syncViewSingleFile(sync: Sync, successfulSyncCallback: () -> Unit) = SyncView(sync.server, sync, // single file sync view
                SubSet.singlefile(sync)).apply {
            useNewFiles = true
            runCompareAndSync = true
            afterSuccessfulSyncCallback = successfulSyncCallback
        }
        fun syncViewTempIniSync(sync: Sync) = SyncView(sync.server, sync, SubSet.all(sync)).apply {
            useNewFiles = true
            runCompareAndSync = true
        }
    }

    private fun handleFailed(task: MyTask<*>) {
        runUIwait {
            logger.info("Failed!")
            dialogMessage(Alert.AlertType.ERROR, "Error", task.title, task.exception.toString())
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

    private fun runCompare(runSyncIfPossible: Boolean = false) {
        logger.info("runCompare: server=$server sync=$sync subset=$subset")
        btSync.isDisable = true
        val ctask = MyTask<Unit> {
            updateTit("A Compare files")
            updateProgr(0, 100, "Initialize local and remote...")
            val taskIni = profile.taskIni()
            val taskCompFiles = profile.taskCompFiles(useNewFiles)
            taskIni.setOnSucceeded {
                updateProgr(50, 100, "Run comparison...")
                taskCompFiles.setOnSucceeded {
                    val haveChanges = taskCompFiles.get()
                    btCompare.isDisable = false
                    runUIwait { profile.cache.updateObservableBuffer() }
                    syncEnabled = true
                    val canSync = updateSyncButton()
                    logger.info("==> havechanges=$haveChanges canSync=$canSync runSyncIfPossible=$runSyncIfPossible")
                    if (!haveChanges && canSync) {
                        logger.info("Finished compare, no changes found. Synchronizing...")
                        runSynchronize()
                    } else if (runSyncIfPossible && canSync) {
                        logger.info("Finished compare, changes found but can sync & runSyncIfPossible. Synchronizing...")
                        runSynchronize()
                    } else {
                        logger.info("Finished compare")
                        syncEnabled = true
                        updateSyncButton()
                    }
                }
                taskCompFiles.setOnFailed { handleFailed(taskCompFiles) }
                taskCompFiles.setOnCancelled { handleCancelled() }
                MyWorker.runTask(taskCompFiles)
            }
            taskIni.setOnFailed { handleFailed(taskIni) }
            taskIni.setOnCancelled { handleCancelled() }
            runUIwait { MyWorker.runTask(taskIni) }

            updateProgr(100, 100, "ended!")
        }
        MyWorker.runTask(ctask)
    }

    private fun runSynchronize() {
        logger.info("runSynchronize: server=$server sync=$sync subset=$subset")
        val taskSynchronize = profile.taskSynchronize()
        taskSynchronize.setOnSucceeded {
            logger.info("Synchronization finished!")
            showNotification("Ssyncbrowser: successfully synchronized", "Server: ${server.title.value}",
                    if (subset.isSingleFile) sync.title.value else "Sync: ${sync.title.value} Subset: ${subset.title.value}")
            if (subset.isAll || subset.isSingleFile)
                sync.status.set("synchronized ${dformat().format(Date())}")
            else
                subset.status.set("synchronized ${dformat().format(Date())}")
            val taskCleanup = profile.taskCleanup()
            taskCleanup.setOnSucceeded {
                this.close()
                afterSuccessfulSyncCallback()
            }
            MyWorker.runTask(taskCleanup)
        }
        taskSynchronize.setOnFailed { handleFailed(taskSynchronize) }
        taskSynchronize.setOnCancelled {
            profile.local!!.interrupted.set(true)
            profile.remote!!.interrupted.set(true)
            handleCancelled()
        }
        MyWorker.runTask(taskSynchronize)
    }

    private fun updateSyncButton(): Boolean { // returns true if can synchronize
        logger.debug("update sync button $syncEnabled")
        val canSync = if (syncEnabled) profile.cache.canSync() else false
        btSync.isDisable = !canSync
        return canSync
    }

    private val btSync: Button = button("Sync!").apply {
        setOnAction {
            btCompare.isDisable = true
            this.isDisable = true
            runSynchronize()
        }
        isDisable = true
    }

    private val btCompare = button("Compare!") { action {
//        btSync.isDisable = true
//        this.isDisable = true
        runCompare()
    }}

    private val fileTableView = tableview(profile.cache.observableList) {
        isEditable = false
        columnResizePolicy = TableView.UNCONSTRAINED_RESIZE_POLICY //BUG: SmartResize & contentWidth: no horiz scroll shown
        multiSelect(true)

        readonlyColumn("Local", SyncEntry2::se).fixedWidth(200.0).cellFormat {
            text = it.detailsLocal().value
        }
        readonlyColumn("Status", SyncEntry2::se).fixedWidth(50.0).cellFormat {
            text = it.status().value
            style {
                backgroundColor = multi(CF.stringToColor(it.status().value))
                textFill = Color.BLACK
            }
        }
        readonlyColumn("Remote", SyncEntry2::se).fixedWidth(200.0).cellFormat {
            text = it.detailsRemote().value
        }
        readonlyColumn("Path", SyncEntry2::path).fixedWidth(2000.0).cellFormat {
            text = it
            tooltip {
                setOnShowing {
                    text = rowItem.toStringNice()
                    style = "-fx-font-family: \"Courier New\";"
                }
            }
        }

        selectionModel.selectedItems.onChange {
            updateActionButtons()
        }
        vgrow = Priority.ALWAYS
    }

    private val mbAdvanced = menubutton("Advanced...") {
        item("Debug info").setOnAction {
//            profile.cache.dumpAll()
            logger.debug("SE: " + fileTableView.selectedItem)
        }
        item("Make local as remote").setOnAction {
            profile.iniLocalAsRemote()
            profile.cache.updateObservableBuffer()
            updateSyncButton()
        }
        item("Make remote as local").setOnAction {
            profile.iniRemoteAsLocal()
            profile.cache.updateObservableBuffer()
            updateSyncButton()
        }
        item("Reveal local file").setOnAction {
            val se2 = fileTableView.selectedItem
            if (se2 != null) {
                if (se2.se.lSize >= 0) {
                    revealFile(MFile(profile.local!!.remoteBasePath + "/" + se2.path))
                }
            }
        }
    }

    private fun createActionButton(lab: String, action: Int): Button {
        val b = Button(lab)
        b.setOnAction {
            for (idx in fileTableView.selectionModel.selectedItems) {
                idx.se.action = action
                fileTableView.refresh()
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

    private fun updateActionButtons() {
        logger.debug("update action buttons")
        listOf(btRmLocal, btUseLocal, btMerge, btSkip, btRmBoth, btUseRemote, btRmRemote).forEach { it.isDisable = true }
        var allEqual = true
        var allowAction = true
        var legal = true // all have same file exist status
        var existCheck: Pair<Boolean, Boolean>? = null // (alllocalexists, allremoteexists)
        for (se2 in fileTableView.selectionModel.selectedItems) {
            if (existCheck == null)
                existCheck = Pair(se2.se.lSize != -1L, se2.se.rSize != -1L)
            else
            if (existCheck != Pair(se2.se.lSize != -1L, se2.se.rSize != -1L)) legal = false
            if (!se2.se.isEqual()) allEqual = false
            if (se2.se.action == A_UNCHECKED || se2.se.action == A_CACHEONLY) allowAction = false
        }
        if (allowAction) {
            btSkip.isDisable = false
            if (legal) {
                if (allEqual) {
                    if (existCheck == Pair(first = true, second = true)) btRmBoth.isDisable = false
                } else {
                    when (existCheck) {
                        Pair(first = true, second = true) -> listOf(btUseLocal,btUseRemote,btMerge,btRmBoth).forEach { it.isDisable = false }
                        Pair(first = true, second = false) -> listOf(btUseLocal,btRmLocal).forEach { it.isDisable = false }
                        Pair(first = false, second = true) -> listOf(btUseRemote,btRmRemote).forEach { it.isDisable = false }
                    }
                }
            }
        }
    }

    private object Filters {
        const val all="all"
        const val changes="changes"
        const val problems="problems"
        fun getList() = listOf(changes,all,problems)
        fun getFilter(s: String): List<Int> {
            return when(s) {
                all -> ALLACTIONS
                changes -> ALLACTIONS.filter { it != A_ISEQUAL && it != A_UNCHECKED }
                problems -> listOf(A_UNKNOWN, A_UNCHECKED, A_SKIP, A_CACHEONLY, A_SYNCERROR)
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
        profile.cache.filterActions = Filters.getFilter(Filters.changes)
        this.setValue(Filters.changes)
    }

    private val btDiff = Button("Quick diff").apply { setOnAction {
        if (profile.profileInitialized) {
            val se2 = fileTableView.selectedItem
            if (se2 != null) {
                if (se2.se.lSize + se2.se.rSize < 100000) {
                    val lf = MFile.createTempFile("sfsync-localfile", ".tmp")
                    val rf = MFile.createTempFile("sfsync-remotefile", ".tmp")
                    profile.local!!.getfile("", se2.path, se2.se.lTime, lf.toString())
                    profile.remote!!.getfile("", se2.path, se2.se.rTime, rf.toString())
                    val lfc = lf.readFileToString()
                    val rfc = rf.readFileToString()
                    val diff = diff_match_patch().apply {
                        Diff_Timeout = 10.0f
                    }
                    val d = diff.diff_main(lfc, rfc)
                    diff.diff_cleanupSemantic(d)
                    val res = diff.diff_prettyHtml(d)
                    dialogMessage(Alert.AlertType.INFORMATION, "Quick diff", "Changes local -> remote", res)
                }
            }
        }
    } }

    override val root = vbox {
        prefWidth = 1200.0
        prefHeight = 600.0
        toolbar {
            this += btCompare
            this += btSync
            button("Close") { action {
                close()
            } }
        }
        this += fileTableView
        this += hbox {
            children.addAll(listOf(cFilter,btRmLocal, btUseLocal, btMerge,
            btSkip, btRmBoth, btUseRemote, btRmRemote, btDiff, mbAdvanced))
        }
    }

    override fun doAfterShown() {
        runCompare(runCompareAndSync)
    }
}