package util

import io.methvin.watcher.DirectoryWatcher
import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.concurrent.Task
import javafx.event.EventTarget
import javafx.scene.control.*
import javafx.scene.layout.Priority
import javafx.stage.*
import mu.KotlinLogging
import store.Server
import store.SettingsStore
import synchro.GeneralConnection
import synchro.VirtualFile
import tornadofx.*
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.FutureTask
import java.util.jar.JarFile
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow


private val logger = KotlinLogging.logger {}

typealias SSP = SimpleStringProperty
typealias SBP = SimpleBooleanProperty
typealias SIP = SimpleIntegerProperty

object Helpers {
    fun isMac() = System.getProperty("os.name").toLowerCase().contains("mac")
    fun isLinux() = System.getProperty("os.name").toLowerCase().matches("(.*nix)|(.*nux)".toRegex())
    fun isWin() = System.getProperty("os.name").toLowerCase().contains("win")

    fun defaultOSexcludeFilter() = when {
        isMac() -> "(\\.DS_Store)|(\\._.*)"
        isLinux() -> ""
        else -> "(\\~\\$)"
    }

    fun dformat() = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    fun tokMGTPE(d: Double): String {
        var num = d
        var ext = ""
        val expo = minOf(floor(ln(d) / ln(1000.0)).toInt(), 6)
        if (expo > 0) {
            ext = "kMGTPE" [expo - 1].toString()
            num = d / 1000.0.pow(expo.toDouble())
        }
        return "%.1f%s".format(num, ext)
    }
    fun tokMGTPE(d: Long): String {
        val expo = minOf(floor(ln(d.toDouble()) / ln(1000.0)).toInt(), 6)
        return if (expo > 0) {
            val ext = "kMGTPE" [expo - 1].toString()
            val num = d / 1000.0.pow(expo.toDouble())
            "%.2f%s".format(num, ext)
        } else "%d".format(d)
    }
    fun toThousandsCommas(l: Long): String = String.format("%,d", l)

    fun revealFile(file: MFile, gointo: Boolean = false) {
        when {
            isMac() -> Runtime.getRuntime().exec(arrayOf("open", if (gointo) "" else "-R", file.getOSPath()))
            isWin() -> Runtime.getRuntime().exec("explorer.exe /select,${file.getOSPath()}")
            isLinux() -> error("not supported OS, tell me how to do it!")
            else -> error("not supported OS, tell me how to do it!")
        }
    }

