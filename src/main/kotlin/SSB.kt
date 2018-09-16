import javafx.scene.Scene
import javafx.scene.control.TableRow
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeTableView
import javafx.scene.image.Image
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.stage.Stage
import javafx.util.Callback
import store.ServerEntry
import store.Store
import store.Sync
import store.TtvThing
import tornadofx.*
import java.io.File
import java.util.*


object Global {
    var string = ""
}

class Styles : Stylesheet() {

    init {
        button {
            and(hover) {
                backgroundColor += Color.RED
            }
        }
        cell {
            and(selected) {
                backgroundColor += Color.RED
            }
        }
    }
}

fun openNewWindow(view: UIComponent) {
    val newstage = Stage()
    newstage.scene = Scene(view.root)
    newstage.show()
}

class BookmarksView : View() {

    open class SettingsPane(val name: String): Pane()

    class ServerSettingsPane(name: String): SettingsPane(name)  {
        init { this += label("settserv $name")}
    }
    class SyncSettingsPane(name: String): SettingsPane(name)  {
        init { this += label("settsync $name")}
    }

    var settingsview = SettingsPane("asdf")

    val ttv = TreeTableView<TtvThing>().apply {
        column("type", TtvThing::type)
        column("name", TtvThing::name)
        column("status", TtvThing::status)
        // TODO add buttons for "sync"
        root = TreeItem<TtvThing>(TtvThing("root", "rootn", "", Store.servers))
        populate { it.value.children }
        root.isExpanded = true
        isShowRoot = false
        root.children.forEach { it.isExpanded = true }
        resizeColumnsToFitContent()
        useMaxHeight = true
        useMaxWidth = true
    }

    override val root = vbox {
        prefWidth = 800.0
        prefHeight = 600.0
        label("conns")
        this += ttv
        this += settingsview
        hbox {
            button("open browser") { action {
                println("browser")
                val bv = BrowserView()
                openNewWindow(bv)
            } }
            button("compare&sync") { action {
                println("bm: ${Thread.currentThread().id}")
                val sv = SyncView()
                openNewWindow(sv)
            } }
            button("testlistchange") { action {
                val sel = ttv.selectedItem
                val sv = SyncView()
                openNewWindow(sv)
            } }
            button("save sett") { action {
                Store.saveSettings()
//                println("XXXXX to json: " + Klaxon().toJsonString(servers))

//                val mapper = jacksonObjectMapper()
//                mapper.enableDefaultTyping()
//                val json = mapper.writeValueAsString(Store.servers)
//                println("XXXX " + json)
//                val res = mapper.readValue(json, Array<ServerEntry>::class.java)
//                println("YYY: " + res)

            } }
        }
    }

    init {
        ttv.onUserSelect(1) { it ->
            println("click: name=${it.name} ${it::class} sv.par=${settingsview}")
            if (it::class == ServerEntry::class) {
                val sp = ServerSettingsPane(it.name)
                settingsview.children.setAll(sp)
            } else if (it::class == Sync::class) {
                val sp = SyncSettingsPane(it.name)
                settingsview.children.setAll(sp)
            }
        }

    }
}

class BrowserView : View("Browser view") {
    val id = UUID.randomUUID()

    class RFile (val path: String, var size: Long, val isdirectory: Boolean, var permissions: String) {
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
        } }
        label("Files:")
        // TODO need cellfactory to color rows!
        tableview(files) {
            column("name", RFile::getName)
            column("size", RFile::size)
            column("perms", RFile::permissions)
        }.rowFactory = Callback { tbl ->
            val row = TableRow<RFile>()
            println("idx=" + row.index)
            row
        }
    }
}

class SyncView : View("Sync view") {
    val id = UUID.randomUUID()
    override val root = vbox {
        prefWidth = 800.0
        prefHeight = 600.0
        toolbar {
            button("Refresh").setOnAction { println("refresh ${Thread.currentThread().id}") }
        }
        label("syncview")
        button("done") { action {
            println("done: ${Thread.currentThread().id}")
            close()
        } }
    }
}



//class SSBApp : App(BookmarksView::class, Styles::class) {
class SSBApp : App(Workspace::class, Styles::class) { // TODO remove workspace or leave to wait for improvements?

//    override val primaryView = DemoTableView::class

    override fun onBeforeShow(view: UIComponent) {
        workspace.dock<BookmarksView>()
    }

    init {
        with(primaryView) {
            addStageIcon(Image(resources["/icons/icon_16x16.png"]))
            addStageIcon(Image(resources["/icons/icon_32x32.png"]))
            addStageIcon(Image(resources["/icons/icon_256x256.png"]))
        }
//        setStageIcon()
        initit()


    }

}



fun initit() {
    Checks.checkComparedFile()
//    Store.dumpConfig()
}
