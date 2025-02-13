import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ChangeListener
import javafx.event.Event
import javafx.geometry.Pos
import javafx.scene.control.TreeItem
import javafx.scene.control.cell.TextFieldListCell
import javafx.scene.layout.Priority
import javafx.scene.paint.Color
import javafx.stage.FileChooser
import javafx.stage.Modality
import javafx.stage.Screen
import mu.KotlinLogging
import store.*
import tornadofx.*
import util.*
import util.Helpers.absPathRegex
import util.Helpers.chooseDirectoryRel
import util.Helpers.concatObsLists
import util.Helpers.hostPortNothingRegex
import util.Helpers.permissionsRegex
import util.Helpers.relPathRegex
import util.Helpers.revealFile
import util.Helpers.uriRegex
import util.Helpers.valitextfield

private val logger = KotlinLogging.logger {}

private const val ttPerms = "Remote permissions to be applied after uploading files/directories, like 'g+w,o+r'"

class Styles : Stylesheet() {
    companion object {
        val thinbutton by cssclass()
    }

    init {
        thinbutton {
            fontSize = 0.8.em
            padding = box(1.5.px)
            backgroundColor += Color.WHITE
            borderColor += box(Color.GRAY)
        }
        logger.info("Loaded stylesheet!")
    }
}

class MainView : View("SFSyncBrowser") {

    class SettingsViewPlaceholder: View() {
        override val root = Form()
    }

    class AboutView: MyView() {
        override val root = Form()
        init {
            with(root) {
                prefWidth = 600.0
                fieldset("About") {
                    field("Build time") { textfield(Helpers.getClassBuildTime().toString()) { isDisable = true } }
                    button("Open homepage...").setOnAction {
                        Helpers.openURL("https://github.com/wolfgangasdf/sfsyncbrowser")
                    }
                    DBSettings.logFile?.let {f ->
                        field("Log file") {
                            textfield(f.internalPath) { isDisable = true }
                            button("Open log file").setOnAction { Helpers.openFile(f) }
                        }
                    }
                }
            }
        }
    }

    class SettingsView: MyView() {
        override val root = Form()
        init {
            val ef = when {
                Helpers.isWin() -> FileChooser.ExtensionFilter("Applications (*.exe)", "*.exe")
                Helpers.isMac() -> FileChooser.ExtensionFilter("Applications (*.app)", "*.app")
                else -> FileChooser.ExtensionFilter("Applications (*)", "*")
            }
            with(root) {
                prefWidth = 600.0
                fieldset("Editor") {
                    field("Editor path") {
                        textfield(SettingsStore.ssbSettings.editor) { tooltip("Full path to external editor program") }
                        button("Choose...").setOnAction {
                            chooseFile("Select editor program", arrayOf(ef)).asMFile().firstOrNull()?.let {
                                SettingsStore.ssbSettings.editor.set(it.internalPath)
                            }
                        }
                    }
                }
                fieldset("On exit") {
                    checkbox("Automatically remove file syncs", SettingsStore.ssbSettings.onExitRemoveFilesyncs)
                }
            }
        }
    }

    inner class ServerSettingsPane(server: Server): View() {
        override val root = Form()
        init {
            with(root) {
                fieldset("Server") {
                    field("Name") { textfield(server.title) }
                    field("Protocol") {
                        combobox<Protocol>(server.protoUI, server.protocols)
                        button("Edit") { action {
                            if (server.protoUI.value != null) openNewWindow(ProtocolView(server.protoUI.value), Modality.APPLICATION_MODAL)
                        }}
                        button("Add") { action {
                            Protocol(server, SimpleStringProperty("<name>"), SimpleStringProperty("sftp://user@server"),
                                SimpleBooleanProperty(false), SimpleStringProperty(""), SimpleBooleanProperty(false),
                                    SimpleStringProperty("/"), SimpleStringProperty(""), SimpleStringProperty(""), SimpleStringProperty(SettingsStore.tunnelModes[0])).let {
                                server.protocols += it
                                server.protoUI.set(it)
                            }
                        } }
                        button("Remove") { action {
                            server.protocols.remove(server.protoUI.value)
                            server.protoUI.set(server.protocols.firstOrNull())
                        } }
                    }
                    field {
                        button("Add new sync") { action {
                            Sync(SyncType.NORMAL, SimpleStringProperty("syname"),
                                SimpleStringProperty(""), SimpleStringProperty("sylocalfolder"),
                                SimpleStringProperty("syremotefolder"), server=server).let {
                                server.syncs += it
                                selectItem(it)
                            }
                        } }
                        button("Open browser") { action {
                            openNewWindow(BrowserView(server, "", ""))
                        } }
                        button("Remove server") { action {
                            if (server.syncs.isNotEmpty()) {
                                error("Remove server", "Syncs not empty, remove them first!")
                            } else {
                                confirm("Remove server", "Really remove server $server?") {
                                    SettingsStore.servers.remove(server)
                                }
                            }
                        } }
                        button("Close connection") { action {
                            server.closeConnection()
                        } }
                    }
                }
            }
        }
    }