    fun openFile(file: MFile) {
        if (Desktop.isDesktopSupported() && file.internalPath != "") {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.OPEN)) {
                desktop.open(file.file)
            }
        }
    }

    // true if succeeded
    fun trashFile(file: MFile): Boolean {
        if (Desktop.isDesktopSupported() && file.internalPath != "") {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.MOVE_TO_TRASH)) {
                desktop.moveToTrash(file.file)
                return true
            }
        }
        return false
    }

    fun openURL(url: String) {
        if (Desktop.isDesktopSupported() && url != "") {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(URI(url))
            }
        }
    }

    fun editFile(file: MFile) {
        when {
            isMac() -> Runtime.getRuntime().exec(arrayOf("/usr/bin/open", "-a", SettingsStore.ssbSettings.editor.value, file.getOSPath()))
            isWin() -> Runtime.getRuntime().exec(arrayOf(SettingsStore.ssbSettings.editor.value, file.getOSPath()))
            isLinux() -> error("not supported OS, tell me how to do it!")
            else -> error("not supported OS, tell me how to do it!")
        }
    }

    fun showNotification(title: String, subtitle: String, msg: String) { // should probably use https://github.com/jcgay/send-notification
        when {
            isMac() -> Runtime.getRuntime().exec(arrayOf("osascript", "-e", "display notification \"$msg\" with title \"$title\" subtitle \"$subtitle\""))
            else -> error("not supported OS, tell me how to do it!")
        }
    }

    fun getFileIntoTempAndDo(server: Server, vf: VirtualFile, action: (f: MFile) -> Unit) {
        val taskGetFile = MyTask<MFile> {
            updateTit("Downloading file $vf...")
            val rf = MFile.createTempFile(vf.getFileName(), ".${vf.getFileExtension()}")
            logger.debug("downloading into ${rf.internalPath}...")
            server.getConnection("").getfile("", vf.path, vf.modTime, rf.internalPath)
            rf
        }
        taskGetFile.setOnSucceeded { action(taskGetFile.value) }
        MyWorker.runTask(taskGetFile)
    }

    fun toJavaPathSeparator(input: String): String =
        if (isWin()) input.replace("""\\""", "/")
        else input

    fun getParentFolder(path: String): String {
        return path.split("/").dropLastWhile { it.isEmpty() }.dropLast(1).joinToString("/") + "/"
    }

    fun getFileName(path: String) =
            path.split("/").dropLastWhile { it.isEmpty() }.lastOrNull()

    // returns aaa/bb/
    fun chooseDirectoryRel(title: String? = null, initialDirectory: MFile, owner: Window? = null, op: DirectoryChooser.() -> Unit = {}): String? {
        // there is no way to allow opening multiple directories locally, also not via FileChooser!
        val res = chooseDirectory(title, initialDirectory.file, owner, op)?.asMFile()
        if (res?.internalPath?.startsWith(initialDirectory.internalPath) == true)
            return MFile.getIPrelativeTo(res.internalPath, initialDirectory.internalPath)
        return null
    }

    val relPathRegex = "(^$)|(^[^/].*/$)".toRegex() // "", "asdf/"
    val absPathRegex = "(^/$)|(^/.*/$)".toRegex() // "/", "/asdf/"
    val permissionsRegex = "([ugo][+-][rwxa],?)*".toRegex()
    val permissionsParseRegex = "([ugo])([+-])([rwxa])".toRegex()
    val uriRegex = """(?:(^file)://$)|(?:(^sftp)://(\S+)@([a-zA-Z0-9\-.]+)(:[0-9]+)?$)""".toRegex()
    val hostPortNothingRegex = """|([a-zA-Z0-9\-.]+)(:[0-9]+)?""".toRegex()

    val filecharset: Charset = Charset.forName("UTF-8")

    // do some quicklook-like thing. returns only after window closed!
    fun previewDocument(file: MFile) {
        runLater { // otherwise task doesn't close, unclear why
            when {
                isMac() -> {
                    ProcessBuilder("qlmanage", "-p", file.getOSPath()).start().waitFor()
                }
                else -> logger.info("Quicklook is not implemented on this platform.")
            }
        }
    }

    // If it hangs, most likely a GUI thread hangs which made a thread which called this
    fun <T>runUIwait( f: () -> T) : T {
        return if (!Platform.isFxApplicationThread()) {
            val query = FutureTask { f() }
            Platform.runLater(query)
            query.get()
        } else {
            f()
        }
    }

    fun dialogYesNoCancel(titletext: String, header: String, content: String): Int {
        return Alert(Alert.AlertType.CONFIRMATION, "", ButtonType.YES, ButtonType.NO, ButtonType.CANCEL).apply {
            //initOwner(stage)
            (dialogPane.scene.window as Stage).isAlwaysOnTop = true
            title = titletext
            headerText = header
            if (content != "") {
                dialogPane.content = textarea(content) {
                    isEditable = false
                }
            }
        }.showAndWait().orElse(ButtonType.NO).let {
            when(it) {
                ButtonType.OK -> 1
                ButtonType.CANCEL -> -1
                else -> 0
            }
        }
    }

    fun dialogOkCancel(titletext: String, header: String, content: String): Boolean {
        return Alert(Alert.AlertType.CONFIRMATION).apply {
            //initOwner(stage)
            (dialogPane.scene.window as Stage).isAlwaysOnTop = true
            title = titletext
            headerText = header
            if (content != "") {
                dialogPane.content = textarea(content) {
                    isEditable = false
                }
            }
        }.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK
    }

    fun dialogInputString(titletext: String, header: String, content: String, inival: String = ""): String? {
        return TextInputDialog(inival).apply {
            //initOwner(stage)
            (dialogPane.scene.window as Stage).isAlwaysOnTop = true
            title = titletext
            headerText = header
            contentText = content
        }.showAndWait().orElse(null)
    }

    fun dialogMessage(type: Alert.AlertType, titletext: String, header: String, content: String) {
        Alert(type).apply {
            //if (stage.owner.nonEmpty) initOwner(stage)
            (dialogPane.scene.window as Stage).isAlwaysOnTop = true
            title = titletext
            headerText = header
            if (content != "") {
                dialogPane.content = textarea(content) {
                    isEditable = false
                }
            }
        }.showAndWait()
    }

    // this is because simply property-bound textfields can't have validator without ViewModel...
    fun EventTarget.valitextfield(property: ObservableValue<String>, valiregex: Regex, valimsg: String, op: TextField.() -> Unit = {}) = textfield().apply {
        bind(property)
        op(this)
        fun updateit() {
            if (!text.matches(valiregex))
                addDecorator(SimpleMessageDecorator(valimsg, ValidationSeverity.Error))
            else while (decorators.isNotEmpty()) removeDecorator(decorators.first())
        }
        this.textProperty().onChange { updateit() }
        updateit()
    }

    fun <T> getSortedFilteredList(): SortedFilteredList<T> {
        val res = SortedFilteredList<T>()
        res.sortedItems.setComparator { o1, o2 -> o1.toString().toUpperCase().compareTo(o2.toString().toUpperCase()) }
        return res
    }

    // observablelist concatenation, target is read only
    fun concatObsLists(vararg lists: ObservableList<out Any>): ObservableList<Any> {
        val into = getSortedFilteredList<Any>()
        for (l in lists) {
            for (ll in l) into.add(ll)
            l.addListener { c: javafx.collections.ListChangeListener.Change<out Any> ->
                while (c.next()) {
                    if (c.wasAdded()) {
                        for (ll in c.addedSubList) into.add(ll)
                    }
                    if (c.wasRemoved()) {
                        for (ll in c.removed) into.remove(ll)
                    }
                }
            }
        }
        return into
    }

    fun getClassBuildTime(): Date? { // https://stackoverflow.com/a/22404140
        var d: Date? = null
        val currentClass = object : Any() {

        }.javaClass.enclosingClass
        val resource = currentClass.getResource(currentClass.simpleName + ".class")
        if (resource != null) {
            when(resource.protocol) {
                "file" -> try {
                    d = Date(File(resource.toURI()).lastModified())
                } catch (ignored: URISyntaxException) {
                }
                "jar" -> {
                    val path = resource.path
                    d = Date(File(path.substring(5, path.indexOf("!"))).lastModified())
                }
                "zip" -> {
                    val path = resource.path
                    val jarFileOnDisk = File(path.substring(0, path.indexOf("!")))
                    //long jfodLastModifiedLong = jarFileOnDisk.lastModified ();
                    //Date jfodLasModifiedDate = new Date(jfodLastModifiedLong);
                    try {
                        JarFile(jarFileOnDisk).use { jf ->
                            val ze = jf.getEntry(path.substring(path.indexOf("!") + 2))//Skip the ! and the /
                            val zeTimeLong = ze.time
                            val zeTimeDate = Date(zeTimeLong)
                            d = zeTimeDate
                        }
                    } catch (ignored: IOException) {
                    } catch (ignored: RuntimeException) {
                    }

                }
            }
        }
        return d
    }
}

