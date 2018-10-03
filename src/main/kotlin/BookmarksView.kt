import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.scene.control.TreeItem
import javafx.scene.control.cell.TextFieldListCell
import store.*
import tornadofx.*
import util.Helpers
import util.MyTask
import util.MyWorker

class BookmarksView : View() {

    class SettingsViewPlaceholder: View() {
        override val root = Form()
    }

    class ServerSettingsPane(server: Server): View() {
        override val root = Form()

        init {
            with(root) {
                fieldset("Server") {
                    field("Name") { textfield(server.name) }
                    field("Type") { textfield(server.type) }
                    field("Status") { label(server.status) }
                    field("Protocol URI") { textfield(server.proto.protocoluri) }
                    field("Protocol password") { passwordfield(server.proto.password) }
                    field("Protocol basefolder") { textfield(server.proto.baseFolder) }
                    field("Protocol set permissions") { checkbox("", server.proto.doSetPermissions) }
                    field("Protocol permissions") { textfield(server.proto.perms) }
                    field("Protocol don't set date") { checkbox("", server.proto.cantSetDate) }
                    field { button("Add new sync") { action {
                        server.children += Sync(SimpleStringProperty("sytype"), SimpleStringProperty("syname"),
                                SimpleStringProperty("systatus"), SimpleStringProperty("sylocalfolder"), server=server)
                    } } }
                }
            }
        }
    }

    class SyncSettingsPane(sync: Sync): View() {
        override val root = Form()
        init {
            with(root) {
                fieldset("Sync") {
                    field("Name") { textfield(sync.name) }
                    field("Sync cacheid") { textfield(sync.cacheid) }
                    field("Type") { textfield(sync.type) }
                    field("Status") { label(sync.status) }
                    field("Local folder") { textfield(sync.localfolder) }
                    field { button("Add new subset") { action {
                            println("syncchilds=${sync.children} ${sync.children::class.java}")
                            sync.children += SubSet(SimpleStringProperty("ssname"),
                                    SimpleStringProperty("ssstatus"), SimpleStringProperty("ssexcl"), FXCollections.observableArrayList<String>(), sync)
                        } } }
                }
            }
        }
    }

    class SubsetSettingsPane(subset: SubSet): View() {
        override val root = Form()
        init {
            with(root) {
                fieldset("Subset") {
                    field("Name: ") { textfield(subset.name) }
                    field { button("Open sync view!") { action {
                        println("bm: ${Thread.currentThread().id}")
                        val sv = SyncView(subset.sync.server, subset.sync, subset)
                        openNewWindow(sv)
                    } } }
                    field("Exclude filter") { textfield(subset.excludeFilter) }
                    field { button("Add new remote folder") { action {
                        subset.remotefolders += "newrf"
                    } } }
                    field { listview(subset.remotefolders).apply {
                        isEditable = true
                        prefHeight = 50.0
                        cellFactory = TextFieldListCell.forListView()
                    } }

                }
            }
        }
    }


    private var settingsview: View = SettingsViewPlaceholder()

    private val ttv = treetableview<TtvThing> {
        column("type", TtvThing::type)
        column("name", TtvThing::name)
        column("status", TtvThing::status)
        columnResizePolicy = TreeTableSmartResize.POLICY
        root = TreeItem<TtvThing>(RootThing(SimpleStringProperty("root"),
                SimpleStringProperty("rootn"), SimpleStringProperty("roots"), SettingsStore.servers))
        populate { it.value.children }
        root.isExpanded = true
        isShowRoot = false
        root.children.forEach { it.isExpanded = true ; it.children.forEach { it2 -> it2.isExpanded = true }}
        resizeColumnsToFitContent()
        useMaxHeight = true
        useMaxWidth = true
        requestResize()
    }

    override val root = vbox {
        prefWidth = 800.0
        prefHeight = 600.0
        label("conns")
        this += ttv
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
        this += settingsview
    }

    init {
        ttv.selectionModel.selectedItemProperty().onChange {
            if (it != null) {
                val ti = it.value
                settingsview.removeFromParent()
                settingsview = when(ti) {
                    is Server -> ServerSettingsPane(ti)
                    is Sync -> SyncSettingsPane(ti)
                    is SubSet -> SubsetSettingsPane(ti)
                    else -> SettingsViewPlaceholder()
                }
                root += settingsview
            }

        }
    }
}
