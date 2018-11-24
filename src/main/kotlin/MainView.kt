import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ChangeListener
import javafx.event.Event
import javafx.scene.control.TreeItem
import javafx.scene.control.cell.TextFieldListCell
import javafx.stage.Modality
import store.*
import tornadofx.*
import util.Helpers.concatObsLists
import util.Helpers.valitextfield

class MainView : View("SSyncBrowser") {

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
                    field("Protocol") { combobox<Protocol>(server.proto, server.protocols) }
                    field {
                        button("Add new protocol") { action {
                            server.protocols += Protocol(server, SimpleStringProperty("sftp:user@//"), SimpleBooleanProperty(false),
                                    SimpleStringProperty(""), SimpleBooleanProperty(false),
                                    SimpleStringProperty(""), SimpleStringProperty(""), SimpleStringProperty(""), SimpleStringProperty(SettingsStore.tunnelModes[0]))
                        } }
                        button("Add new sync") { action {
                            server.syncs += Sync(SimpleStringProperty("sytype"), SimpleStringProperty("syname"),
                                SimpleStringProperty("systatus"), SimpleStringProperty("sylocalfolder"), server=server)
                        } }
                        button("Open browser") { action {
                            openNewWindow(BrowserView(server, ""))
                        } }
                        button("Remove server") { action {
                            SettingsStore.servers.remove(server)
                        } }
                        button("Close connection") { action {
                            server.closeConnection()
                        } }
                    }
                }
            }
        }
    }

    class ProtocolSettingsPane(proto: Protocol): View() {
        override val root = Form()
        init {
            with(root) {
                fieldset("Protocol") {
                    field("URI and password") { textfield(proto.protocoluri) ; passwordfield(proto.password) }
                    field("Basefolder") { valitextfield(proto.baseFolder, "(^/$)|(/.*[^/]$)".toRegex(), "/f1/f2 or /") }
                    field("Set permissions") { checkbox("", proto.doSetPermissions) ; textfield(proto.perms) }
                    field("Don't set date") { checkbox("", proto.cantSetDate) }
                    field("Tunnel host") { textfield(proto.tunnelHost) ; combobox(proto.tunnelMode, SettingsStore.tunnelModes) }
                    println("tmode=${proto.tunnelMode.value}")
                    field {
                        button("Remove protocol") { action {
                            proto.server.protocols.remove(proto)
                        } }

                    }
                }
            }
        }
    }

    class BookmarkSettingsPane(bookmark: BrowserBookmark): View() {
        override val root = Form()
        init {
            with(root) {
                fieldset("Bookmark") {
                    field("Path") { textfield(bookmark.path) }
                    field {
                        button("Open") { action {
                            openNewWindow(BrowserView(bookmark.server, bookmark.path.value))
                        } }
                        button("Remove bookmark") { action {
                            bookmark.server.bookmarks.remove(bookmark)
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
                                    SimpleStringProperty("ssstatus"), SimpleStringProperty("ssexcl"), sync = sync)
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
            val lvFolders = listview(subset.subfolders).apply {
                isEditable = true
                prefHeight = 150.0
                cellFactory = TextFieldListCell.forListView()
            }
            with(root) {
                fieldset("Subset") {
                    field("Name: ") { textfield(subset.title) }
                    field { this += lvFolders }
                    field("Exclude filter") { textfield(subset.excludeFilter) }
                    field {
                        button("Compare & Sync!") { action {
                            val sv = SyncView(subset.sync.server, subset.sync, subset)
                            openNewWindow(sv)
                        } }
                        button("Add new remote folder") { action {
                            val bv = openNewWindow(BrowserView(subset.sync.server, "", BrowserViewMode.SELECTFOLDER), Modality.APPLICATION_MODAL)
                            bv.selectFolderCallback = {
                                subset.subfolders += it.path
                            }
                        } }
                        button("Remove selected remote folders") { action {
                            if (lvFolders.selectedItem != null) subset.subfolders.remove(lvFolders.selectedItem)
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

    // annoying that needed: https://stackoverflow.com/questions/32478383/updating-treeview-items-from-textfield
    // this is better than writing generic type TtvThing, which gets messy!
    private inner class MyTreeItem(ele: Any) : TreeItem<Any>(ele) {
        private val changeListener = ChangeListener<String> { _, _, _ ->
            Event.fireEvent(this, TreeItem.TreeModificationEvent<Any>(TreeItem.valueChangedEvent<Any>(), this))
        }
        init {
            when (ele) {
                is Server -> ele.title.addListener(changeListener)
                is Protocol -> ele.protocoluri.addListener(changeListener)
                is BrowserBookmark -> ele.path.addListener(changeListener)
                is Sync -> ele.title.addListener(changeListener)
                is SubSet -> ele.title.addListener(changeListener)
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
                value is Server -> concatObsLists(value.protocols, value.syncs, value.bookmarks)
                value is Sync -> value.subsets.sorted()
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
            button("Add server") { action {
                SettingsStore.servers += Server(SimpleStringProperty("name"), SimpleStringProperty("status"),
                        SimpleIntegerProperty(-1))
            } }
            button("save sett") { action {
                SettingsStore.saveSettings()
            } }
        }
        this += settingsview
    }

    init {
        ttv.setOnMouseClicked { me ->
            val src = ttv.selectedValue
            if (src is BrowserBookmark) {
                if (me.clickCount == 2) {
                    openNewWindow(BrowserView(src.server, src.path.value))
                    me.consume()
                }
            }
        }
        ttv.selectionModel.selectedItemProperty().onChange {
            if (it != null) {
                val ti = it.value
                settingsview.removeFromParent()
                settingsview = when(ti) {
                    is Server -> ServerSettingsPane(ti)
                    is Protocol -> ProtocolSettingsPane(ti)
                    is BrowserBookmark -> BookmarkSettingsPane(ti)
                    is Sync -> SyncSettingsPane(ti)
                    is SubSet -> SubsetSettingsPane(ti)
                    else -> SettingsViewPlaceholder()
                }
                root += settingsview
            }

        }
    }
}