// all other solutions didn't work well...
open class MyTask<T>(val callfun: MyTask<T>.() -> T): Task<T>() {

    override fun call(): T {
        return callfun()
    }

    fun updateTit(title: String?) { runLater {
        logger.debug("task: title=$title")
        updateTitle(title)
    } }
    fun updateMsg(msg: String?) { runLater {
        logger.debug("task: msg=$msg")
        updateMessage(msg)
    } }

    fun updateProgr(workDone: Int, max: Int, msg: String) = updateProgr(workDone.toDouble(), max.toDouble(), msg)
    fun updateProgr(workDone: Double, max: Double, msg: String) {
        runLater {
            updateMessage(msg)
            updateProgress(workDone, max)
        }
    }
}

object MyWorker: Dialog<ButtonType>() {
    private val taskList = FXCollections.observableArrayList<MyTask<*>>()

    private val taskListView = listview(taskList) {
        cellFormat { // https://www.youtube.com/watch?v=mlDT1Y1b09M
            graphic = vbox {
                hbox {
                    label(itemProperty().select(MyTask<*>::titleProperty)) {
                        isWrapText = true
                        hgrow = Priority.NEVER
                    }
                    progressbar(itemProperty().select(MyTask<*>::progressProperty)) {
                        hgrow = Priority.ALWAYS
                    }
                }
                label(itemProperty().select(MyTask<*>::messageProperty)) {
                    isWrapText = true
                    style = "-fx-font-size: 10"
                    hgrow = Priority.NEVER
                    this@vbox.widthProperty().onChange { w -> prefWidth = w }
                }
            }
        }
    }

