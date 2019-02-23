import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.TableColumn
import javafx.scene.control.TableRow
import javafx.scene.input.KeyCode
import javafx.scene.input.TransferMode
import javafx.scene.layout.Priority
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.util.Callback
import mu.KotlinLogging
import store.*
import synchro.VirtualFile
import tornadofx.*
import util.*
import util.Helpers.dialogInputString
import util.Helpers.dialogMessage
import util.Helpers.dialogOkCancel
import util.Helpers.editFile
import util.Helpers.getFileIntoTempAndDo
import util.Helpers.openFile
import util.Helpers.tokMGTPE
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

private val logger = KotlinLogging.logger {}

enum class BrowserViewMode {
    NORMAL, SELECTFOLDER, SELECTFOLDERS
}

class DragView(temppath: File) : View("Drag...") {
    private val btDnd = button("The file(s) have been downloaded to ${temppath.path}.\nDrag this to the destination!").apply {
        setOnDragDetected { me ->
            val dragBoard = startDragAndDrop(TransferMode.MOVE) // so tempfiles are removed by OS
            dragBoard.setContent { putFiles(temppath.listFiles().toList()) }
            me.consume()
        }
        setOnDragDone { de ->
            de.consume()
            this@DragView.close()
        }
    }
    override val root = btDnd
}

