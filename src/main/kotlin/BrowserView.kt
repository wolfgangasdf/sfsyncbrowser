import javafx.beans.property.SimpleStringProperty
import javafx.scene.control.TableRow
import javafx.scene.input.KeyCode
import javafx.scene.input.TransferMode
import javafx.util.Callback
import mu.KotlinLogging
import store.*
import synchro.VirtualFile
import tornadofx.*
import util.Helpers
import util.Helpers.getFileIntoTempAndDo
import util.MyTask
import util.MyWorker
import util.SSP

private val logger = KotlinLogging.logger {}

enum class BrowserViewMode {
    NORMAL, SELECTFOLDER, SELECTFOLDERS
}

class BrowserView(private val server: Server, private val basePath: String, path: String, mode: BrowserViewMode = BrowserViewMode.NORMAL) :
        View("${server.getProtocol().protocoluri.value}:${server.getProtocol().baseFolder.value}$basePath$path") {

    private var currentPath = SimpleStringProperty(path)

    private val files = mutableListOf<VirtualFile>().observable()

    var selectFolderCallback: (f: VirtualFile) -> Unit = {}
    var selectFoldersCallback: (fl: List<VirtualFile>) -> Unit = {}

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
        multiSelect(mode == BrowserViewMode.SELECTFOLDERS)
        rowFactory = Callback {
            val row = TableRow<VirtualFile>()
            row.setOnMouseClicked { it2 ->
                if (it2.clickCount == 2 && row.item.isDir()) {
                    currentPath.set(row.item.path)
                }
            }
            row
        }
        setOnKeyReleased { ke -> when(ke.code) {
             KeyCode.SPACE -> {
                 if (selectedItem?.isFile() == true && selectedItem != lastpreviewvf) {
                     lastpreviewvf = selectedItem
                     // TODO show progress dialog and allow interruption!
                     getFileIntoTempAndDo(server, selectedItem!!) { Helpers.previewDocument(it) }
                 }
             }
            else -> {}
        }}
        setOnDragDetected { me ->
            println("drag detected!")
            if (selectedItem?.isFile() == true) { // TODO also for dirs
                val dragBoard = startDragAndDrop(TransferMode.COPY, TransferMode.MOVE)
                // TODO how to get notified when dropped? only then download, possibly remove!
                dragBoard.setContent { putString("vf: $selectedItem") }
                me.consume()
            }
        }
        setOnDragOver { de ->
            if (de.gestureSource != this) {
                // TODO also for own type for DnD between browser windows!
                if (de.dragboard.hasFiles()) de.acceptTransferModes(TransferMode.COPY, TransferMode.MOVE)
            }
            de.consume()
        }
        setOnDragEntered { de ->
            println("entered $de")
            if (de.gestureSource != this && de.dragboard.hasFiles()) {
                println("entered: accept...") // TODO do something?
            } // TODO also accept internal files
        }
        setOnDragDropped { de ->
            var success = false
            if (de.dragboard.hasFiles()) {
                println("drop file ${de.dragboard.files} mode=${de.transferMode}")
                // TODO
                success = true
            }
            de.isDropCompleted = success
            de.consume()
        }
        setOnDragDone { de ->
            println("dragdone: ${de.transferMode}, ${de.dragboard}")
            de.dragboard.setContent { putString("newstring") }
            // TODO
        }
    }

    override val root = vbox {
        prefWidth = 800.0
        prefHeight = 600.0
        toolbar {
            button("Refresh").setOnAction { updateBrowser() }
            when (mode) {
                BrowserViewMode.NORMAL -> {
                    button("Add bookmark").setOnAction {
                        server.bookmarks += BrowserBookmark(server, SimpleStringProperty(currentPath.value))
                    }
                    button("Add syncfile").setOnAction {
                        if (fileTableView.selectedItem?.isFile() == true) {
                            server.syncs += Sync(SyncType.FILE, SSP(fileTableView.selectedItem?.getFileName()), SSP("not synced"),
                                    SSP(""), SSP(fileTableView.selectedItem?.getParent()), server = server).apply {
                                localfolder.set(DBSettings.getCacheFolder(cacheid.value))
                            }
                        }
                    }
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
                    action { currentPath.set(it2) }
                })
            }
        }
        taskListLocal.setOnFailed {
            throw taskListLocal.exception
        }
        MyWorker.runTask(taskListLocal)
    }
    init {
        logger.debug("opening browser protobp=${server.getProtocol().baseFolder.value} bp=$basePath p=$path")
        currentPath.onChange { if (it != null) {
            updateBrowser()
        } }
        updateBrowser()
    }
}