    init {
        //initOwner(FX.primaryStage)
        title = "Progress"
        isResizable = true
        dialogPane.content = vbox {
            this += label("Tasks:")
            this += taskListView
        }
        dialogPane.buttonTypes += ButtonType.CANCEL

        dialogPane.setPrefSize(600.0,300.0)
        Screen.getPrimary().visualBounds.let {
            x = 0.9*it.width - dialogPane.prefWidth
            y = 0.9*it.height - dialogPane.prefHeight
        }

        initModality(Modality.NONE)

        setOnCloseRequest {
            if (taskList.isNotEmpty()) {
                taskList.forEach { t -> if (t.isRunning) t.cancel() }
                logger.info("cancelled all tasks!")
            }
        }
    }

    private fun cleanup() {
        if (taskList.isNotEmpty()) runLater {
            var iii = 0
            while (iii < taskList.size) {
                if (taskList[iii].isDone || taskList[iii].isCancelled) {
                    taskList.remove(iii, iii + 1)
                } else {
                    iii += 1
                }
            }
            if (taskList.isEmpty()) {
                this@MyWorker.close()
            }
        }
    }

    fun runTask(atask: MyTask<*>) {
        // this is to close myworker dialog before calling onsucceeded etc...
        val onsucc = atask.onSucceeded
        val oncanc = atask.onCancelled
        val onfail = atask.onFailed
        atask.setOnSucceeded { cleanup() ; onsucc?.handle(it) }
        atask.setOnCancelled { cleanup() ; oncanc?.handle(it) }
        atask.setOnFailed { cleanup() ; onfail?.handle(it) }

        taskList += atask
        if (!this.isShowing) this.show()
        val th = Thread(atask)
        th.isDaemon = true
        th.start()
    }

    fun runTaskWithConn(onsucc: () -> Unit, msg: String, server: Server, basePath: String, callfun: MyTask<Unit>.(connection: GeneralConnection) -> Unit) {
        val t = MyTask<Unit> {
            updateTit("Initializing connection...")
            val conn = server.getConnection(basePath)
            updateTit("")
            conn.onProgress = { progressVal, bytesPerSecond ->
                val pv = (100 * progressVal).toInt()
                updateProgr(progressVal, 1.0, "$msg...$pv% (${Helpers.tokMGTPE(bytesPerSecond)}B/s)")
            }
            callfun(conn)
        }
        t.setOnSucceeded { onsucc() }
        t.setOnFailed {
            throw t.exception
        }
        runTask(t)
    }

}

class FileWatcher(val file: MFile) {
    private var dw: DirectoryWatcher? = null
    private var lastmod = 0L

    private fun lastMod(): Long = file.lastModified()

    // this doesn't work with directories, watches file mod time to avoid double notifications.
    fun watch(callback: (MFile) -> Unit ): FileWatcher {
        logger.info("filewatcher: watching $file")
        lastmod = lastMod()
        dw = DirectoryWatcher.builder().path(file.asPath()).listener { dce ->
            val newlastmod = lastMod()
            logger.debug("filewatcher($file, $lastmod, $newlastmod): $dce")
            if (newlastmod != lastmod) {
                lastmod = newlastmod
                callback(file)
            }
        }.fileHashing(false).build()
        dw?.watchAsync()
        return this
    }

    fun stop() {
        logger.info("filewatcher: stop watching $file")
        dw?.close()
    }
}
