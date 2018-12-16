@file:Suppress("unused")

package util

import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.concurrent.Task
import javafx.event.EventTarget
import javafx.scene.control.*
import javafx.scene.layout.Priority
import javafx.scene.web.WebView
import javafx.stage.*
import mu.KotlinLogging
import store.Server
import synchro.VirtualFile
import tornadofx.*
import util.MyWorker.setOnCloseRequest
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.jar.JarFile
import kotlin.math.floor


private val logger = KotlinLogging.logger {}

typealias SSP = SimpleStringProperty

object Helpers {
    fun isMac() = System.getProperty("os.name").toLowerCase().contains("mac")
    fun isLinux() = System.getProperty("os.name").toLowerCase().matches("(.*nix)|(.*nux)".toRegex())
    fun isWin() = System.getProperty("os.name").toLowerCase().contains("win")

    fun tokMGTPE(d: Double): String {
        var num = d
        var ext = ""
        val expo = minOf(floor(Math.log(d) / Math.log(1000.0)).toInt(), 6)
        if (expo > 0) {
            ext = "kMGTPE" [expo - 1].toString()
            num = d / Math.pow(1000.0, expo.toDouble())
        }
        return "%.1f%s".format(num, ext)
    }

    fun revealFile(file: java.io.File) {
        when {
            Helpers.isMac() -> Runtime.getRuntime().exec(arrayOf("open", "-R", file.path))
            Helpers.isWin() -> Runtime.getRuntime().exec("explorer.exe /select,${file.path}")
            Helpers.isLinux() -> error("not supported OS, tell me how to do it!")
            else -> error("not supported OS, tell me how to do it!")
        }
    }

