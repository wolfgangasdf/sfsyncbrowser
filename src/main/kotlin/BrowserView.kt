import javafx.beans.property.SimpleStringProperty
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.TableRow
import javafx.scene.input.KeyCode
import javafx.scene.input.TransferMode
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.util.Callback
import mu.KotlinLogging
import store.*
import synchro.VirtualFile
import tornadofx.*
import util.Helpers
import util.Helpers.dialogInputString
import util.Helpers.getFileIntoTempAndDo
import util.MyTask
import util.MyWorker
import util.SSP
import java.io.File
import java.nio.file.Files

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

    private var currentPath = SimpleStringProperty(path).apply {
        onChange { if (it != null) updateBrowser() }
    }

    private val files = mutableListOf<VirtualFile>().observable()

    var selectFolderCallback: (f: VirtualFile) -> Unit = {}
    var selectFoldersCallback: (fl: List<VirtualFile>) -> Unit = {}
    private fun isNormal() = mode == BrowserViewMode.NORMAL

    private val pathButtonFlowPane = hbox {
        label("Path:")
    }

    // mac quicklook lets "space" to close through... this is good in principle, to allow navigation while preview open. implement?
    private var lastpreviewvf: VirtualFile? = null

    private val fileTableView = tableview(files) {
        column("title", VirtualFile::getFileNameBrowser).remainingWidth()
        column("size", VirtualFile::size)
        column("perms", VirtualFile::permissions)
    }.apply {
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
                server.bookmarks += BrowserBookmark(server, SimpleStringProperty(selectedItem?.path))
            }
            item("Add syncfile") { isDisable = !isNormal() || selectedItem?.isFile() != true }.action {
                server.syncs += Sync(SyncType.FILE, SSP(selectedItem?.getFileName()), SSP("not synced"),
                        SSP(""), SSP(selectedItem?.getParent()), server = server).apply {
                            localfolder.set(DBSettings.getCacheFolder(cacheid.value))
                            auto.set(true)
                        }
                // TODO somehow initiate sync and reveal afterwards! also
            }
            item("Add sync...") { isDisable = !isNormal() || selectedItem?.isDir() != true }.action {
                val sname = dialogInputString("New sync", "Enter sync name:", "")
                var lfolder = "sylocalfolder"
                chooseDirectory("Select local folder")?.let {
                    lfolder = if (it.absolutePath.endsWith("/")) it.absolutePath else it.absolutePath + "/"
                }
                server.syncs += Sync(SyncType.NORMAL, SSP(sname?:"syname"),
                        SSP(""), SSP(lfolder),
                        SSP(selectedItem!!.path), server=server)
            }
            item("Add temporary sync...") { isDisable = !isNormal() || selectedItem?.isDir() != true }.action {
                val sname = dialogInputString("New temporary sync", "Enter sync name:", "")
                server.syncs += Sync(SyncType.NORMAL, SSP(sname?:"syname"),
                        SSP(""), SSP(""),
                        SSP(selectedItem!!.path), server=server).apply {
                    localfolder.set(DBSettings.getCacheFolder(cacheid.value))
                    auto.set(true)
                }
            }
            separator()
            item("Rename...") { isDisable = !isNormal() || selectedItem == null || !server.getConnection(basePath).canRename() }.action {
                dialogInputString("Rename...", "Enter new name:", "", selectedItem!!.getFileName())?.let {
                    server.getConnection(basePath).extRename(selectedItem!!.path, selectedItem!!.getParent() + it)
                    updateBrowser()
                }
            }
            item("Change permissions...") { isDisable = !isNormal() }.action {
                // TODO
            }
            item("Copy URL") { isDisable = !isNormal() || selectedItem == null }.action {
                clipboard.putString("${server.getProtocol().protocoluri.value}:${server.getProtocol().baseFolder.value}${selectedItem!!.path}")
            }
            separator()
            item("Duplicate...") { isDisable = !isNormal() || selectedItem == null || !server.getConnection(basePath).canDuplicate() }.action {
                dialogInputString("Duplicate...", "Enter new name:", "", selectedItem!!.getFileName())?.let {
                    server.getConnection(basePath).extDuplicate(selectedItem!!.path, selectedItem!!.getParent() + it)
                    updateBrowser()
                }
            }
            item("New folder...") { isDisable = !isNormal() }.action {
                dialogInputString("Create new folder", "Enter folder name:", "", "")?.let {
                    server.getConnection(basePath).mkdirrec(currentPath.value + it)
                    updateBrowser()
                }
            }
            item("New file...") { isDisable = !isNormal() }.action {
                dialogInputString("Create new file", "Enter file name:", "", "")?.let {
                    val tempfolder = Files.createTempDirectory("ssyncbrowsertemp").toFile()
                    val f = File("${tempfolder.path}/$it")
                    if (!f.createNewFile()) throw Exception("Error creating file ${f.path}")
                    server.getConnection(basePath).putfile("", f.path, f.lastModified(), "${currentPath.value}${f.name}")

                    updateBrowser()
                }
            }
            item("Delete") { isDisable = !isNormal() }.action {
                selectionModel.selectedItems.forEach {
                    server.getConnection("").deletefile(it.path)
                }
                updateBrowser()
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
                val taskUploadFiles = MyTask<Unit> {
                    fff.forEach { f ->
                        updateTit("Uploading file $f...")
                        server.getConnection("").putfile("", f.path, f.lastModified(), "${currentPath.value}${f.name}")
                    }
                }
                taskUploadFiles.setOnSucceeded {
                    logger.info("successfully uploaded files!")
                    de.isDropCompleted = true
                    de.consume()
                    updateBrowser()
                }
                MyWorker.runTask(taskUploadFiles)
                de.isDropCompleted = true
            }
            de.consume()
        }
    }

    override val root = vbox {
        prefWidth = 800.0
        prefHeight = 600.0
        toolbar {
            when (mode) {
                BrowserViewMode.NORMAL -> {
                }
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

    private fun updateBrowser() {
        val taskListLocal = MyTask<MutableList<VirtualFile>> {
            val tmpl = mutableListOf<VirtualFile>()
            updateTit("Getting remote file list...")
            server.getConnection(basePath).list(currentPath.value, "", false) { it2 ->
                if (it2.path != currentPath.value) tmpl.add(it2)
            }
            tmpl.sortWith( Comparator { o1, o2 -> o1.toString().toUpperCase().compareTo(o2.toString().toUpperCase()) })
            tmpl
        }
        taskListLocal.setOnSucceeded {
            files.clear()
            files.setAll(taskListLocal.value)
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
