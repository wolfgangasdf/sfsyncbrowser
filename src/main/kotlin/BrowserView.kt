import javafx.beans.property.SimpleBooleanProperty
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.TransferMode
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
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
import util.Helpers.toThousandsCommas
import util.Helpers.tokMGTPE
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

private val logger = KotlinLogging.logger {}

enum class BrowserViewMode {
    NORMAL, SELECTFOLDER, SELECTFOLDERS
}

private class DragView(temppath: File) : View("Drag...") {
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

    private var oldPath = ""
    private var currentPath = SSP(path).apply {
        onChange { if (it != null) updateBrowser() }
    }

    private val files = mutableListOf<VirtualFile>().observable()

    var selectFolderCallback: (f: VirtualFile) -> Unit = {}
    var selectFoldersCallback: (fl: List<VirtualFile>) -> Unit = {}
    private fun isNormal() = mode == BrowserViewMode.NORMAL

    // mac quicklook lets "space" to close through... this is good in principle, to allow navigation while preview open. implement?
    private var lastpreviewvf: VirtualFile? = null

    private val pathButtonFlowPane = flowpane {
        alignment = Pos.CENTER_LEFT
        paddingAll = 5.0
        hgap = 5.0
        vgap = 5.0
    }

    inner class MyMenuitem(text: String, keyCombination: KeyCombination? = null, onaction: () -> Unit): MenuItem(text) {
        fun withEnableOnSelectionChanged(act: MyMenuitem.(List<VirtualFile>) -> Boolean): MyMenuitem {
            fileTableView.selectionModel.selectedItems.onChange {
                isDisable = !act(fileTableView.selectionModel.selectedItems.toList())
            }
            return this
        }
        init {
            keyCombination?.apply { accelerator = this }
            setOnAction { onaction() }
        }
    }

