import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.value.ChangeListener
import javafx.event.Event
import javafx.geometry.Pos
import javafx.scene.control.Alert
import javafx.scene.control.TreeItem
import javafx.scene.control.cell.TextFieldListCell
import javafx.stage.Modality
import mu.KotlinLogging
import store.*
import tornadofx.*
import util.Helpers.absPathRegex
import util.Helpers.chooseDirectoryRel
import util.Helpers.concatObsLists
import util.Helpers.dialogMessage
import util.Helpers.permissionsRegex
import util.Helpers.relPathRegex
import util.Helpers.revealFile
import util.Helpers.valitextfield
import util.SSP
import java.io.File

private val logger = KotlinLogging.logger {}

class Styles : Stylesheet() {
    companion object {
        val thinbutton by cssclass()
    }

    init {
        thinbutton {
            fontSize = 0.8.em
            padding = box(2.0.px)
        }
        logger.info("Loaded stylesheet!")
    }
}

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
                    field("Protocol") { combobox<Protocol>(server.proto, server.protocols) }
                    field {
                        button("Add new protocol") { action {
                            server.protocols += Protocol(server, SSP("sftp:user@//"), SimpleBooleanProperty(false),
                                    SSP(""), SimpleBooleanProperty(false),
                                    SSP(""), SSP(""), SSP(""), SSP(SettingsStore.tunnelModes[0]))
                        } }
                        button("Add new sync") { action {
                            server.syncs += Sync(SSP("sytype"), SSP("syname"),
                                SSP(""), SSP("sylocalfolder"),
                                SSP("syremotefolder"), server=server)
                        } }
                        button("Open browser") { action {
                            openNewWindow(BrowserView(server, "", ""))
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
                    field("URI and password") {
                        textfield(proto.protocoluri) { tooltip("'sftp://user@host:port' or 'file:///") }
                        passwordfield(proto.password) { tooltip("Leave empty for public key authentification")}
                    }
                    field("Remote basefolder") { valitextfield(proto.baseFolder, absPathRegex, "Absolute path like '/folder'") { } }
                    field("Set permissions") {
                        checkbox("", proto.doSetPermissions) { tooltip("Set permissions on files/directories on remote server?") }
                        valitextfield(proto.perms, permissionsRegex, "Regex: $permissionsRegex") { tooltip("Remote permissions to be applied after uploading files/directories, like 'g+w,o+r'") }
                    }
                    field("Don't set date") { checkbox("", proto.cantSetDate) {
                        tooltip("E.g., on un-rooted Android devices I can't set the file date via sftp,\nselect this and I will keep track of actual remote times.")
                    } }
                    field("Tunnel host") {
                        textfield(proto.tunnelHost) { tooltip("Enter tunnel host:port from which the sftp server is reachable") }
                        combobox(proto.tunnelMode, SettingsStore.tunnelModes) { tooltip("Choose if tunnel shall be used, or auto (tries to ping sftp host)") }
                    }
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
                    field("Path") { valitextfield(bookmark.path, relPathRegex, "relative!") }
                    field {
                        button("Open") { action {
                            openNewWindow(BrowserView(bookmark.server, "", bookmark.path.value))
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
                    field("Cacheid") {
                        textfield(sync.cacheid)
                        button("Delete cache!") { tooltip("Clear the cache database for this sync") } .setOnAction {
                            DBSettings.clearCacheFile(sync.cacheid.value)
                        }
                    }
                    field("Local folder") {
                        valitextfield(sync.localfolder, absPathRegex, "absolute!") { tooltip("Local base folder such as '/localdir'") }
                        button("Choose...").setOnAction {
                            val dir = chooseDirectory("Select local folder")
                            if (dir != null) if (dir.isDirectory) {
                                sync.localfolder.set(if (dir.absolutePath.endsWith("/")) dir.absolutePath else dir.absolutePath + "/")
                            }
                        }
                        button("Reveal").setOnAction { revealFile(File(sync.localfolder.value)) }
                    }

                    field("Remote folder") {
                        valitextfield(sync.remoteFolder, relPathRegex, "relative!") {
                            tooltip("Remote base directory below protocol remote base folder such as 'remotebasedir/sub")
                        }
                        button("Choose...").setOnAction {
                            val bv = openNewWindow(BrowserView(sync.server, "", "", BrowserViewMode.SELECTFOLDER), Modality.APPLICATION_MODAL)
                            bv.selectFolderCallback = { it2 ->
                                sync.remoteFolder.set(it2.path)
                            }
                        }
                    }
                    field {
                        button("Add new subset") { action {
                            sync.subsets += SubSet(SSP("ssname"),
                                    SSP(""), SSP("ssexcl"), sync = sync)
                        } }
                        button("Add <all> subset") { action {
                            val ss = SubSet(SSP("all"), SSP(""), SSP(""), sync = sync)
                            ss.subfolders += ""
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
        companion object {
            fun compSync(subset: SubSet) {
                val sv = SyncView(subset.sync.server, subset.sync, subset)
                openNewWindow(sv)
                sv.runCompare()

            }
        }
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
                            compSync(subset)
                        } }
                        button("Add new folder (remote)") { action {
                            val bv = openNewWindow(BrowserView(subset.sync.server, subset.sync.remoteFolder.value, "", BrowserViewMode.SELECTFOLDER), Modality.APPLICATION_MODAL)
                            bv.selectFolderCallback = {
                                subset.subfolders += it.path
                            }
                        } }
                        button("Add new folder (local)") { action {
                            val dir = chooseDirectoryRel("Select local folder", File(subset.sync.localfolder.value))
                            if (dir != null) subset.subfolders += dir.path + "/"
                        } }
                        button("Remove selected folder") { action {
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
        cellFormat { tit ->
            graphic = hbox(20, Pos.CENTER_LEFT) {
                label(tit.toString()) {
                    isEditable = false
                }
                when (tit) {
                    is Server -> { button("Open browser") { addClass(Styles.thinbutton) }.setOnAction {
                            openNewWindow(BrowserView(tit, "", ""))
                        }
                        label(tit.status)
                    }

                    is BrowserBookmark -> button("Open browser") { addClass(Styles.thinbutton) }.setOnAction {
                        openNewWindow(BrowserView(tit.server, "", tit.path.value))
                    }
                    is Sync -> {
                        button("Compare & sync all") { addClass(Styles.thinbutton) }.setOnAction {
                            val all = tit.subsets.filter { it2 -> it2.title.value == "all" }
                            if (all.size == 1) {
                                SubsetSettingsPane.compSync(all.first())
                            } else dialogMessage(Alert.AlertType.ERROR, "Error", "You must have exactly one \"all\" subset!", "")
                        }
                        label(tit.status)
                    }
                    is SubSet -> {
                        button("Compare & sync") { addClass(Styles.thinbutton) }.setOnAction {
                            SubsetSettingsPane.compSync(tit)
                        }
                        label(tit.status)
                    }
                }

            }
        }
        root.isExpanded = true
        isShowRoot = false
        root.children.forEach { it.isExpanded = true ; it.children.forEach { it2 -> it2.isExpanded = true }}
        prefHeight = 350.0
        useMaxWidth = true
    }

    override val root = vbox {
        prefWidth = 800.0
        label("conns")
        this += ttv
        hbox {
            button("Add server") { action {
                SettingsStore.servers += Server(SSP("name"), SSP("status"),
                        SimpleIntegerProperty(-1))
            } }
            button("save sett") { action {
                SettingsStore.saveSettings()
            } }
        }
        this += settingsview
    }

    init {
        logger.info("Initialize MainView...")
        ttv.setOnMouseClicked { me ->
            val src = ttv.selectedValue
            if (src is BrowserBookmark) {
                if (me.clickCount == 2) {
                    openNewWindow(BrowserView(src.server, "", src.path.value))
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
                FX.primaryStage.sizeToScene() // rescale stage to show full scene
            }
        }
    }
}
