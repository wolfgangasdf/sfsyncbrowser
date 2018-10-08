import javafx.beans.property.SimpleStringProperty
import javafx.scene.control.TableRow
import javafx.util.Callback
import store.Server
import synchro.VirtualFile
import tornadofx.*
import util.Helpers
import util.MyTask
import util.MyWorker


class BrowserView(private val server: Server, path: String) : View("Browser view") {

    private var currentPath = SimpleStringProperty(path)

    private val files = mutableListOf<VirtualFile>().observable()

    private val pathButtonFlowPane = hbox {
        label("Path:")
    }

    override val root = vbox {
        prefWidth = 800.0
        prefHeight = 600.0
        toolbar {
            button("Refresh").setOnAction { println("refresh ${Thread.currentThread().id}") }
        }
        label("browserview")
        hbox {
            button("Refresh") { action {
                updateBrowser()
            } }
        }
        this += pathButtonFlowPane
        label("Files:")
        tableview(files) {
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
        }.smartResize()
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
