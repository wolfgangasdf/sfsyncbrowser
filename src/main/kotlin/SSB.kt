import javafx.beans.property.SimpleStringProperty
import javafx.scene.Scene
import javafx.scene.control.TableRow
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeTableView
import javafx.scene.image.Image
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.stage.Stage
import javafx.util.Callback
import store.*
import tornadofx.*
import java.io.File
import java.util.*


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

    class ServerSettingsPane(server: Server): SettingsPane(server.name.value)  {
        init {
            this += hbox { label("Server name: ") ; textfield(server.name) }
            this += hbox { label("Server name: ") ; textfield(server.name) }
            // class Server(override val type: StringProperty, override val name: StringProperty, override val status: StringProperty, val proto: ObjectProperty<Protocol>,
            //             override val children: ObservableList<Sync>): TtvThing
        }
    }
    class SyncSettingsPane(sync: Sync): SettingsPane(sync.name.value)  {
        init {
            this += label("settsync $name")
        }
    }

    var settingsview = SettingsPane("asdf")

    val ttv = TreeTableView<TtvThing>().apply {
        column("type", TtvThing::type)
        column("name", TtvThing::name)
        column("status", TtvThing::status)
        // TODO add buttons for "sync"
        root = TreeItem<TtvThing>(RootThing(SimpleStringProperty("root"),
                SimpleStringProperty("rootn"), SimpleStringProperty("roots"), Store.servers))
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
//                val res = mapper.readValue(json, Array<Server>::class.java)
//                println("YYY: " + res)

            } }
        }
    }

    init {
        ttv.selectionModel.selectedItemProperty().onChange {
            println("selch: name=${it?.value?.name?.value} sv.par=${settingsview}")
            if (it != null) {
                val ti = it.value
                if (ti::class == Server::class) {
                    val sp = ServerSettingsPane(ti as Server)
                    settingsview.children.setAll(sp)
                } else if (ti::class == Sync::class) {
                    val sp = SyncSettingsPane(ti as Sync)
                    settingsview.children.setAll(sp)
                }
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
    Store
}