    inner class InfoView(vf: VirtualFile): MyView() {
        override val root = Form()
        private val recursively = SimpleBooleanProperty(false)
        private val permbps = PosixFilePermission.values().map {
            it to SBP(vf.permissions.contains(it)).apply {
                onChange { op -> if (op) vf.permissions.add(it) else vf.permissions.remove(it) }
            }
        }.toMap()
        init {
            with(root) {
                fieldset("Info") {
                    field("Path") { label(vf.path) }
                    field("Size") {
                        label(toThousandsCommas(vf.size))
                        button("Calculate...") {
                            isDisable = vf.isFile()
                            setOnAction {
                                var size = 0L
                                MyWorker.runTaskWithConn({
                                    Helpers.dialogMessage(Alert.AlertType.INFORMATION, "Info", "Path: ${vf.path}\nRecursive size: ${toThousandsCommas(size)} bytes", "")
                                }, "Calculate size recursively...", server, basePath) { c ->
                                    c.list(vf.path, "", true, true) { vflist ->
                                        size += vflist.size
                                    }
                                }
                            }
                        }
                    }
                }
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
                        checkbox("Apply recursively", recursively) {
                            isDisable = !vf.isDir()
                            tooltip = Tooltip("For files, only (w,r) are changed!")
                        }
                        button("Apply permissions").setOnAction {
                            MyWorker.runTaskWithConn({ updateBrowser() }, "Chmod", server, basePath) { c ->
                                val fff = arrayListOf(vf)
                                if (vf.isDir() && recursively.value) {
                                    c.list(vf.path, "", true, true) { vflist ->
                                        fun checkSetPerm(p: PosixFilePermission) {
                                            if (vf.permissions.contains(p)) vflist.permissions.add(p) else vflist.permissions.remove(p)
                                        }
                                        checkSetPerm(PosixFilePermission.OWNER_WRITE)
                                        checkSetPerm(PosixFilePermission.OWNER_READ)
                                        checkSetPerm(PosixFilePermission.GROUP_WRITE)
                                        checkSetPerm(PosixFilePermission.GROUP_READ)
                                        checkSetPerm(PosixFilePermission.OTHERS_WRITE)
                                        checkSetPerm(PosixFilePermission.OTHERS_READ)
                                        fff += vflist
                                    }
                                }
                                fff.forEach {
                                    c.extChmod(it.path, it.permissions)
                                }
                            }
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
            this += miRefresh
            this += miAddBookmark
            this += miAddTempSync
            this += miAddTempSyncOpen
            this += miAddTempSyncEdit
            this += miAddSync
            this += miAddCachedSync
            separator()
            this += miRename
            this += miInfo
            this += miCopyURL
            separator()
            this += miDuplicate
            this += miNewFolder
            this += miNewFile
            this += miDelete
        }

        setOnKeyReleased { ke ->
            if (ke.code == KeyCode.SPACE) { // quicklook
                if (selectedItem?.isFile() == true && selectedItem != lastpreviewvf) {
                    lastpreviewvf = selectedItem
                    getFileIntoTempAndDo(server, selectedItem!!) { Helpers.previewDocument(it) }
                }
            } else if (ke.code == KeyCode.RIGHT) {
                if (selectedItem?.isDir() == true) currentPath.set(selectedItem!!.path)
            } else if (ke.code == KeyCode.LEFT) {
                currentPath.set(Helpers.getParentFolder(currentPath.value))
            }
        }

        setOnDragDetected { me -> // drag from here...
            // java DnD: can't get finder or explorer drop location ("promised files" not implemented yet), therefore open this window.
            if (selectionModel.selectedItems.isEmpty()) return@setOnDragDetected

            val remoteBase = selectionModel.selectedItems.first().getParent()
            val tempfolder = Files.createTempDirectory("ssyncbrowsertemp").toFile()

            val taskGetFile = MyTask<Unit> {
                updateTit("Downloading files for drag and drop...")
                val fff = arrayListOf<VirtualFile>()
                selectionModel.selectedItems.forEach { selvf ->
                    if (selvf.isDir()) {
                        server.getConnection("").list(selvf.path, "", true, true) {
                            fff += it
                        }
                    } else fff += selvf
                }

                fff.forEach { vf ->
                    updateMsg("Downloading file $vf...")
                    val lf = "${tempfolder.path}/${vf.path.removePrefix(remoteBase)}"
                    logger.debug("downloading $vf to $lf...")
                    server.getConnection("").getfile("", vf.path, vf.modTime, lf)
                }
            }.apply {
                setOnSucceeded {
                    val newstage = Stage()
                    val dragView = DragView(tempfolder)
                    newstage.scene = Scene(dragView.root)
                    newstage.initModality(Modality.WINDOW_MODAL)
                    newstage.show()
                }
                setOnFailed { throw exception }
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
                // get local base path of file(s)
                val localBase = de.dragboard.files.first().parent + "/"

                // check overwrites
                val existing = mutableListOf<VirtualFile>()
                de.dragboard.files.forEach { f ->
                    files.find { vf -> vf.getFileName() == f.name }?.let { existing.add(it) }
                }
                if (existing.isNotEmpty()) {
                    if (!Helpers.dialogOkCancel("Drop files...", "Remote files existing, overwrite all or cancel?", existing.joinToString("\n"))) {
                        de.isDropCompleted = false
                        de.consume()
                        return@setOnDragDropped
                    }
                }

                val fff = arrayListOf<File>()

                // expand dirs
                de.dragboard.files.forEach { f ->
                    if (f.isDirectory) {
                        f.walkTopDown().forEach { fff += it }
                    } else fff += f
                }

                MyWorker.runTaskWithConn({
                    logger.info("successfully uploaded files!")
                    de.isDropCompleted = true
                    de.consume()
                    updateBrowser()
                }, "Uploading", server, "") { c ->
                    fff.forEach { f ->
                        updateTit("Uploading file $f...")
                        c.putfile("", f.path, f.lastModified(), "${currentPath.value}${f.path.removePrefix(localBase)}")
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
                if (it2.path != currentPath.value &&
                        (SettingsStore.ssbSettings.showHiddenfiles.value || !it2.getFileName().startsWith("."))) tmpl.add(it2)
            }
            tmpl.sortWith( Comparator { o1, o2 -> o1.toString().toUpperCase().compareTo(o2.toString().toUpperCase()) })
            tmpl
        }
        taskListLocal.setOnSucceeded {
            files.clear()
            files.setAll(taskListLocal.value)
            fileTableView.sort()
            fileTableView.requestResize() ; fileTableView.requestResize() // bug
            // select last folder or first
            files.find { f -> f.path == oldPath }?.let { f -> fileTableView.selectionModel.select(f) } ?: fileTableView.selectFirst()
            fileTableView.scrollTo(fileTableView.selectedItem)
            oldPath = currentPath.value
            fileTableView.requestFocus()
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

    private fun addFilesync(op: SfsOp) {
        val newSync = Sync(SyncType.FILE, SSP(fileTableView.selectedItem?.getFileName()), SSP("not synced"),
                SSP(""), SSP(fileTableView.selectedItem?.getParent()), SSP(""), server = server).apply {
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

    private val miRefresh = MyMenuitem("Refresh") {
        updateBrowser()
    }

    private val miAddBookmark: MyMenuitem = MyMenuitem("Add bookmark") {
        server.bookmarks += BrowserBookmark(server, SSP(fileTableView.selectedItem?.path))
    }.withEnableOnSelectionChanged { isNormal() && it.firstOrNull()?.isDir() == true }

    private val miAddTempSync: MyMenuitem = MyMenuitem("Add temporary syncfile") {
        addFilesync(SfsOp.NONE)
    }.withEnableOnSelectionChanged { isNormal() && it.firstOrNull()?.isFile() == true }

    private val miAddTempSyncOpen: MyMenuitem = MyMenuitem("Add temporary syncfile and open") {
        addFilesync(SfsOp.OPEN)
    }.withEnableOnSelectionChanged { isNormal() && it.firstOrNull()?.isFile() == true }

    private val miAddTempSyncEdit: MyMenuitem = MyMenuitem("Add temporary syncfile and edit") {
        addFilesync(SfsOp.EDIT)
    }.withEnableOnSelectionChanged { isNormal() && it.firstOrNull()?.isFile() == true }

    private val miAddSync: MyMenuitem = MyMenuitem("Add sync...") {
        val sname = dialogInputString("New sync", "Enter sync name:", "")
        var lfolder = "sylocalfolder"
        chooseDirectory("Select local folder")?.let {
            lfolder = if (it.absolutePath.endsWith("/")) it.absolutePath else it.absolutePath + "/"
        }
        server.syncs += Sync(SyncType.NORMAL, SSP(sname?:"syname"),
                SSP(""), SSP(lfolder),
                SSP(fileTableView.selectedItem!!.path), SSP(""), server=server)
    }.withEnableOnSelectionChanged { isNormal() && it.firstOrNull()?.isDir() == true }

    private val miAddCachedSync: MyMenuitem = MyMenuitem("Add sync...") {
        val sname = dialogInputString("New temporary sync", "Enter sync name:", "")
        server.syncs += Sync(SyncType.CACHED, SSP(sname?:"syname"),
                SSP(""), SSP(""),
                SSP(fileTableView.selectedItem!!.path), SSP(""), server=server).apply {
            localfolder.set(DBSettings.getCacheFolder(cacheid.value))
            auto.set(false)
        }
    }.withEnableOnSelectionChanged { isNormal() && it.firstOrNull()?.isDir() == true }

    private val miRename: MyMenuitem = MyMenuitem("Rename...", KeyCodeCombination(KeyCode.R, KeyCombination.META_DOWN)) {
        dialogInputString("Rename...", "Enter new name:", "", fileTableView.selectedItem!!.getFileName())?.let {
            MyWorker.runTaskWithConn({ updateBrowser() }, "Rename", server, basePath) { c ->
                c.extRename(fileTableView.selectedItem!!.path, fileTableView.selectedItem!!.getParent() + it) }
        }
    }.withEnableOnSelectionChanged { isNormal() && it.size == 1 && canRename }

    private val miInfo: MyMenuitem = MyMenuitem("Info...", KeyCodeCombination(KeyCode.I, KeyCombination.META_DOWN)) {
        openNewWindow(InfoView(fileTableView.selectedItem!!), Modality.APPLICATION_MODAL)
    }.withEnableOnSelectionChanged { isNormal() && it.size == 1 }

    private val miCopyURL: MyMenuitem = MyMenuitem("Copy URL") {
        clipboard.putString("${server.getProtocol().protocoluri.value}:${server.getProtocol().baseFolder.value}${fileTableView.selectedItem!!.path}")
    }.withEnableOnSelectionChanged { isNormal() && it.size == 1 }

    private val miDuplicate: MyMenuitem = MyMenuitem("Duplicate...", KeyCodeCombination(KeyCode.D, KeyCombination.META_DOWN)) {
        dialogInputString("Duplicate...", "Enter new name:", "", fileTableView.selectedItem!!.getFileName())?.let {
            MyWorker.runTaskWithConn({ updateBrowser() }, "Duplicate", server, basePath) {
                c -> c.extDuplicate(fileTableView.selectedItem!!.path, fileTableView.selectedItem!!.getParent() + it)
            }
        }
    }.withEnableOnSelectionChanged { isNormal() && it.size == 1 && canDuplicate }

    private val miNewFolder: MyMenuitem = MyMenuitem("New folder...", KeyCodeCombination(KeyCode.N, KeyCombination.META_DOWN, KeyCombination.SHIFT_DOWN)) {
        dialogInputString("Create new folder", "Enter folder name:", "", "")?.let {
            MyWorker.runTaskWithConn({ updateBrowser() }, "Mkdir", server, basePath) { c -> c.mkdirrec(currentPath.value + it, true) }
        }
    }.apply { isDisable = !listOf(BrowserViewMode.NORMAL, BrowserViewMode.SELECTFOLDER).contains(mode) }

    private val miNewFile: MyMenuitem = MyMenuitem("New file...", KeyCodeCombination(KeyCode.N, KeyCombination.META_DOWN)) {
        dialogInputString("Create new file", "Enter file name:", "", "")?.let {
            val tempfolder = Files.createTempDirectory("ssyncbrowsertemp").toFile()
            val f = File("${tempfolder.path}/$it")
            if (!f.createNewFile()) throw Exception("Error creating file ${f.path}")
            MyWorker.runTaskWithConn({ updateBrowser() }, "New file", server, basePath) { c -> c.putfile("", f.path, f.lastModified(), "${currentPath.value}${f.name}") }
        }
    }.apply { isDisable = !isNormal() }

    private val miDelete: MyMenuitem = MyMenuitem("Delete", KeyCodeCombination(KeyCode.BACK_SPACE, KeyCombination.META_DOWN)) {
        if (dialogOkCancel("Delete files", "Really delete these files?", fileTableView.selectionModel.selectedItems.joinToString { "${it.path}\n" })) {
            MyWorker.runTaskWithConn({ updateBrowser() }, "Delete", server, "") { c ->
                fileTableView.selectionModel.selectedItems.forEach { c.deletefile(it.path) }
            }
        }
    }.withEnableOnSelectionChanged { isNormal() && it.isNotEmpty() }


    override val root = VBox()
    init {
        with(root) {
            prefWidth = 800.0
            prefHeight = 600.0
            menubar {
                isUseSystemMenuBar = true
                menu("File") {
                    this += miRefresh
                    this += miAddBookmark
                    this += miAddTempSync
                    this += miAddTempSyncOpen
                    this += miAddTempSyncEdit
                    this += miAddSync
                    this += miAddCachedSync
                    separator()
                    this += miRename
                    this += miInfo
                    this += miCopyURL
                    separator()
                    this += miDuplicate
                    this += miNewFolder
                    this += miNewFile
                    this += miDelete
                }
                menu("View") {
                    checkmenuitem("Show hidden files", null, null, SettingsStore.ssbSettings.showHiddenfiles).apply {
                        setOnAction { updateBrowser() }
                    }
                    item("Paste")
                }
            }
            toolbar {
                when (mode) {
                    BrowserViewMode.NORMAL -> {
                    }
                    BrowserViewMode.SELECTFOLDER -> button("Select Folder").setOnAction {
                        if (fileTableView.selectedItem?.isDir() == true) selectFolderCallback(fileTableView.selectedItem!!)
                        this@BrowserView.close()
                    }
                    BrowserViewMode.SELECTFOLDERS -> button("Select Folder(s)").setOnAction {
                        selectFoldersCallback(fileTableView.selectionModel.selectedItems.filter { vf -> vf.isDir() })
                        this@BrowserView.close()
                    }
                }
            }
            this += pathButtonFlowPane
            this += fileTableView
            fileTableView.smartResize()
        }
    }

    override fun doAfterShown() {
        logger.debug("opening browser protobp=${server.getProtocol().baseFolder.value} bp=$basePath")
        updateBrowser()
    }
}