    class ProtocolView(private val proto: Protocol): MyView() {
        override val root = Form()
        private val oldidx: Int = proto.server.currentProtocol.value
        override fun doBeforeClose() {
            proto.server.protocols[oldidx] = proto
            proto.server.protoUI.set(proto)
        }
        init {
            with(root) {
                prefWidth = 600.0
                fieldset("Protocol") {
                    field("Name") { textfield(proto.name) }
                    field("URI and password") {
                        valitextfield(proto.protocoluri, uriRegex, "Regex: $uriRegex") { tooltip("'sftp://user@host[:port]' or 'file://'") }
                        passwordfield(proto.password) { tooltip("Leave empty for public key authentification")}
                    }
                    field("Remote basefolder") { valitextfield(proto.baseFolder, absPathRegex, "Absolute path like '/folder/'") { } }
                    field("Set permissions") {
                        checkbox("", proto.doSetPermissions) { tooltip("Set permissions on files/directories on remote server?") }
                        valitextfield(proto.perms, permissionsRegex, "Regex: $permissionsRegex") { tooltip(ttPerms) }
                    }
                    field("Don't set date") { checkbox("", proto.cantSetDate) {
                        tooltip("E.g., on un-rooted Android devices I can't set the file date via sftp,\nselect this and I will keep track of actual remote times.")
                    } }
                    field("Tunnel host") {
                        valitextfield(proto.tunnelHost, hostPortNothingRegex, "Regex: $hostPortNothingRegex") { tooltip("Enter tunnel host[:port] from which the sftp server is reachable") }
                        combobox(proto.tunnelMode, SettingsStore.tunnelModes) { tooltip("Choose if tunnel shall be used, or auto (tries to ping sftp host)") }
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

    inner class SyncSettingsPane(sync: Sync): View() {
        override val root = Form()
        init {
            with(root) {
                if (sync.type in setOf(SyncType.NORMAL, SyncType.TEMP)) fieldset("Sync") {
                    field("Name and type") {
                        textfield(sync.title)
                        label(sync.type.name)
                        checkbox("Auto", sync.auto).apply { isDisable = true }
                        checkbox("Disable full sync", sync.disableFullSync)
                        label("Perms ")
                        valitextfield(sync.permsOverride, permissionsRegex, "Regex: $permissionsRegex") { tooltip("Override permissions from protocol") }
                    }
                    field("Cacheid") {
                        textfield(sync.cacheid) { isEditable = false }
                        button("Delete cache!") { tooltip("Clear the cache database for this sync") } .setOnAction {
                            DBSettings.removeCacheFile(sync.cacheid.value)
                        }
                    }
                    field("Local folder") {
                        valitextfield(sync.localfolder, absPathRegex, "absolute!") { tooltip("Local base folder such as '/localdir'") }
                        button("Choose...").setOnAction {
                            val dir = chooseDirectory("Select local folder")?.asMFile()
                            if (dir != null) if (dir.isDirectory()) {
                                sync.localfolder.set(if (dir.internalPath.endsWith("/")) dir.internalPath else dir.internalPath + "/")
                            }
                        }
                        button("Reveal").setOnAction { revealFile(MFile(sync.localfolder.value)) }
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
                    field("Exclude filter") {
                        textfield(sync.excludeFilter) { tooltip("Regular expression on full path like (.*\\.DS_Store)|(.*\\._.*)") }
                        button("Reset to OS default").setOnAction {
                            sync.excludeFilter.set( Helpers.defaultOSexcludeFilter() )
                        }
                    }
                    field {
                        button("Add new subset") { action {
                            SubSet(SimpleStringProperty("ssname"), SimpleStringProperty(""), sync = sync).let {
                                sync.subsets += it
                                selectItem(it)
                            }
                        } }
                        button("Remove sync") { action {
                            confirm("Remove sync", "Really remove sync [$sync] of server [${sync.server}]?\nAll local temporary files are removed!") {
                                sync.server.removeSync(sync)
                            }
                        } }
                    }
                } else fieldset("File sync") {
                    field("File path") { label(sync.title) ; checkbox("Auto", sync.auto).apply { isDisable = true } }
                    field("Cacheid") {
                        textfield(sync.cacheid) { isEditable = false }
                    }
                    field("Local folder") {
                        label(sync.localfolder)
                        button("Reveal").setOnAction { revealFile(MFile(sync.localfolder.value), true) }
                    }
                    field("Remote folder") {
                        label(sync.remoteFolder)
                    }
                    field {
                        button("Remove sync") { action {
                            confirm("Remove sync", "Really remove file sync [$sync] of server [${sync.server}]?\nAll local temporary files are removed!") {
                                sync.server.removeSync(sync)
                            }
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
                    field {
                        button("Compare & Sync!") { action {
                            compSync(subset)
                        } }
                        button("Add folder(s) (remote)") { action {
                            val bv = openNewWindow(BrowserView(subset.sync.server, subset.sync.remoteFolder.value, lvFolders.selectedItem ?: "", BrowserViewMode.SELECTFOLDERS), Modality.APPLICATION_MODAL)
                            bv.selectFoldersCallback = {
                                it.forEach { vf -> subset.subfolders += vf.path }
                            }
                        } }
                        button("Add folder (local)") { action {
                            val dir = chooseDirectoryRel("Select local folder",
                                MFile(lvFolders.selectedItem?.let { subset.sync.localfolder.value + it } ?: subset.sync.localfolder.value)
                            )
                            if (dir != null) subset.subfolders += "$dir/"
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
        private val changeListener = ChangeListener<Any> { _, _, _ ->
            Event.fireEvent(this, TreeModificationEvent(valueChangedEvent<Any>(), this))
        }
        init {
            when (ele) {
                is Server -> ele.title.addListener(changeListener)
                is Protocol -> ele.protocoluri.addListener(changeListener)
                is BrowserBookmark -> ele.path.addListener(changeListener)
                is Sync -> { ele.title.addListener(changeListener) ; ele.disableFullSync.addListener(changeListener) }
                is SubSet -> ele.title.addListener(changeListener)
            }
            // have to remove listeners? I don't think so.
        }
    }

    private fun selectItem(item: Any) {
        getTreeViewItem(item)?.let { ttv.selectionModel.select(it) }
    }
    private fun getTreeViewItem(value: Any, item: TreeItem<Any>? = ttv.root): TreeItem<Any>? {
        if (item != null && item.value == value)
            return item
        for (child in item!!.children) {
            val s = getTreeViewItem(value, child)
            if (s != null)
                return s
        }
        return null
    }

    private val ttv = treeview<Any> {
        root = TreeItem("root")
        populate({ ite -> MyTreeItem(ite)}) { parent ->
            val value = parent.value
            when {
                parent == root -> SettingsStore.servers
                value is Server -> concatObsLists(value.syncs, value.bookmarks)
                value is Sync -> value.subsets
                else -> null
            }
        }
        cellFormat { what ->
            graphic = hbox(20, Pos.CENTER_LEFT) {
                label(what.toString()) {
                    isEditable = false
                }
                when (what) {
                    is Server -> {
                        style(true) { backgroundColor += c("#6B6B6B", .5) }
                        button("Open browser") { addClass(Styles.thinbutton) }.setOnAction {
                            openNewWindow(BrowserView(what, "", ""))
                        }
                        label(what.status)
                    }
                    is BrowserBookmark -> button("Open browser") { addClass(Styles.thinbutton) }.setOnAction {
                        openNewWindow(BrowserView(what.server, "", what.path.value))
                    }
                    is Sync -> {
                        style(true) { backgroundColor += c("#B0B0B0", .5) }
                        if (what.type == SyncType.FILE) button("Sync file") { addClass(Styles.thinbutton) }.setOnAction {
                            compSyncFile(what) {}
                        } else if (!what.disableFullSync.value) button("Compare & sync all") { addClass(Styles.thinbutton) }.setOnAction {
                            val allsubset = SubSet.all(what)
                            compSync(allsubset)
                        }
                        button("Reveal local") { addClass(Styles.thinbutton) }.setOnAction {
                            revealFile(MFile(what.localfolder.value))
                        }
                        if (what.type != SyncType.FILE)
                            button("Browse remote") { addClass(Styles.thinbutton) }.setOnAction {
                                openNewWindow(BrowserView(what.server, "", what.remoteFolder.value))
                            }
                        label(what.status)
                    }
                    is SubSet -> {
                        button("Compare & sync") { addClass(Styles.thinbutton) }.setOnAction {
                            compSync(what)
                        }
                        label(what.status)
                    }
                }
            }
        }
        root.isExpanded = true
        isShowRoot = false
        root.children.forEach { it.isExpanded = false ; it.children.forEach { it2 -> it2.isExpanded = true }}
        prefHeight = 350.0
        vgrow = Priority.ALWAYS
        useMaxWidth = true
    }

    override val root = vbox {
        prefWidth = 800.0
        menubar {
            isUseSystemMenuBar = true
            menu("Help") {
                item("About").setOnAction {
                    openNewWindow(AboutView(), Modality.APPLICATION_MODAL)
                }
                item("Test MFile").setOnAction { MFile.testit() }
            }
        }
        this += ttv
        hbox {
            button("Add server") { action {
                Server(SimpleStringProperty("name"), SimpleStringProperty(""), SimpleIntegerProperty(-1)).let {
                    SettingsStore.servers += it
                    selectItem(it)
                }
            } }
            button("Settings...") { action {
                openNewWindow(SettingsView(), Modality.APPLICATION_MODAL)
            }}
            button("save sett") { action {
                SettingsStore.saveSettings()
            } }
        }
        this += settingsview
    }

    init {
        logger.info("Initialize MainView...")
        Screen.getPrimary().bounds.let {
            currentStage!!.x = 0.1 * it.width
            currentStage!!.y = 0.1 * it.height
        }
        ttv.setOnMouseClicked { me ->
            val src = ttv.selectedValue
            if (me.clickCount == 1) { // auto collapse/expand servers!
                if (src is Server)
                    ttv.root.children.forEach { it.isExpanded = (src == it.value) }
            }
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

    companion object {
        fun compSync(subset: SubSet) {
            openNewWindow(SyncView(subset.sync.server, subset.sync, subset))
        }
        fun compSyncTemp(sync: Sync, successfulSyncCallback: () -> Unit = {}) {
            openNewWindow(SyncView.syncViewTempIniSync(sync, successfulSyncCallback))
        }
        // synchronizes file, and possibly run filewatcher after sync.
        fun compSyncFile(sync: Sync, successfulFirstSyncCallback: () -> Unit) {
            sync.fileWatcher?.stop()
            val callback: () -> Unit = { // must start filewatcher after getting file!
                if (sync.auto.value) {
                    sync.fileWatcher = FileWatcher(MFile(DBSettings.getCacheFolder(sync.cacheid.value) + sync.title.value))
                    sync.fileWatcher!!.watch {
                        runLater { compSyncFile(sync) { } }
                    }
                }
                successfulFirstSyncCallback()
            }
            openNewWindow(SyncView.syncViewSingleFile(sync, callback))
        }
    }
}