class BrowserView(private val server: Server, private val basePath: String, path: String, private val mode: BrowserViewMode = BrowserViewMode.NORMAL) :
        MyView("${server.getProtocol().protocoluri.value}:${server.getProtocol().baseFolder.value}$basePath$path") {

    private var currentPath = SSP(path).apply {
        onChange { if (it != null) updateBrowser() }
    }

    private val files = mutableListOf<VirtualFile>().observable()

    var selectFolderCallback: (f: VirtualFile) -> Unit = {}
    var selectFoldersCallback: (fl: List<VirtualFile>) -> Unit = {}
    private fun isNormal() = mode == BrowserViewMode.NORMAL

    private val pathButtonFlowPane = flowpane {
        alignment = Pos.CENTER_LEFT
        paddingAll = 5.0
        hgap = 5.0
        vgap = 5.0
    }

    // mac quicklook lets "space" to close through... this is good in principle, to allow navigation while preview open. implement?
    private var lastpreviewvf: VirtualFile? = null

    inner class InfoView(vf: VirtualFile): MyView() {
        override val root = Form()
        private val permbps = PosixFilePermission.values().map {
            it to SBP(vf.permissions.contains(it)).apply {
                onChange { op -> if (op) vf.permissions.add(it) else vf.permissions.remove(it) }
            }
        }.toMap()
        init {
            with(root) {
                fieldset("Permissions") {
                    field("User") {
                        checkbox("read", permbps[PosixFilePermission.OWNER_READ])
                        checkbox("write", permbps[PosixFilePermission.OWNER_WRITE])
                        checkbox("execute", permbps[PosixFilePermission.OWNER_EXECUTE])
                    }
                    field("Group") {
                        checkbox("read", permbps[PosixFilePermission.GROUP_READ])
                        checkbox("write", permbps[PosixFilePermission.GROUP_WRITE])
                        checkbox("execute", permbps[PosixFilePermission.GROUP_EXECUTE])
                    }
                    field("Others") {
                        checkbox("read", permbps[PosixFilePermission.OTHERS_READ])
                        checkbox("write", permbps[PosixFilePermission.OTHERS_WRITE])
                        checkbox("execute", permbps[PosixFilePermission.OTHERS_EXECUTE])
                    }
                    field("") {
                        button("Apply permissions").setOnAction {
                            MyWorker.runTaskWithConn({ updateBrowser() }, "Chmod", server, basePath) { c -> c.extChmod(vf.path, vf.permissions) }
                        }
                    }

                }
            }
        }
    }

    enum class SfsOp { NONE, OPEN, EDIT }
    private val fileTableView = tableview(files) {
        val colo = SettingsStore.ssbSettings.browsercols.value.let { if (it == "") "1:true;2:true;3:true;4:true" else it }.
                split(";").map { cs -> cs.split(":").let { Pair(it[0].toInt(), it[1].toBoolean()) } }
        colo.forEach { col ->
            when (col.first) {
                1 -> column("Name", VirtualFile::getFileNameBrowser).remainingWidth().apply { sortType = TableColumn.SortType.ASCENDING }
                2 -> column("Size", VirtualFile::size).apply { cellFormat { text = tokMGTPE(it) } }
                3 -> column("Perms", VirtualFile::getPermString)
                4 -> column("Modtime", VirtualFile::modTime).apply { cellFormat { text = Helpers.dformat().format(it) } }
                else -> { error("Unknown column number ${col.first}") ; null }
            }?.let {
                it.userData = col.first
                if (!col.second && col.first != 1) {
                    it.isVisible = false
                    it.maxWidth = 0.0 // bug
                }
            }
        }
        vgrow = Priority.ALWAYS
        columnResizePolicy = SmartResize.POLICY
    }.apply {
        columns.find { it.userData == 1 }?.let { sortOrder.add(it) }
        isTableMenuButtonVisible = true
        multiSelect(true)
        rowFactory = Callback {
            val row = TableRow<VirtualFile>()
            row.setOnMouseClicked { it2 ->
                if (it2.clickCount == 2 && row.item.isDir()) {
                    currentPath.set(row.item.path)
                }
            }
            row
        }
        lazyContextmenu {
            item("Refresh").action { updateBrowser() }
            item("Add bookmark") { isDisable = !isNormal() || selectedItem?.isDir() != true }.action {
                server.bookmarks += BrowserBookmark(server, SSP(selectedItem?.path))
            }
            fun addFilesync(op: SfsOp) {
                val newSync = Sync(SyncType.FILE, SSP(selectedItem?.getFileName()), SSP("not synced"),
                        SSP(""), SSP(selectedItem?.getParent()), SSP(""), server = server).apply {
                    localfolder.set(DBSettings.getCacheFolder(cacheid.value))
                    auto.set(true)
                }
                server.syncs += newSync
                MainView.compSyncFile(newSync) {
                    when (op) {
                        SfsOp.NONE -> {}
                        SfsOp.OPEN -> openFile("${newSync.localfolder.value}/${newSync.title.value}")
                        SfsOp.EDIT -> if (SettingsStore.ssbSettings.editor.value != "")
                            editFile("${newSync.localfolder.value}/${newSync.title.value}")
                        else
                            dialogMessage(Alert.AlertType.ERROR, "Edit file", "Set editor in settings first!", "")
                    }
                }
            }
            item("Add temporary syncfile") { isDisable = !isNormal() || selectedItem?.isFile() != true }.action {
                addFilesync(SfsOp.NONE)
            }
            item("Add temporary syncfile and open") { isDisable = !isNormal() || selectedItem?.isFile() != true }.action {
                addFilesync(SfsOp.OPEN)
            }
            item("Add temporary syncfile and edit") { isDisable = !isNormal() || selectedItem?.isFile() != true }.action {
                addFilesync(SfsOp.EDIT)
            }
            item("Add sync...") { isDisable = !isNormal() || selectedItem?.isDir() != true }.action {
                val sname = dialogInputString("New sync", "Enter sync name:", "")
                var lfolder = "sylocalfolder"
                chooseDirectory("Select local folder")?.let {
                    lfolder = if (it.absolutePath.endsWith("/")) it.absolutePath else it.absolutePath + "/"
                }
                server.syncs += Sync(SyncType.NORMAL, SSP(sname?:"syname"),
                        SSP(""), SSP(lfolder),
                        SSP(selectedItem!!.path), SSP(""), server=server)
            }
            item("Add temporary sync...") { isDisable = !isNormal() || selectedItem?.isDir() != true }.action {
                val sname = dialogInputString("New temporary sync", "Enter sync name:", "")
                server.syncs += Sync(SyncType.CACHED, SSP(sname?:"syname"),
                        SSP(""), SSP(""),
                        SSP(selectedItem!!.path), SSP(""), server=server).apply {
                    localfolder.set(DBSettings.getCacheFolder(cacheid.value))
                    auto.set(false)
                }
            }
            separator()
            item("Rename...") { isDisable = !isNormal() || selectedItem == null || !canRename }.action {
                dialogInputString("Rename...", "Enter new name:", "", selectedItem!!.getFileName())?.let {
                    MyWorker.runTaskWithConn({ updateBrowser() }, "Rename", server, basePath) { c -> c.extRename(selectedItem!!.path, selectedItem!!.getParent() + it) }
                }
            }
            item("Info...") { isDisable = !isNormal() || selectedItem == null }.action {
                openNewWindow(InfoView(selectedItem!!), Modality.APPLICATION_MODAL)
            }
            item("Copy URL") { isDisable = !isNormal() || selectedItem == null }.action {
                clipboard.putString("${server.getProtocol().protocoluri.value}:${server.getProtocol().baseFolder.value}${selectedItem!!.path}")
            }
            separator()
            item("Duplicate...") { isDisable = !isNormal() || selectedItem == null || !canDuplicate }.action {
                dialogInputString("Duplicate...", "Enter new name:", "", selectedItem!!.getFileName())?.let {
                    MyWorker.runTaskWithConn({ updateBrowser() }, "Duplicate", server, basePath) { c -> c.extDuplicate(selectedItem!!.path, selectedItem!!.getParent() + it) }
                }
            }
            item("New folder...") { isDisable = !listOf(BrowserViewMode.NORMAL, BrowserViewMode.SELECTFOLDER).contains(mode) }.action {
                dialogInputString("Create new folder", "Enter folder name:", "", "")?.let {
                    MyWorker.runTaskWithConn({ updateBrowser() }, "Mkdir", server, basePath) { c -> c.mkdirrec(currentPath.value + it, true) }
                }
            }
            item("New file...") { isDisable = !isNormal() }.action {
                dialogInputString("Create new file", "Enter file name:", "", "")?.let {
                    val tempfolder = Files.createTempDirectory("ssyncbrowsertemp").toFile()
                    val f = File("${tempfolder.path}/$it")
                    if (!f.createNewFile()) throw Exception("Error creating file ${f.path}")
                    MyWorker.runTaskWithConn({ updateBrowser() }, "New file", server, basePath) { c -> c.putfile("", f.path, f.lastModified(), "${currentPath.value}${f.name}") }
                }
            }
            item("Delete") { isDisable = !isNormal() }.action {
                if (dialogOkCancel("Delete files", "Really delete these files?", selectionModel.selectedItems.joinToString { "${it.path}\n" })) {
                    MyWorker.runTaskWithConn({ updateBrowser() }, "Delete", server, "") { c ->
                        selectionModel.selectedItems.forEach { c.deletefile(it.path) }
                    }
                }
            }
        }
        setOnKeyReleased { ke -> when(ke.code) { // quicklook
             KeyCode.SPACE -> {
                 if (selectedItem?.isFile() == true && selectedItem != lastpreviewvf) {
                     lastpreviewvf = selectedItem
                     getFileIntoTempAndDo(server, selectedItem!!) { Helpers.previewDocument(it) }
                 }
             }
            else -> {}
        }}
        setOnDragDetected { me -> // drag from here...
            // java DnD: can't get finder or explorer drop location ("promised files" not implemented yet), therefore open this window.
            // mac crashes due to jvm: add to accessibility prefs, gradle dist, run in shell build/macApp/ssyncbrowser.app/Contents/MacOS/JavaAppLauncher
            val selfiles = selectionModel.selectedItems
            if (selfiles.isEmpty()) return@setOnDragDetected
            selfiles.find { it.isDir() }?.let {
                Helpers.dialogMessage(Alert.AlertType.WARNING, "Drag", "Can't drag folders!", "")
                me.consume()
                return@setOnDragDetected
            }
            val tempfolder = Files.createTempDirectory("ssyncbrowsertemp").toFile()
            val taskGetFile = MyTask<Unit> {
                updateTit("Downloading files for drag and drop...")
                selfiles.forEach { vf ->
                    updateMsg("Downloading file $vf...")
                    val lf = "${tempfolder.path}/${vf.getFileName()}"
                    logger.debug("downloading $vf to $lf...")
                    server.getConnection("").getfile("", vf.path, vf.modTime, lf)
                }
            }
            taskGetFile.setOnSucceeded {
                val newstage = Stage()
                val dragView = DragView(tempfolder)
                newstage.scene = Scene(dragView.root)
                newstage.initModality(Modality.WINDOW_MODAL)
                newstage.show()
            }
            MyWorker.runTask(taskGetFile)
            me.consume()
        }
        setOnDragOver { de ->
            if (de.gestureSource != this) {
                //println("dragover: hasfiles=${de.dragboard.hasFiles()} source=${de.gestureSource} de=$de db=${de.dragboard}")
                if (de.dragboard.hasFiles()) de.acceptTransferModes(TransferMode.COPY)
            }
            de.consume()
        }
        setOnDragDropped { de -> // dropped from external
            //println("dragdropped: hasfiles=${de.dragboard.hasFiles()} source=${de.gestureSource} de=$de db=${de.dragboard}")
            if (de.dragboard.hasFiles()) {
                //println("drop files ${de.dragboard.files} mode=${de.transferMode}")
                val fff = de.dragboard.files
                // check no dirs
                fff.find { it.isDirectory }?.let {
                    Helpers.dialogMessage(Alert.AlertType.WARNING, "Drop", "Can't drop folders!", "")
                    de.isDropCompleted = false
                    de.consume()
                    return@setOnDragDropped
                }
                // check overwrites
                val existing = mutableListOf<VirtualFile>()
                fff.forEach { f ->
                    files.find { vf -> vf.getFileName() == f.name }?.let { existing.add(it) }
                }
                if (existing.isNotEmpty()) {
                    if (!Helpers.dialogOkCancel("Drop files...", "Remote files existing, overwrite all or cancel?", existing.joinToString("\n"))) {
                        de.isDropCompleted = false
                        de.consume()
                        return@setOnDragDropped
                    }
                }

                MyWorker.runTaskWithConn({
                    logger.info("successfully uploaded files!")
                    de.isDropCompleted = true
                    de.consume()
                    updateBrowser()
                }, "Uploading", server, "") { c ->
                    fff.forEach { f ->
                        updateTit("Uploading file $f...")
                        c.putfile("", f.path, f.lastModified(), "${currentPath.value}${f.name}")
                    }
                }
                de.isDropCompleted = true
            }
            de.consume()
        }

        fun saveColumnsettings() {
            val order = columns.mapNotNull { Pair(it.userData as Int, it.isVisible) }
            SettingsStore.ssbSettings.browsercols.set(order.joinToString(";") { "${it.first}:${it.second}" } )
        }
        columns.onChange {
            saveColumnsettings()
        }
        columns.forEach { it.visibleProperty().onChange { v -> // bug that needed
            saveColumnsettings()
            it.maxWidthProperty().set(if (v) 5000.0 else 0.0) // bug
            this.requestResize() ; this.requestResize()
        } }
    }

    override val root = vbox {
        prefWidth = 800.0
        prefHeight = 600.0
        toolbar {
            when (mode) {
                BrowserViewMode.NORMAL -> {}
                BrowserViewMode.SELECTFOLDER -> button("Select Folder").setOnAction {
                    if (fileTableView.selectedItem?.isDir() == true) selectFolderCallback(fileTableView.selectedItem!!)
                    this@BrowserView.close() }
                BrowserViewMode.SELECTFOLDERS -> button("Select Folder(s)").setOnAction {
                    selectFoldersCallback(fileTableView.selectionModel.selectedItems.filter { vf -> vf.isDir() })
                    this@BrowserView.close() }
            }
        }
        this += pathButtonFlowPane
        this += fileTableView
        fileTableView.smartResize()
    }

    private var canRename: Boolean = false
    private var canChmod: Boolean = false
    private var canDuplicate: Boolean = false

    private fun updateBrowser() {
        val taskListLocal = MyTask<MutableList<VirtualFile>> {
            updateTit("Initialize connection...")
            // do here because needs to be done in background thread
            canRename = server.getConnection(basePath).canRename()
            canChmod = server.getConnection(basePath).canChmod()
            canDuplicate = server.getConnection(basePath).canDuplicate()

            val tmpl = mutableListOf<VirtualFile>()
            updateTit("Getting remote file list...")
            server.getConnection(basePath).list(currentPath.value, "", false, true) { it2 ->
                if (it2.path != currentPath.value) tmpl.add(it2)
            }
            tmpl.sortWith( Comparator { o1, o2 -> o1.toString().toUpperCase().compareTo(o2.toString().toUpperCase()) })
            tmpl
        }
        taskListLocal.setOnSucceeded {
            files.clear()
            files.setAll(taskListLocal.value)
            fileTableView.sort()
            fileTableView.requestResize() ; fileTableView.requestResize() // bug
            // path buttons
            pathButtonFlowPane.children.clear()
            pathButtonFlowPane.add(label("Path:"))
            var tmpp = currentPath.value
            val pl = mutableListOf<String>()
            while (tmpp.removeSuffix("/").isNotEmpty()) {
                pl += tmpp.removeSuffix("/")
                tmpp = Helpers.getParentFolder(tmpp)
            }
            pathButtonFlowPane.add(button("[base]") { action { currentPath.set("") }})
            pl.reversed().forEach { it2 ->
                pathButtonFlowPane.add(button(Helpers.getFileName(it2)!!) {
                    action { currentPath.set("$it2/") }
                })
            }
        }
        taskListLocal.setOnFailed {
            throw taskListLocal.exception
        }
        MyWorker.runTask(taskListLocal)
    }

    override fun doAfterShown() {
        logger.debug("opening browser protobp=${server.getProtocol().baseFolder.value} bp=$basePath")
        updateBrowser()
    }
}
