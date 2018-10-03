import javafx.scene.control.TableRow
import javafx.util.Callback
import tornadofx.*
import java.io.File

class BrowserView : View("Browser view") {

    private class RFile (val path: String, var size: Long, val isdirectory: Boolean, var permissions: String) {
        fun getName(): String = File(path).name
        override fun toString(): String {
            return "[rf: p=$path isd=$isdirectory"
        }
    }
    private val files = listOf(
            RFile("/aaa/f1", 100,false, ""),
            RFile("/aaa/d1", 100,true, ""),
            RFile("/aaa/f2", 100,false, ""),
            RFile("/aaa/f3", 100,false, ""),
            RFile("/aaa/f4", 100,false, "")
    ).toMutableList().observable()


    override val root = vbox {
        prefWidth = 800.0
        prefHeight = 600.0
        toolbar {
            button("Refresh").setOnAction { println("refresh ${Thread.currentThread().id}") }
        }
        label("browserview")
        button("compare&sync") { action {
            println("comp: ${Thread.currentThread().id}")
            runAsync {
                println("compasync: ${Thread.currentThread().id}")
                Thread.sleep(1000)
            } ui {
                files.add(RFile("/aaa/fxxxxxx", 100,false, ""))
            }
        } }


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
}