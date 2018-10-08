import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ChangeListener
import javafx.collections.FXCollections
import javafx.event.Event
import javafx.scene.control.TreeItem
import javafx.scene.control.cell.TextFieldListCell
import store.Server
import store.SettingsStore
import store.SubSet
import store.Sync
import tornadofx.*
import util.Helpers.valitextfield

class BookmarksView : View() {

    class SettingsViewPlaceholder: View() {
        override val root = Form()
    }

    class ServerSettingsPane(server: Server): View() {
        override val root = Form()

        init {
            with(root) {
                fieldset("Server") {
                    field("Name") { textfield(server.title) }
                    field("Status") { label(server.status) }
                    field("Protocol URI and password") { textfield(server.proto.protocoluri) ; passwordfield(server.proto.password) }
                    // TODO make everything with file selectors, disable editing.
                    field("Protocol basefolder") { valitextfield(server.proto.baseFolder, "(^/$)|(/.*[^/]$)".toRegex(), "/f1/f2 or /") }
                    field("Protocol set permissions") { checkbox("", server.proto.doSetPermissions) ; textfield(server.proto.perms) }
                    field("Protocol don't set date") { checkbox("", server.proto.cantSetDate) }
                    field("Protocol tunnel host") { textfield(server.proto.tunnelHost) }
                    field {
                        button("Add new sync") { action {
                            server.syncs += Sync(SimpleStringProperty("sytype"), SimpleStringProperty("syname"),
                                SimpleStringProperty("systatus"), SimpleStringProperty("sylocalfolder"), server=server)
                        } }
                        button("Open browser") { action {
                            val bv = BrowserView(server, "")
                            openNewWindow(bv)
                        } }

                    }
                }
            }
        }
    }

    class SyncSettingsPane(sync: Sync): View() {
        override val root = Form()
        init {
            with(root) {
                fieldset("Sync") {
                    field("Name and type") { textfield(sync.title) ; textfield(sync.type) }
                    field("Sync cacheid") { textfield(sync.cacheid) }
                    field("Status") { label(sync.status) }
                    field("Local folder") { valitextfield(sync.localfolder, "^/.*[^/]$".toRegex(), "/f1/f2 or /") }
                    field {
                        button("Add new subset") { action {
                            sync.subsets += SubSet(SimpleStringProperty("ssname"),
                                    SimpleStringProperty("ssstatus"), SimpleStringProperty("ssexcl"), FXCollections.observableArrayList<String>(), sync)
                        } }
                        button("Remove sync") { action {
                            sync.server.syncs.remove(sync)
                        } }
                    }
                }
            }
        }
    }

    class SubsetSettingsPane(subset: SubSet): View() {
        override val root = Form()
        init {
            with(root) {
                fieldset("Subset") {
                    field("Name: ") { textfield(subset.title) }
                    field { listview(subset.subfolders).apply {
                        isEditable = true
                        prefHeight = 50.0
                        cellFactory = TextFieldListCell.forListView()
                    } }
                    field("Exclude filter") { textfield(subset.excludeFilter) }
                    field {
                        button("Open sync view!") { action {
                            val sv = SyncView(subset.sync.server, subset.sync, subset)
                            openNewWindow(sv)
                        } }
                        button("Add new remote folder") { action {
                            subset.subfolders += "newrf"
                        } }
                        button("Remove subset") { action {
                            subset.sync.subsets.remove(subset)
                        } }
                    }
                }
            }
        }
    }


    private var settingsview: View = SettingsViewPlaceholder()

    // https://stackoverflow.com/questions/32478383/updating-treeview-items-from-textfield
    // this is better than writing generic type TtvThing, which gets messy!
    private inner class MyTreeItem(ele: Any) : TreeItem<Any>(ele) {
        private val nameListener = ChangeListener<String> { _, _, _ ->
            val event = TreeItem.TreeModificationEvent<Any>(TreeItem.valueChangedEvent<Any>(), this)
            Event.fireEvent(this, event)
        }
        init {
            when (ele) {
                is Server -> ele.title.addListener(nameListener)
                is Sync -> ele.title.addListener(nameListener)
                is SubSet -> ele.title.addListener(nameListener)
            }
            // have to remove listeners? I don't think so.
        }
    }

    private val ttv = treeview<Any> {
        root = TreeItem<Any>("root")

        populate({ ite -> MyTreeItem(ite)}) { parent ->
            val value = parent.value
            when {
                parent == root -> SettingsStore.servers
                value is Server -> value.syncs
                value is Sync -> value.subsets
                else -> null
            }
        }

        root.isExpanded = true
        isShowRoot = false
        root.children.forEach { it.isExpanded = true ; it.children.forEach { it2 -> it2.isExpanded = true }}
        useMaxHeight = true
        useMaxWidth = true
    }

    override val root = vbox {
        prefWidth = 800.0
        prefHeight = 600.0
        label("conns")
        this += ttv
        hbox {
            button("save sett") { action {
                SettingsStore.saveSettings()
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
