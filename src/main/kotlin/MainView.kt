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
import java.io.File

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

class MainView : View("SSyncBrowser") {

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
                        Helpers.openURL("https://github.com/wolfgangasdf/ssyncbrowser-test")
                    }
                    DBSettings.logFile?.let {f ->
                        field("Log file") {
                            textfield(f.path) { isDisable = true }
                            button("Open log file").setOnAction { Helpers.openFile(f.path) }
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
                            val fl = chooseFile("Select editor program", arrayOf(ef))
                            fl.first().let {f -> SettingsStore.ssbSettings.editor.set(f.path) }
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
                        combobox<Protocol>(server.proto, server.protocols)
                        button("Edit") { action {
                            openNewWindow(ProtocolView(server.proto.value), Modality.APPLICATION_MODAL)
                        }}
                        button("Add") { action {
                            Protocol(server, SSP("sftp://user@server:/folder/"), SBP(false),
                                    SSP(""), SBP(false),
                                    SSP(""), SSP(""), SSP(""), SSP(SettingsStore.tunnelModes[0])).let {
                                server.protocols += it
                                if (server.proto.value == null) server.proto.set(it)
                                selectItem(it)
                            }
                        } }
                        button("Remove") { action {
                            server.protocols.remove(server.proto.value)
                        } }
                    }
                    field {
                        button("Add new sync") { action {
                            Sync(SyncType.NORMAL, SSP("syname"),
                                SSP(""), SSP("sylocalfolder"),
                                SSP("syremotefolder"), SSP(""), server=server).let {
                                server.syncs += it
                                selectItem(it)
                            }
                        } }
                        button("Open browser") { action {
                            openNewWindow(BrowserView(server, "", ""))
                        } }
                        button("Remove server") { action {
                            val iter = server.syncs.iterator()
                            while (iter.hasNext()) server.removeSync(iter.next())
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

    class ProtocolView(private val proto: Protocol): MyView() {
        override val root = Form()
        override fun doBeforeClose() { proto.server.protocols.invalidate() }
        init {
            with(root) {
                prefWidth = 600.0
                fieldset("Protocol") {
                    field("URI and password") {
                        valitextfield(proto.protocoluri, uriRegex, "Regex: $uriRegex") { tooltip("'sftp://user@host[:port]' or 'file://") }
                        passwordfield(proto.password) { tooltip("Leave empty for public key authentification")}
                    }
                    field("Remote basefolder") { valitextfield(proto.baseFolder, absPathRegex, "Absolute path like '/folder'") { } }
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
                if (sync.type in setOf(SyncType.NORMAL, SyncType.CACHED)) fieldset("Sync") {
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
                    field("Exclude filter") { textfield(sync.excludeFilter) }
                    field {
                        button("Add new subset") { action {
                            SubSet(SSP("ssname"), SSP(""), sync = sync).let {
                                sync.subsets += it
                                selectItem(it)
                            }
                        } }
                        button("Remove sync") { action {
                            sync.server.removeSync(sync)
                        } }
                    }
                } else fieldset("File sync") {
                    field("File path") { label(sync.title) ; checkbox("Auto", sync.auto).apply { isDisable = true } }
                    field("Cacheid") {
                        textfield(sync.cacheid) { isEditable = false }
                    }
                    field("Local folder") {
                        label(sync.localfolder)
                        button("Reveal").setOnAction { revealFile(File(sync.localfolder.value), true) }
                    }
                    field("Remote folder") {
                        label(sync.remoteFolder)
                    }
                    field {
                        button("Remove sync") { action {
                            sync.server.removeSync(sync)
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
                openNewWindow(SyncView(subset.sync.server, subset.sync, subset))
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
                    field {
                        button("Compare & Sync!") { action {
                            compSync(subset)
                        } }
                        button("Add new folder(s) (remote)") { action {
                            val bv = openNewWindow(BrowserView(subset.sync.server, subset.sync.remoteFolder.value, "", BrowserViewMode.SELECTFOLDERS), Modality.APPLICATION_MODAL)
                            bv.selectFoldersCallback = {
                                it.forEach { vf -> subset.subfolders += vf.path }
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
        private val changeListener = ChangeListener<Any> { _, _, _ ->
            Event.fireEvent(this, TreeItem.TreeModificationEvent<Any>(TreeItem.valueChangedEvent<Any>(), this))
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
        root = TreeItem<Any>("root")
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
                            val allsubset = SubSet(SSP("<all>"), SSP(""), sync = what)
                            allsubset.subfolders += ""
                            SubsetSettingsPane.compSync(allsubset)
                        }
                        button("Reveal local") { addClass(Styles.thinbutton) }.setOnAction {
                            revealFile(File(what.localfolder.value))
                        }
                        label(what.status)
                    }
                    is SubSet -> {
                        button("Compare & sync") { addClass(Styles.thinbutton) }.setOnAction {
                            SubsetSettingsPane.compSync(what)
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
        this += ttv
        hbox {
            button("Add server") { action {
                Server(SSP("name"), SSP(""), SIP(-1)).let {
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
            button("test") { action {
                val testtask = MyTask<Unit> {
                    updateTit("Getting remote file list...")
                    Thread.sleep(500L)
                    updateProgr(10,100," asdjkfdg skdfghs kdfgs kdfjg sdkjfg skdjfg ksdjfg ksdjfg skjdfg skjdfg ksdjfg ksdjfg ksdjfg skdfg skdjfg skdfg skdjfgh sdkjfg skdjfg ")
                    Thread.sleep(500L)
                    updateProgr(20,100," asdjkfdg skdfghs kdfgs kdfjg sdkjfg skdjfg ksdjfg ksdjfg skjdfg skjdfg ksdjfg ksdjfg ksdjfg skdfg skdjfg skdfg skdjfgh sdkjfg skdjfg ")
                    Thread.sleep(500L)
                    updateProgr(30,100," asdjkfdg skdfghs kdfgs kdfjg sdkjfg skdjfg ksdjfg ksdjfg skjdfg skjdfg ksdjfg ksdjfg ksdjfg skdfg skdjfg skdfg skdjfgh sdkjfg skdjfg ")
                    Thread.sleep(500L)
                    updateProgr(40,100," asdjkfdg skdfghs kdfgs kdfjg sdkjfg skdjfg ksdjfg ksdjfg skjdfg skjdfg ksdjfg ksdjfg ksdjfg skdfg skdjfg skdfg skdjfgh sdkjfg skdjfg ")
                    Thread.sleep(500L)
                    updateProgr(50,100," asdjkfdg skdfghs kdfgs kdfjg sdkjfg skdjfg ksdjfg ksdjfg skjdfg skjdfg ksdjfg ksdjfg ksdjfg skdfg skdjfg skdfg skdjfgh sdkjfg skdjfg ")
                }
                MyWorker.runTask(testtask)
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
        menubar {
            isUseSystemMenuBar = true
            menu("Help") {
                item("About").setOnAction {
                    openNewWindow(AboutView(), Modality.APPLICATION_MODAL)
                }
            }
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
        fun compSyncFile(sync: Sync, successfulSyncCallback: () -> Unit) {
            sync.fileWatcher?.stop()
            openNewWindow(SyncView(sync.server, sync, successfulSyncCallback))
            if (sync.auto.value) {
                sync.fileWatcher = FileWatcher(DBSettings.getCacheFolder(sync.cacheid.value) + sync.title.value).watch {
                    runLater { compSyncFile(sync, successfulSyncCallback) }
                }
            }
        }
    }
}
