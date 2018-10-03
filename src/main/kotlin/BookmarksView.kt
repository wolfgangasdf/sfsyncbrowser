import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeTableView
import javafx.scene.control.cell.TextFieldListCell
import javafx.scene.layout.Pane
import store.*
import tornadofx.*
import util.Helpers
import util.MyTask
import util.MyWorker

class BookmarksView : View() {

    open class SettingsPane: Pane()

    class ServerSettingsPane(server: Server): SettingsPane() {
        init {
            this += vbox {
                this += hbox { label("Server name: ") ; textfield(server.name) }
                this += hbox { label("Server type: ") ; textfield(server.type) }
                this += hbox { label("Status: ") ; label(server.status) }
                this += hbox { label("Protocol URI: ") ; textfield(server.proto.protocoluri) }
                this += hbox { label("Protocol basefolder: ") ; textfield(server.proto.baseFolder) }
                this += checkbox("Protocol set permissions", server.proto.doSetPermissions)
                this += hbox { label("Protocol permissions: ") ; textfield(server.proto.perms) }
                this += checkbox("Protocol don't set date", server.proto.cantSetDate)
                this += button("Add new sync") { action {
                    server.children += Sync(SimpleStringProperty("sytype"), SimpleStringProperty("syname"),
                            SimpleStringProperty("systatus"), SimpleStringProperty("sylocalfolder"), server=server)
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
                            SimpleStringProperty("ssstatus"), SimpleStringProperty("ssexcl"), FXCollections.observableArrayList<String>(), sync)
                } }
            }
        }
    }

    class SubsetSettingsPane(subset: SubSet): SettingsPane() {
        init {
            this += vbox {
                this += hbox { label("Subset name: "); textfield(subset.name) }
                this += button("sync...") { action {
                    println("bm: ${Thread.currentThread().id}")
                    val sv = SyncView(subset.sync.server, subset.sync, subset)
                    openNewWindow(sv)
                } }
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

    private var settingsview = SettingsPane()

    private val ttv = TreeTableView<TtvThing>().apply {
        column("type", TtvThing::type)
        column("name", TtvThing::name)
        column("status", TtvThing::status)
        root = TreeItem<TtvThing>(RootThing(SimpleStringProperty("root"),
                SimpleStringProperty("rootn"), SimpleStringProperty("roots"), SettingsStore.servers))
        populate { it.value.children }
        root.isExpanded = true
        isShowRoot = false
        root.children.forEach { it.isExpanded = true ; it.children.forEach { it2 -> it2.isExpanded = true }}
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
            button("save sett") { action {
                SettingsStore.saveSettings()
            } }

            button("testmytask") { action {
                println("gui: ${Thread.currentThread().id}")
                val taskIni = MyTask<Unit> {
                    println("taskini: !!!!!!! ${Thread.currentThread().id}")
                    updateTit("Initialize connections...${Thread.currentThread().id}")
                    updateProgr(0, 100, "execute 'before'...")
                    //        throw Exception("error executing 'before' command!")
                    Thread.sleep(1000)
                    updateProgr(50, 100, "initialize remote connection...")
                    Thread.sleep(1000)
                    updateProgr(50, 100, "initialize remote connection...2")
                    Thread.sleep(1000)

                    println("hereAAAA")
                    val res = Helpers.runUIwait{ Helpers.dialogOkCancel("Warning", "Directory ", "content") }
                    println("hereBBBB $res")

                    updateProgr(100, 100, "done!")
                }

                taskIni.setOnSucceeded { println("back here: succ!") }
                MyWorker.runTask(taskIni)
            } }

        }
    }

    init {
        ttv.selectionModel.selectedItemProperty().onChange {
            println("selch: name=${it?.value?.name?.value} sv.par=$settingsview")
            if (it != null) {
                val ti = it.value
                when(ti) {
                    is Server -> settingsview.children.setAll(ServerSettingsPane(ti))
                    is Sync -> settingsview.children.setAll(SyncSettingsPane(ti))
                    is SubSet -> settingsview.children.setAll(SubsetSettingsPane(ti))
                }
            }

        }
    }
}
