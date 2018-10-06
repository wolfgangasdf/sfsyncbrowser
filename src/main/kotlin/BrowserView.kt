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

    class RFile (private val path: String, var size: Long, private val isdirectory: Boolean, var permissions: String) {
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
            button("testasync") { action {
                println("comp: ${Thread.currentThread().id}")
                runAsync {
                    println("compasync: ${Thread.currentThread().id}")
                    Thread.sleep(1000)
                } ui {
                    files.add(RFile("/aaa/fxxxxxx", 100, false, ""))
                }
            } }
            button("Refresh") { action {
                println("refresh ${Thread.currentThread().id}")
                updateBrowser()
            } }
        }
        pathButtonFlowPane
        label("Files:")
        // TODO need cellfactory to color rows!
        tableview(files) {
            column("name", RFile::getName)
            column("size", RFile::size)
            column("perms", RFile::permissions)
        }.rowFactory = Callback {
            val row = TableRow<RFile>()
            println("idx=" + row.index)
            row
        }
    }

    private fun updateBrowser() {
        println("update browser!")

        val taskListLocal = MyTask<MutableList<RFile>> {
            updateTit("Find local file")
            val tmpl = mutableListOf<RFile>()
            server.getConnection().list(currentPath.value, "", false) {
                it2 -> tmpl.add(RFile(it2.path, it2.size, it2.isDir(), "todo"))
            }
            tmpl
        }
        taskListLocal.setOnSucceeded {
            files.clear()
            files.setAll(taskListLocal.value)
            pathButtonFlowPane.children.clear()
            var tmpp = currentPath.value
            while (tmpp != "/") {
                pathButtonFlowPane.add(button(Helpers.getFileName(tmpp)))
                tmpp = Helpers.getParentFolder(tmpp)
            }
        }
        MyWorker.runTask(taskListLocal)
    }
    init {
        currentPath.onChange { if (it != null) {
            println("huhu onchange")
            updateBrowser()
        } }
        updateBrowser()
    }
}
