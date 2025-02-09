import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.ObservableList
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.stage.Modality
import javafx.stage.Stage
import mu.KotlinLogging
import store.*
import synchro.VirtualFile
import tornadofx.*
import util.*
import util.Helpers.dialogInputString
import util.Helpers.dialogMessage
import util.Helpers.dialogOkCancel
import util.Helpers.dialogYesNoCancel
import util.Helpers.editFile
import util.Helpers.getFileIntoTempAndDo
import util.Helpers.openFile
import util.Helpers.runUIwait
import util.Helpers.toThousandsCommas
import util.Helpers.tokMGTPE
import java.nio.file.attribute.PosixFilePermission
import kotlin.math.round

private val logger = KotlinLogging.logger {}

enum class BrowserViewMode {
    NORMAL, SELECTFOLDER, SELECTFOLDERS
}

private class DragView(temppath: MFile) : View("Drag...") {
    private val btDnd = button("The file(s) have been downloaded to $temppath.\n" +
            "Drag this to the destination!\n(Use shift+drag to move files remotely)").apply {
        setOnDragDetected { me ->
            val dragBoard = startDragAndDrop(TransferMode.MOVE) // so tempfiles are removed by OS
            dragBoard.setContent { putFiles(temppath.listFiles().map { it.file }.toList()) }
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

    companion object {
        private val dataFormatVFs = DataFormat("VirtualFiles")
    }
    private var oldPath = ""
    private var currentPath = SimpleStringProperty(path).apply {
        onChange { if (it != null) updateBrowser() }
    }

    private val files = mutableListOf<VirtualFile>().asObservable()

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

    class TriCheckBox(title: String, property: SimpleIntegerProperty, tristate: Boolean = false): HBox() {
        private fun statechar(state: Int) = hashMapOf( -1 to "?", 0 to "-", 1 to "+")[state]
        init {
            this.alignment = Pos.CENTER_LEFT
            val b = Button(statechar(property.value))
            b.style {
                prefWidth = 20.px
                padding = box(3.5.px)
                backgroundInsets = multi(box(5.px))
            }
            b.setOnAction {
                var v = property.value + 1
                if (v > 1) v = if (tristate) -1 else 0
                b.text = statechar(v)
                property.set(v)
            }
            val l = Label(title)
            children += b
            children += l
        }
    }

    inner class InfoView(vfs: ObservableList<VirtualFile>): MyView() {
        override val root = Form()
        private val recursively = SimpleBooleanProperty(false)
        init {
            val haveDir = vfs.firstOrNull { it.isDir() } != null
            val dotri = haveDir || vfs.size > 1
            val permips = PosixFilePermission.entries.associateWith {
                if (dotri) SimpleIntegerProperty(-1) else {
                    SimpleIntegerProperty(if (vfs.first().permissions.contains(it)) 1 else 0)
                }
            }
            with(root) {
                fieldset("Info") {
                    field("Path") { label(if (vfs.size > 1) "multiple..." else vfs.first().path) }
                    field("Size") {
                        label(toThousandsCommas(vfs.sumOf { it.size }))
                        button("Calculate...") {
                            isDisable = !haveDir
                            setOnAction {
                                var size = 0L
                                MyWorker.runTaskWithConn({
                                    dialogMessage(Alert.AlertType.INFORMATION, "Info", "Path: ${vfs.first().path}\nRecursive size: ${toThousandsCommas(size)} bytes", "")
                                }, "Calculate size recursively...", server, basePath) { c ->
                                    vfs.forEach { vf ->
                                        c.list(vf.path, "", recursive = true, resolveSymlinks = true) { vflist ->
                                            size += vflist.size
                                            !isCancelled
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                fieldset("Permissions") {
                    field("User") {
                        this += TriCheckBox("read", permips.getValue(PosixFilePermission.OWNER_READ), dotri)
                        this += TriCheckBox("write", permips.getValue(PosixFilePermission.OWNER_WRITE), dotri)
                        this += TriCheckBox("execute", permips.getValue(PosixFilePermission.OWNER_EXECUTE), dotri)
                    }
                    field("Group") {
                        this += TriCheckBox("read", permips.getValue(PosixFilePermission.GROUP_READ), dotri)
                        this += TriCheckBox("write", permips.getValue(PosixFilePermission.GROUP_WRITE), dotri)
                        this += TriCheckBox("execute", permips.getValue(PosixFilePermission.GROUP_EXECUTE), dotri)
                    }
                    field("Others") {
                        this += TriCheckBox("read", permips.getValue(PosixFilePermission.OTHERS_READ), dotri)
                        this += TriCheckBox("write", permips.getValue(PosixFilePermission.OTHERS_WRITE), dotri)
                        this += TriCheckBox("execute", permips.getValue(PosixFilePermission.OTHERS_EXECUTE), dotri)
                    }
                    field("") {
                        checkbox("Apply recursively", recursively) {
                            isDisable = !haveDir
                        }
                        button("Apply permissions").setOnAction {
                            MyWorker.runTaskWithConn({
                                updateBrowser()
                                this@InfoView.close()
                            }, "Chmod", server, basePath) { c ->
                                val fff = arrayListOf<VirtualFile>()
                                fun addFileUpdatePerms(vvf: VirtualFile) {
                                    fun checkSetPerm(p: PosixFilePermission) {
                                        when (permips.getValue(p).value) {
                                            0 -> vvf.permissions.remove(p)
                                            1 -> vvf.permissions.add(p)
                                        }
                                    }
                                    checkSetPerm(PosixFilePermission.OWNER_WRITE)
                                    checkSetPerm(PosixFilePermission.OWNER_READ)
                                    checkSetPerm(PosixFilePermission.OWNER_EXECUTE)
                                    checkSetPerm(PosixFilePermission.GROUP_WRITE)
                                    checkSetPerm(PosixFilePermission.GROUP_READ)
                                    checkSetPerm(PosixFilePermission.GROUP_EXECUTE)
                                    checkSetPerm(PosixFilePermission.OTHERS_WRITE)
                                    checkSetPerm(PosixFilePermission.OTHERS_READ)
                                    checkSetPerm(PosixFilePermission.OTHERS_EXECUTE)
                                    fff += vvf
                                }
                                updateTit("Getting list of files...")
                                vfs.forEach { vf ->
                                    addFileUpdatePerms(vf)
                                    if (vf.isDir() && recursively.value) {
                                        c.list(vf.path, "", recursive = true, resolveSymlinks = true) { vflist ->
                                            addFileUpdatePerms(vflist)
                                            !isCancelled
                                        }
                                    }
                                }
                                updateTit("Applying permissions...")
                                fff.forEach {
                                    logger.debug("applying permissions ${it.path} ${it.getPermString()}")
                                    c.extChmod(it.path, it.permissions)
                                }
                            }
                        }
                    }

                }
            }
        }
    }

    private fun dropit(de: DragEvent, onto: String) {
        logger.debug("dropped: have files = ${de.dragboard.hasFiles()}!")
        //println("dragdropped: hasfiles=${de.dragboard.hasFiles()} source=${de.gestureSource} de=$de db=${de.dragboard}")
        if (de.dragboard.hasFiles()) { // drop of local file
            // get local base path of file(s)
            val localBase = de.dragboard.files.first().parent + "/"

            val fff = arrayListOf<MFile>()

            // get drop file & dir list
            de.dragboard.files.forEach { f ->
                if (f.isDirectory) {
                    f.walkTopDown().forEach { fff += MFile(it) }
                } else fff += MFile(f)
            }

            MyWorker.runTaskWithConn({
                logger.info("successfully uploaded files!")
                updateBrowser()
            }, "Uploading", server, "") { c ->
                var bytescopied = 0L
                val bytesTotal = fff.sumOf { m -> m.getSize() }
                fff.forEachIndexed { idx, f ->
                    updateTit("Uploading file ($idx/${fff.size}, ${round(100.0*bytescopied/bytesTotal)}%)  $f")
                    updateMsg("")
                    val rp = "$onto${f.internalPath.removePrefix(localBase)}"
                    val doit = if (c.listSingleFile(rp) == null) 1 else
                        runUIwait { dialogYesNoCancel("Drop files...", "Remote file existing, overwrite?", c.remoteBasePath + rp) }
                    if (doit == 1) c.putfile("", f.asVFPath, f.lastModified(), rp)
                    else if (doit == -1) return@runTaskWithConn
                    bytescopied += f.getSize()
                }
            }
        } else if (de.dragboard.hasContent(dataFormatVFs)) { // remote -> remote drop
            val dc = de.dragboard.getContent(dataFormatVFs)
            MyWorker.runTaskWithConn({
                logger.info("successfully moved files!")
                de.isDropCompleted = true
                de.consume()
                updateBrowser()
            }, "Uploading", server, "") { c ->
                if (dc is List<*>) {
                    dc.forEachIndexed { idx, f ->
                        if (f is VirtualFile) {
                            val rp = onto + f.getFileName()
                            logger.debug("moving file $f to $rp ...")
                            updateTit("Renaming file ($idx/${dc.size}) $f to $rp ...")
                            val doit = if (c.listSingleFile(onto + f.getFileName()) == null) 1 else {
                                val doit2 = runUIwait { dialogYesNoCancel("Drop files...", "Remote file existing, overwrite/delete it?", c.remoteBasePath + rp) }
                                if ( doit2 == 1) c.deletefile(rp)
                                doit2
                            }
                            if (doit == 1) c.extRename(f.path, rp)
                            else if (doit == -1) return@runTaskWithConn
                        }
                    }
                }
            }

            de.isDropCompleted = true
        }

    }

    enum class SfsOp { NONE, OPEN, EDIT }
    private val fileTableView = tableview(files) {
        val colo = SettingsStore.ssbSettings.browsercols.value.let { if (it == "") "1:true;2:true;3:true;4:true" else it }.
                split(";").map { cs -> cs.split(":").let { Pair(it[0].toInt(), it[1].toBoolean()) } }
        colo.forEach { col ->
            when (col.first) {
                1 -> column("Size", VirtualFile::size).apply { cellFormat { text = tokMGTPE(it) } ; minWidth = 75.0 }
                2 -> column("Perms", VirtualFile::getPermString).apply { minWidth = 80.0 }
                3 -> column("Modtime", VirtualFile::modTime).apply {
                    cellFormat { text = Helpers.dformat().format(it) }
                    minWidth = 150.0
                }
                4 -> column("Name", VirtualFile::getFileNameBrowser).remainingWidth().apply {
                    sortType = TableColumn.SortType.ASCENDING
                    minWidth = 2500.0
                }
                else -> { error("Unknown column number ${col.first}") ; null }
            }?.let {
                it.userData = col.first
                if (!col.second && col.first != 4) {
                    it.isVisible = false
                    it.maxWidth = 0.0 // bug
                }
            }
        }
        vgrow = Priority.ALWAYS
//        columnResizePolicy = SmartResize.POLICY
        setColumnResizePolicy { true } // TODO still not ideal: they should auto size, but that's hard.
    }.apply {
        columns.find { it.userData == 4 }?.let { sortOrder.add(it) }
        isTableMenuButtonVisible = true
        multiSelect(true)

        lazyContextmenu {
            this += miRefresh
            this += miAddBookmark
            this += miAddTempSyncFile
            this += miAddTempSyncFileOpen
            this += miAddTempSyncFileEdit
            this += miAddSync
            this += miAddTempSync
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

        setOnKeyPressed { ke ->
            if (ke.code == KeyCode.SPACE) { // quicklook
                if (selectedItem?.isFile() == true && selectedItem != lastpreviewvf) {
                    lastpreviewvf = selectedItem
                    getFileIntoTempAndDo(server, selectedItem!!) { Helpers.previewDocument(it) }
                }
            } else if (ke.code == KeyCode.RIGHT) {
                if (selectedItem?.isDir() == true) currentPath.set(selectedItem!!.path)
            } else if (ke.code == KeyCode.LEFT) {
                currentPath.set(Helpers.getParentFolder(currentPath.value))
            } else if (ke.code == KeyCode.ESCAPE) {
                contextMenu?.hide() ?: close()
            } else if (!ke.isMetaDown && !ke.isControlDown && !ke.isShiftDown && !ke.isAltDown && selectedItem != null &&
                    (ke.code.isDigitKey || ke.code.isLetterKey)) { // search in file list by alphanumeric keys
                val idx0 = selectionModel.focusedIndex
                var idx1 = idx0
                while (true) {
                    idx1 += 1
                    if (idx1 == files.size) idx1 = 0 // at end, loop
                    if (idx1 == idx0) break // back to beginning, stop
                    if (files[idx1].getFileName().startsWith(ke.text)) {
                        selectionModel.clearAndSelect(idx1)
                        scrollTo(selectedItem)
                        break
                    }
                }
            }
        }
        // empty tableview doesn't show placeholder-rows, so have to do these two https://stackoverflow.com/questions/16992631
        setOnDragOver { de ->
            if (de.dragboard.hasFiles()) de.acceptTransferModes(TransferMode.COPY)
            de.consume()
        }
        setOnDragDropped { de ->
            logger.debug("drop on tableview")
            dropit(de, currentPath.value)
            de.consume()
        }
        setRowFactory {
            val row = TableRow<VirtualFile>()
            row.setOnMouseClicked { it2 ->
                if (it2.clickCount == 2 && row.item.isDir()) {
                    currentPath.set(row.item.path)
                }
            }
            row.setOnDragOver { de ->
                if (de.gestureSource != this) {
                    if (de.dragboard.hasFiles()) de.acceptTransferModes(TransferMode.COPY)
                } else de.acceptTransferModes(TransferMode.MOVE)
                de.consume()
            }
            row.setOnDragDropped { de ->
                logger.debug("drop on row.item=${row.item}")
                val onto = if (row.item == null || row.item?.isDir() == false) currentPath.value
                                       else if (row.item.isDir()) row.item.path else null
                logger.debug("  onto=$onto")
                if (onto != null) dropit(de, onto)
                de.consume()
            }
            row
        }
        setOnDragDetected { me -> // drag from here...
            // java DnD: can't get finder or explorer drop location ("promised files" not implemented yet), therefore open this window.
            if (selectionModel.selectedItems.isEmpty()) return@setOnDragDetected
            if (me.isShiftDown) { // internal drag
                val db = startDragAndDrop(TransferMode.MOVE)
                val content = ClipboardContent()
                content[dataFormatVFs] = selectionModel.selectedItems.toList()
                db.setContent(content)
            } else { // drag out of sfsb
                val remoteBase = selectionModel.selectedItems.first().getParent()
                val tempfolder = MFile.createTempDirectory("sfsyncbrowsertemp")

                val taskGetFile = MyTask {
                    updateTit("Downloading files for drag and drop...")
                    val fff = server.getConnection("").listRecursively(selectionModel.selectedItems.toList())
                    var bytescopied = 0L
                    val bytesTotal = fff.sumOf { m -> m.size }
                    fff.forEachIndexed { idx, vf ->
                        updateMsg("Downloading file ($idx/${fff.size}, ${round(100.0*bytescopied/bytesTotal)}%)  $vf")
                        logger.debug("rb=$remoteBase vfpath=${vf.path}")
                        val lf = "${tempfolder.internalPath}/${vf.path.removePrefix(remoteBase)}"
                        logger.debug("downloading $vf to $lf...")
                        server.getConnection("").getfile("", vf.path, vf.modTime, lf)
                        bytescopied += vf.size
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
            }
            me.consume()
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

    private fun getPathButton(text: String, setPath: String): Button {
        return Button(text).apply {
            setOnAction { currentPath.set(setPath) }
            setOnDragOver { de ->
                if (de.dragboard.hasFiles()) de.acceptTransferModes(TransferMode.COPY)
                if (de.dragboard.hasContent(dataFormatVFs)) de.acceptTransferModes(TransferMode.MOVE)
                de.consume()
            }
            setOnDragDropped { de ->
                logger.debug("drop: ${de.dragboard}")
                if (de.dragboard.hasFiles() || de.dragboard.hasContent(dataFormatVFs)) {
                    logger.debug("internal drop ${de.dragboard} setp=$setPath")
                    dropit(de, setPath)
                }
                de.consume()
            }
        }
    }

    private fun updateBrowser() {
        val taskListLocal = MyTask {
            updateTit("Initialize connection...")
            // do here because needs to be done in background thread
            canRename = server.getConnection(basePath).canRename()
            canChmod = server.getConnection(basePath).canChmod()
            canDuplicate = server.getConnection(basePath).canDuplicate()

            val tmpl = mutableListOf<VirtualFile>()
            updateTit("Getting remote file list...")
            server.getConnection(basePath).list(currentPath.value, "", recursive = false, resolveSymlinks = true) { it2 ->
                if (it2.path != currentPath.value &&
                        (SettingsStore.ssbSettings.showHiddenfiles.value || !it2.getFileName().startsWith("."))) tmpl.add(it2)
                !isCancelled
            }
            tmpl.sortWith { o1, o2 -> o1.toString().uppercase().compareTo(o2.toString().uppercase()) }
            tmpl
        }
        taskListLocal.setOnSucceeded {
            files.clear()
            files.setAll(taskListLocal.value)
            fileTableView.sort()
            fileTableView.requestResize() ; fileTableView.requestResize() // bug
            // select last folder or first
            if (files.isEmpty()) { // hack to trigger onchange update of mi*
                files.add(VirtualFile("", 0, 0))
                fileTableView.selectionModel.selectFirst()
                files.removeAt(0)
                fileTableView.selectionModel.clearSelection()
            } else {
                files.find { f -> f.path == oldPath }?.let { f -> fileTableView.selectionModel.select(f) }
                    ?: fileTableView.selectFirst()
            }
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
            pathButtonFlowPane.add(getPathButton("[base]", ""))
            pl.reversed().forEach { it2 ->
                pathButtonFlowPane.add(getPathButton(Helpers.getFileName(it2)!!, "$it2/"))
            }
        }
        taskListLocal.setOnFailed {
            throw taskListLocal.exception
        }
        MyWorker.runTask(taskListLocal)
    }

    private fun addFilesync(op: SfsOp) {
        val newSync = Sync(SyncType.FILE, SimpleStringProperty(fileTableView.selectedItem?.getFileName()), SimpleStringProperty("not synced"),
                SimpleStringProperty(""), SimpleStringProperty(fileTableView.selectedItem?.getParent()), server = server).apply {
            localfolder.set(DBSettings.getCacheFolder(cacheid.value))
            auto.set(true)
        }
        server.syncs += newSync
        MainView.compSyncFile(newSync) {
            when (op) {
                SfsOp.NONE -> openFile(MFile(newSync.localfolder.value))
                SfsOp.OPEN -> openFile(MFile("${newSync.localfolder.value}/${newSync.title.value}"))
                SfsOp.EDIT -> if (SettingsStore.ssbSettings.editor.value != "")
                    editFile(MFile("${newSync.localfolder.value}/${newSync.title.value}"))
                else
                    dialogMessage(Alert.AlertType.ERROR, "Edit file", "Set editor in settings first!", "")
            }
        }
    }

    private val miRefresh = MyMenuitem("Refresh") {
        updateBrowser()
    }

    private val miAddBookmark: MyMenuitem = MyMenuitem("Add bookmark") {
        server.bookmarks += BrowserBookmark(server, SimpleStringProperty(fileTableView.selectedItem?.path))
    }.withEnableOnSelectionChanged { isNormal() && it.firstOrNull()?.isDir() == true }

    private val miAddTempSyncFile: MyMenuitem = MyMenuitem("Add temporary syncfile") {
        addFilesync(SfsOp.NONE)
    }.withEnableOnSelectionChanged { isNormal() && it.firstOrNull()?.isFile() == true }

    private val miAddTempSyncFileOpen: MyMenuitem = MyMenuitem("Add temporary syncfile and open") {
        addFilesync(SfsOp.OPEN)
    }.withEnableOnSelectionChanged { isNormal() && it.firstOrNull()?.isFile() == true }

    private val miAddTempSyncFileEdit: MyMenuitem = MyMenuitem("Add temporary syncfile and edit") {
        addFilesync(SfsOp.EDIT)
    }.withEnableOnSelectionChanged { isNormal() && it.firstOrNull()?.isFile() == true }

    private val miAddSync: MyMenuitem = MyMenuitem("Add permanent sync...") {
        val sname = dialogInputString("New sync", "Enter sync name:", "")
        var lfolder = "sylocalfolder"
        chooseDirectory("Select local folder")?.asMFile()?.let {
            lfolder = if (it.internalPath.endsWith("/")) it.internalPath else it.internalPath + "/"
        }
        server.syncs += Sync(SyncType.NORMAL, SimpleStringProperty(sname?:"syname"),
                SimpleStringProperty(""), SimpleStringProperty(lfolder),
                SimpleStringProperty(fileTableView.selectedItem!!.path), server=server)
    }.withEnableOnSelectionChanged { isNormal() && it.firstOrNull()?.isDir() == true }

    private val miAddTempSync: MyMenuitem = MyMenuitem("Add temporary sync...") {
        val sname = dialogInputString("New temporary sync", "Enter sync name:", "")?:"tempsync"
        val newSync = Sync(SyncType.TEMP, SimpleStringProperty(sname),
                SimpleStringProperty("not synced"), SimpleStringProperty(""),
                SimpleStringProperty(fileTableView.selectedItem!!.path), server=server).apply {
            cacheid.set(sname + "-" + cacheid.value)
            localfolder.set(DBSettings.getCacheFolder(cacheid.value))
            auto.set(false)
        }
        server.syncs += newSync
        MainView.compSyncTemp(newSync) { openFile(MFile(newSync.localfolder.value)) }
    }.withEnableOnSelectionChanged { isNormal() && it.firstOrNull()?.isDir() == true }

    private val miRename: MyMenuitem = MyMenuitem("Rename...", KeyCodeCombination(KeyCode.R, KeyCombination.META_DOWN)) {
        dialogInputString("Rename...", "Enter new name:", "", fileTableView.selectedItem!!.getFileName())?.let {
            MyWorker.runTaskWithConn({ updateBrowser() }, "Rename", server, basePath) { c ->
                c.extRename(fileTableView.selectedItem!!.path, fileTableView.selectedItem!!.getParent() + it) }
        }
    }.withEnableOnSelectionChanged { isNormal() && it.size == 1 && canRename }

    private val miInfo: MyMenuitem = MyMenuitem("Info...", KeyCodeCombination(KeyCode.I, KeyCombination.META_DOWN)) {
        openNewWindow(InfoView(fileTableView.selectionModel.selectedItems), Modality.APPLICATION_MODAL)
    }.withEnableOnSelectionChanged { isNormal() && it.isNotEmpty() }

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
            val tempfolder = MFile.createTempDirectory("sfsyncbrowsertemp")
            val f = MFile("${tempfolder.internalPath}/$it")
            if (!f.createNewFile()) throw Exception("Error creating file $f")
            MyWorker.runTaskWithConn({ updateBrowser() }, "New file", server, basePath) { c -> c.putfile("", f.asVFPath, f.lastModified(), "${currentPath.value}${f.name}") }
        }
    }.apply { isDisable = !isNormal() }

    private val miDelete: MyMenuitem = MyMenuitem("Delete", KeyCodeCombination(KeyCode.BACK_SPACE, KeyCombination.META_DOWN)) {
        MyWorker.runTaskWithConn({
            updateBrowser()
        }, "Delete files...", server, basePath) { c ->
            updateTit("Delete: searching for files...")
            val fff = c.listRecursively(fileTableView.selectionModel.selectedItems.toList()).reversed() // reversed delete!
            if (runUIwait { dialogOkCancel("Delete files", "Really delete these files?", fff.joinToString("\n") { it.path })}) {
                fff.forEachIndexed { idx, vf ->
                    updateTit("Delete ($idx/${fff.size}) $vf...")
                    c.deletefile(vf.path)
                }
            }
        }
    }.withEnableOnSelectionChanged { isNormal() && it.isNotEmpty() }


    override val root = vbox {
        prefWidth = 800.0
        prefHeight = 600.0
        menubar {
            isUseSystemMenuBar = true
            menu("File") {
                this += miRefresh
                this += miAddBookmark
                this += miAddTempSyncFile
                this += miAddTempSyncFileOpen
                this += miAddTempSyncFileEdit
                this += miAddSync
                this += miAddTempSync
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

    override fun doAfterShown() {
        logger.debug("opening browser protobp=${server.getProtocol().baseFolder.value} bp=$basePath")
        updateBrowser()
    }
}