    fun openURL(url: String) {
        if (Desktop.isDesktopSupported() && url != "") {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(URI(url))
            }
        }
    }

    fun readFileToString(fn: Path): String {
        val enc = Files.readAllBytes(fn)
        return StandardCharsets.UTF_8.decode(ByteBuffer.wrap(enc)).toString()
    }

    fun getFileIntoTempAndDo(server: Server, vf: VirtualFile, action: (f: File) -> Unit) {
        val taskGetFile = MyTask<File> {
            updateTit("Downloading file $vf...")
            val rf = Files.createTempFile(vf.getFileName(), ".${vf.getFileExtension()}")
            logger.debug("downloading into ${rf.toFile().absolutePath}...")
            server.getConnection("").getfile("", vf.path, vf.modTime, rf.toFile().absolutePath)
            rf.toFile()
        }
        taskGetFile.setOnSucceeded { action(taskGetFile.value) }
        MyWorker.runTask(taskGetFile)
    }

    fun toHex(i: Int): String {
        return java.lang.Integer.toHexString(i)
    }

    fun toJavaPathSeparator(input: String): String =
        if (isWin()) input.replace("""\\""", "/")
        else input

    fun getParentFolder(path: String): String {
        return path.split("/").dropLastWhile { it.isEmpty() }.dropLast(1).joinToString("/") + "/"
    }

    fun getFileName(path: String) =
            path.split("/").dropLastWhile { it.isEmpty() }.lastOrNull()

    fun chooseDirectoryRel(title: String? = null, initialDirectory: File, owner: Window? = null, op: DirectoryChooser.() -> Unit = {}): File? {
        // there is no way to allow opening multiple directories locally, also not via FileChooser!
        val res = chooseDirectory(title, initialDirectory, owner, op)
        if (res?.startsWith(initialDirectory.path) == true)
            return res.relativeTo(initialDirectory)
        return null
    }

    val relPathRegex = "(^$)|(^[^/].*/$)".toRegex() // "", "asdf/"
    val absPathRegex = "(^/$)|(^/.*/$)".toRegex() // "/", "/asdf/"
    val permissionsRegex = "([ugo][+-][rwxa],?)*".toRegex()
    val permissionsParseRegex = "([ugo])([+-])([rwxa])".toRegex()

    // for debugging, this throws exceptions at a place depending on number
    // mind that certain settings have to be chosen (e.g., sftp/local file) to see it fail.
    // after MyWorker etc changes, test all if exceptions propagate as intended!
    const val failat = 0 // 0..5 currently

    val filecharset: Charset = java.nio.charset.Charset.forName("UTF-8")

    const val directoryFilter = "([a-zA-Z]:)?/.*" // not for sftp... if (isWin) ".:/.*" else "/.*"

    fun openDocument(file: File) {
        if (Desktop.isDesktopSupported()) {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.OPEN)) {
                desktop.open(file)
            }
        }
    }

    // do some quicklook-like thing. returns only after window closed!
    fun previewDocument(file: File) {
        runLater { // otherwise task doesn't close, unclear why
            when {
                isMac() -> {
                    ProcessBuilder("qlmanage", "-p", file.absolutePath).start().waitFor()
                }
                else -> logger.info("Quicklook is not implemented on this platform.")
            }
        }
    }

    // https://stackoverflow.com/a/22404140
    fun getClassBuildTime(): Date? {
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

    // If it hangs, most likely a GUI thread hangs which made a thread which called this
    fun <T>runUIwait( f: () -> T) : T {
        return if (!Platform.isFxApplicationThread()) {
            val query = FutureTask<T>(Callable<T> { f() })
            Platform.runLater(query)
            query.get()
        } else {
            f()
        }
    }

    fun dialogOkCancel(titletext: String, header: String, content: String): Boolean {
        return Alert(Alert.AlertType.CONFIRMATION).apply {
            //initOwner(stage)
            (dialogPane.scene.window as Stage).isAlwaysOnTop = true
            title = titletext
            headerText = header
            contentText = content
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

    fun dialogMessage(type: Alert.AlertType, titletext: String, header: String, htmlmsg: String) {
        Alert(type).apply {
            //if (stage.owner.nonEmpty) initOwner(stage)
            (dialogPane.scene.window as Stage).isAlwaysOnTop = true
            title = titletext
            headerText = header
            if (htmlmsg != "") {
                val sp2 = ScrollPane().apply {
                    // optional html message
                    content = WebView().apply {
                        engine.loadContent(htmlmsg)
                    }
                    isFitToWidth = true
                    isFitToHeight = true
                }
                dialogPane.content = sp2
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
}


// https://github.com/bijukunjummen/kfun/blob/master/src/main/kotlin/io/kfun/Tuples.kt
object Tuple {
    operator fun <A> invoke(_1: A): Tuple1<A> = Tuple1(_1)
    operator fun <A, B> invoke(_1: A, _2: B): Tuple2<A, B> = Tuple2(_1, _2)
    operator fun <A, B, C> invoke(_1: A, _2: B, _3: C): Tuple3<A, B, C> = Tuple3(_1, _2, _3)
    operator fun <A, B, C, D> invoke(_1: A, _2: B, _3: C, _4: D): Tuple4<A, B, C, D> = Tuple4(_1, _2, _3, _4)
    operator fun <A, B, C, D, E> invoke(_1: A, _2: B, _3: C, _4: D, _5: E): Tuple5<A, B, C, D, E> = Tuple5(_1, _2, _3, _4, _5)
}

data class Tuple1<out A>(val _1: A)
data class Tuple2<out A, out B>(val _1: A, val _2: B)
data class Tuple3<out A, out B, out C>(val _1: A, val _2: B, val _3: C)
data class Tuple4<out A, out B, out C, out D>(val _1: A, val _2: B, val _3: C, val _4: D)
data class Tuple5<out A, out B, out C, out D, out E>(val _1: A, val _2: B, val _3: C, val _4: D, val _5: E)

typealias Pair<A, B> = Tuple2<A, B>
typealias Triple<A, B, C> = Tuple3<A, B, C>


// all other solutions didn't work well...
open class MyTask<T>(val callfun: MyTask<T>.() -> T): Task<T>() {

    override fun call(): T {
        return callfun()
    }

    fun updateTit(title: String?) { runLater { updateTitle(title) } }
    fun updateMsg(msg: String?) { runLater { updateMessage(msg) } }

    fun updateProgr(workDone: Int, max: Int, msg: String) {
        runLater {
            updateMessage(msg)
            updateProgress(workDone.toDouble(), max.toDouble())
        }
    }
}

object MyWorker: Dialog<javafx.scene.control.ButtonType>() {
    private val taskList = FXCollections.observableArrayList<MyTask<*>>()
    private var backgroundTimer: java.util.Timer? = null // just to clean up finished tasks

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
        /*if (taskList.isNotEmpty()) runLater {*/
            var iii = 0
            while (iii < taskList.size) {
                if (taskList[iii].isDone || taskList[iii].isCancelled) {
                    taskList.remove(iii, iii + 1)
                } else {
                    iii += 1
                }
            }
            if (taskList.isEmpty()) {
                this@MyWorker.hide()
            }
        //}
    }

    fun runTask(atask: MyTask<*>) {
        // this is to close myworker dialog before calling onsucceeded etc...
        val onsucc = atask.onSucceeded
        val oncanc = atask.onCancelled
        val onfail = atask.onFailed
        atask.setOnSucceeded { taskList -= atask ; cleanup() ; onsucc?.handle(it) }
        atask.setOnCancelled { taskList -= atask ; cleanup() ; oncanc?.handle(it) }
        atask.setOnFailed { taskList -= atask ; cleanup() ; onfail?.handle(it) }

        taskList += atask
        if (!this.isShowing) this.show()
        val th = Thread(atask)
        th.isDaemon = true
        th.start()
    }

}

