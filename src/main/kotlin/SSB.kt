import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.scene.Scene
import javafx.scene.control.TableRow
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeTableView
import javafx.scene.control.cell.TextFieldListCell
import javafx.scene.image.Image
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.stage.Stage
import javafx.util.Callback
import mu.KotlinLogging
import store.*
import tornadofx.*
import java.io.File
import java.util.*

private val logger = KotlinLogging.logger {}


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

    open class SettingsPane: Pane()

    class ServerSettingsPane(server: Server): SettingsPane() {
        init {
            this += vbox {
                this += hbox { label("Server name: ") ; textfield(server.name) }
                this += hbox { label("Server type: ") ; textfield(server.type) }
                this += hbox { label("Status: ") ; label(server.status) }
                this += hbox { label("Protocol URI: ") ; textfield(server.proto.protocoluri) }
                this += checkbox("Protocol set permissions", server.proto.doSetPermissions)
                this += hbox { label("Protocol permissions: ") ; textfield(server.proto.perms) }
                this += checkbox("Protocol don't set date", server.proto.cantSetDate)
                this += button("Add new sync") { action {
                    server.children += Sync(SimpleStringProperty("sytype"), SimpleStringProperty("syname"),
                            SimpleStringProperty("systatus"), SimpleStringProperty("sylocalfolder"))
                } }
            }
        }
    }

    class SyncSettingsPane(sync: Sync): SettingsPane()  {
        init {
            this += vbox {
                this += hbox { label("Sync name: "); textfield(sync.name) }
                this += hbox { label("Sync cacheid: "); label(sync.cacheid) }
                this += hbox { label("type: "); textfield(sync.type) }
                this += hbox { label("Status: "); label(sync.status) }
                this += hbox { label("Local folder: "); textfield(sync.localfolder) }
                this += button("Add new subset") { action {
                    println("syncchilds=${sync.children} ${sync.children::class.java}")
                    sync.children += SubSet(SimpleStringProperty("ssname"),
                    SimpleStringProperty("ssstatus"), SimpleStringProperty("ssexcl"), FXCollections.observableArrayList<String>())
                } }
            }
        }
    }

    class SubsetSettingsPane(subset: SubSet): SettingsPane() {
        init {
            this += vbox {
                this += hbox { label("Subset name: "); textfield(subset.name) }
                this += hbox { label("Exclude filter: "); textfield(subset.excludeFilter) }
                this += button("Add new remote folder") { action {
                    subset.remotefolders += "newrf"
                } }
                this += listview(subset.remotefolders).apply {
                    isEditable = true
                    cellFactory = TextFieldListCell.forListView()
                }
            }
        }
    }

    var settingsview = SettingsPane()

    val ttv = TreeTableView<TtvThing>().apply {
        column("type", TtvThing::type)
        column("name", TtvThing::name)
        column("status", TtvThing::status)
        root = TreeItem<TtvThing>(RootThing(SimpleStringProperty("root"),
                SimpleStringProperty("rootn"), SimpleStringProperty("roots"), Store.servers))
        populate { it.value.children }
        root.isExpanded = true
        isShowRoot = false
        root.children.forEach { it.isExpanded = true ; it.children.forEach { it.isExpanded = true }}
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
                } else if (ti::class == SubSet::class) {
                    val sp = SubsetSettingsPane(ti as SubSet)
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
            runAsync {
                println("compasync: ${Thread.currentThread().id}")
                Thread.sleep(1000)
            } ui {
                files.add(RFile("/aaa/fxxxxxx", 100,false, ""))
            }
        } }


        button("testmytask") { action {
            println("gui: ${Thread.currentThread().id}")
            val taskIni = MyTask<Unit> {
                println("taskini: !!!!!!! ${Thread.currentThread().id}")
                updateTit("Initialize connections...${Thread.currentThread().id}")
                updateProgr(0.0, 100.0, "execute 'before'...")
                //        throw Exception("error executing 'before' command!")
                Thread.sleep(1000)
                updateProgr(50.0, 100.0, "initialize remote connection...")
                Thread.sleep(1000)
                updateProgr(50.0, 100.0, "initialize remote connection...2")
                Thread.sleep(1000)
                updateProgr(100.0, 100.0, "done!")
            }

            taskIni.setOnSucceeded { println("back here: succ!") }
            MyWorker.runTask(taskIni)
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
