import javafx.beans.property.SimpleStringProperty
import javafx.scene.control.TableRow
import javafx.scene.input.KeyCode
import javafx.scene.input.TransferMode
import javafx.util.Callback
import mu.KotlinLogging
import store.BrowserBookmark
import store.Server
import synchro.VirtualFile
import tornadofx.*
import util.Helpers
import util.MyTask
import util.MyWorker
import java.io.File
import java.nio.file.Files

private val logger = KotlinLogging.logger {}

class BrowserView(private val server: Server, path: String) : View(server.getProtocol().protocoluri.value) {

    private var currentPath = SimpleStringProperty(path)

    private val files = mutableListOf<VirtualFile>().observable()

    private val pathButtonFlowPane = hbox {
        label("Path:")
    }

    // mac quicklook lets "space" to close through... this is good in principle, to allow navigation while preview open. implement?
    private var lastpreviewvf: VirtualFile? = null

    private val fileTableView = tableview(files) {
        column("title", VirtualFile::getFileName).remainingWidth()
        column("size", VirtualFile::size)
        column("perms", VirtualFile::permissions)
    }.apply {
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
                     getFileIntoTempAndDo(selectedItem!!) { Helpers.previewDocument(it) }
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
            button("Add bookmark").setOnAction {
                server.bookmarks += BrowserBookmark(server, SimpleStringProperty(currentPath.value))
            }
        }
        this += pathButtonFlowPane
        label("Files:")
        this += fileTableView
        fileTableView.smartResize()
    }

    private fun getFileIntoTempAndDo(vf: VirtualFile, action: (f: File) -> Unit) {
        val taskListLocal = MyTask<File> {
            updateTit("Downloading file $vf...")
            val rf = Files.createTempFile(vf.getFileName(), ".${vf.getFileExtension()}")
            logger.debug("downloading into ${rf.toFile().absolutePath}...")
            server.getConnection().getfile("", vf.path, vf.modTime, rf.toFile().absolutePath)
            rf.toFile()
        }
        taskListLocal.setOnSucceeded { action(taskListLocal.value) }
        MyWorker.runTask(taskListLocal)
    }

    private fun updateBrowser() {
        val taskListLocal = MyTask<MutableList<VirtualFile>> {
            val tmpl = mutableListOf<VirtualFile>()
            updateTit("Getting remote file list...")
            server.getConnection().list(currentPath.value, "", false) { it2 ->
                if (it2.path != currentPath.value) tmpl.add(it2)
            }
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
            pathButtonFlowPane.add(button("base") { action { currentPath.set("/") }})
            pl.reversed().forEach { it2 ->
                pathButtonFlowPane.add(button(Helpers.getFileName(it2)!!) {
                    action { currentPath.set(it2) }
                })
            }
        }
        MyWorker.runTask(taskListLocal)
    }
    init {
        currentPath.onChange { if (it != null) {
            updateBrowser()
        } }
        updateBrowser()
    }
}
