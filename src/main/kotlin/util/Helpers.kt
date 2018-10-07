@file:Suppress("unused")

package util

import javafx.application.Platform
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import javafx.concurrent.Task
import javafx.event.EventTarget
import javafx.geometry.Rectangle2D
import javafx.scene.control.*
import javafx.scene.layout.Priority
import javafx.scene.web.WebView
import javafx.stage.Modality
import javafx.stage.Screen
import mu.KotlinLogging
import tornadofx.*
import util.MyWorker.setOnCloseRequest
import util.MyWorker.setOnHidden
import util.MyWorker.setOnShown
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.jar.JarFile
import kotlin.concurrent.timerTask
import kotlin.math.floor


private val logger = KotlinLogging.logger {}

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


    fun openURL(url: String) {
        if (Desktop.isDesktopSupported() && url != "") {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(URI(url))
            }
        }
    }

    fun toHex(i: Int): String {
        return java.lang.Integer.toHexString(i)
    }

    fun toJavaPathSeparator(input: String): String =
        if (isWin()) input.replace("""\\""", "/")
        else input

    fun getParentFolder(path: String): String {
        println("getparent of $path")
        return path.split("/").dropLastWhile { it.isEmpty() }.dropLast(1).joinToString("/") + "/"
    }

    fun getFileName(path: String) =
            path.split("/").dropLastWhile { it.isEmpty() }.lastOrNull()

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
            title = titletext
            headerText = header
            contentText = content
        }.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK
    }

    fun dialogInputString(titletext: String, header: String, content: String): String {
        return TextInputDialog().apply {
            //initOwner(stage)
            title = titletext
            headerText = header
            contentText = content
        }.showAndWait().orElse("")
    }

    fun dialogMessage(titletext: String, header: String, htmlmsg: String) {
        Dialog<Boolean>().apply {
            //if (stage.owner.nonEmpty) initOwner(stage)
            title = titletext
            headerText = header
            val sp2 = ScrollPane().apply { // optional html message
                content = WebView().apply {
                    engine.loadContent(htmlmsg)
                }
                isFitToWidth = true
                isFitToHeight = true
            }
            dialogPane.content = sp2
            dialogPane.buttonTypes += listOf(ButtonType.OK).observable()
        }.showAndWait()
    }

    // this is because simply property-bound textfields can't have validator without ViewModel...
    fun EventTarget.valitextfield(property: ObservableValue<String>, valiregex: String, valimsg: String, op: TextField.() -> Unit = {}) = textfield().apply {
        bind(property)
        op(this)
        fun updateit() {
            if (!text.matches(valiregex.toRegex()))
                addDecorator(SimpleMessageDecorator(valimsg, ValidationSeverity.Error))
            else while (decorators.isNotEmpty()) removeDecorator(decorators.first())
        }
        this.textProperty().onChange { updateit() }
        updateit()
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
            graphic = cache {
                vbox {
                    hbox {
                        label(itemProperty().select(MyTask<*>::titleProperty)) {
                            isWrapText = true
                            //                    prefWidthProperty().bind(lv.widthProperty() - 180)
                            hgrow = Priority.ALWAYS
                        }
                        progressbar(itemProperty().select(MyTask<*>::progressProperty)) {
                            prefWidth = 150.0
                        }
                    }
                    label(itemProperty().select(MyTask<*>::messageProperty)) {
                        isWrapText = true
                        //                    prefWidthProperty().bind(lv.widthProperty() - 30)
                        style = "-fx-font-size: 10"
                    }
                }
            }

        }
    }

    init {
        // not needed? initOwner(Main.stage)
        title = "Progress"
        isResizable = true
        dialogPane.content = vbox {
            this += label("Tasks:")
            this += taskListView
        }
        dialogPane.buttonTypes += ButtonType.CANCEL
        val sb: Rectangle2D = Screen.getPrimary().visualBounds
        dialogPane.setPrefSize(sb.width/2.5, sb.height/3)

        initModality(Modality.NONE)

        setOnCloseRequest {
            if (taskList.isNotEmpty()) {
                taskList.forEach { t -> if (t.isRunning) t.cancel() }
                logger.info("cancelled all tasks!")
            }
        }

        setOnShown {
            val ttask = timerTask {
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
            backgroundTimer = java.util.Timer()
            backgroundTimer!!.schedule(ttask, 0, 500)
        }

        setOnHidden {
            backgroundTimer!!.cancel()
        }

    }

    fun runTask(atask: MyTask<*>) {
        taskList += atask
        if (!this.isShowing) this.show()
        val th = Thread(atask) // doesn't work
        th.isDaemon = true
        th.start()
    }
}
