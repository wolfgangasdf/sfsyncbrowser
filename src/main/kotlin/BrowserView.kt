import javafx.beans.property.SimpleStringProperty
import javafx.scene.control.TableRow
import javafx.util.Callback
import store.Server
import tornadofx.*
import util.Helpers
import util.MyTask
import util.MyWorker
import java.io.File


class BrowserView(private val server: Server, path: String) : View("Browser view") {

    private var currentPath = SimpleStringProperty(path)

    class RFile (val path: String, var size: Long, val isdirectory: Boolean, var permissions: String) {
        fun getName(): String = File(path).name
        override fun toString(): String {
            return "[rf: p=$path isd=$isdirectory"
        }
    }

    private val files = mutableListOf<RFile>().observable()

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
        label(currentPath)
        this += pathButtonFlowPane
        label("Files:")
        tableview(files) {
            column("title", RFile::getName).remainingWidth()
            column("size", RFile::size)
            column("perms", RFile::permissions)
        }.apply {
            rowFactory = Callback {
                val row = TableRow<RFile>()
                row.setOnMouseClicked { it2 ->
                    if (it2.clickCount == 2 && row.item.isdirectory) {
                        currentPath.set(row.item.path)
                    }
                }
                row
            }
        }.smartResize()
    }

    private fun updateBrowser() {
        val taskListLocal = MyTask<MutableList<RFile>> {
            val tmpl = mutableListOf<RFile>()
            server.getConnection().list(currentPath.value, "", false) { it2 ->
                with(it2.path.removePrefix("/")) {
                    if (this != currentPath.value) tmpl.add(RFile(this, it2.size, it2.isDir(), "todo"))
                }
            }
            tmpl
        }
        taskListLocal.setOnSucceeded {
            files.clear()
            files.setAll(taskListLocal.value)
            pathButtonFlowPane.children.clear()
            pathButtonFlowPane.add(label("Parents: "))
            var tmpp = currentPath.value
            val pl = mutableListOf<String>()
            while (tmpp != "/") {
                tmpp = Helpers.getParentFolder(tmpp)
                pl += tmpp.removeSuffix("/")
            }
            pl.reversed().forEach { it2 ->
                pathButtonFlowPane.add(button(Helpers.getFileName(it2)?:"remroot") {
                    action {
                    currentPath.set(it2)
                }})
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
