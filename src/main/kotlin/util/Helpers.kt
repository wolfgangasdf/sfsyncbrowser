import javafx.application.Platform
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.concurrent.Task
import javafx.geometry.Rectangle2D
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.layout.Priority
import javafx.stage.Screen
import mu.KotlinLogging
import java.io.File
import java.net.URI
import java.awt.Desktop
import java.io.IOException
import java.util.jar.JarFile
import java.net.URISyntaxException
import java.nio.charset.Charset
import java.util.*
import tornadofx.*
import kotlin.concurrent.timerTask

private val logger = KotlinLogging.logger {}

object Helpers {
    fun isMac() = System.getProperty("os.name").toLowerCase().contains("mac")
    fun isLinux() = System.getProperty("os.name").toLowerCase().matches("(.*nix)|(.*nux)".toRegex())
    fun isWin() = System.getProperty("os.name").toLowerCase().contains("win")
    fun openURL(url: String) {
        if (Desktop.isDesktopSupported() && url != "") {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(URI(url))
            }
        }
    }

    fun toJavaPathSeparator(input: String): String =
        if (isWin()) input.replace("""\\""", "/")
        else input

    // for debugging, this throws exceptions at a place depending on number
    // mind that certain settings have to be chosen (e.g., sftp/local file) to see it fail.
    // after MyWorker etc changes, test all if exceptions propagate as intended!
    val failat = 0 // 0..5 currently

    val filecharset: Charset = java.nio.charset.Charset.forName("UTF-8")

    val directoryFilter = "([a-zA-Z]:)?/.*" // not for sftp... if (isWin) ".:/.*" else "/.*"

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

    val myTitleProperty = SimpleStringProperty("huhu")

    override fun call(): T {
        return callfun()
    }

    fun updateTit(title: String?) {
        runLater {
            println("updatetit: before")
            updateTitle(title)
            myTitleProperty.set(title)
            println("updatetit: after")
        }
    }

    fun updateProgr(workDone: Double, max: Double, msg: String) {
        runLater {
            updateMessage(msg)
            updateProgress(workDone, max)
            println("updateprogr [${this.title}] done=$workDone msg=$msg")
            myTitleProperty.set(msg)
        }
    }
}

object MyWorker: Dialog<javafx.scene.control.ButtonType>() {
    val taskList = FXCollections.observableArrayList<MyTask<*>>()

    class TodoItemModel(property: ObjectProperty<MyTask<*>>) : ItemViewModel<MyTask<*>>(itemProperty = property) {
        val text = bind(autocommit = true) {
            item?.myTitleProperty }
    }

    class TaskListFragment : ListCellFragment<MyTask<*>>() {
        val todoitem = TodoItemModel(itemProperty)
        override val root = vbox {
            println("xxxxxxx item=$item $itemProperty ${itemProperty.get()}")
            label(todoitem.text)
//            label(itemProperty.getProperty(MyTask::titleProperty))
        }
//            hbox {
//                label(item.titleProperty()) {
//                    isWrapText = true
////                    prefWidthProperty().bind(lv.widthProperty() - 180)
//                    hgrow = Priority.ALWAYS
//                }
//                progressbar(item.progressProperty()) {
//                    prefWidth = 150.0
//                }
//            }
//            label(item.messageProperty()) {
//                isWrapText = true
////                    prefWidthProperty().bind(lv.widthProperty() - 30)
//                style = "-fx-font-size: 10"
//            }
//        }
    }

    val taskListView = listview(taskList) {
        cellFragment(DefaultScope, TaskListFragment::class)
    }

//    private val taskListView = ListView<MyTask<*>>().apply {
//        items = taskList
//
//
//        setCellFactory { lv ->
//            ListCell<MyTask<*>>().apply {
//                itemProperty().onChange {
//                    println("itemonch: $item")
//                    if (item?.value != null) {
//                        val title = label {
//                            isWrapText = true
//                            textProperty().bind(item.titleProperty())
//                            prefWidthProperty().bind(lv.widthProperty() - 180)
//                            hgrow = Priority.ALWAYS
//                        }
//                        val message = label {
//                            isWrapText = true
//                            prefWidthProperty().bind(lv.widthProperty() - 30)
//                            textProperty().bind(item.messageProperty())
//                            style = "-fx-font-size: 10"
//                        }
//                        val progress = progressbar {
//                            prefWidth = 150.0
//                            progressProperty().bind(item.progressProperty())
//                        }
//                        val hb = hbox {
//                            this += title
//                            this += progress
//                        }
//                        val vb = vbox {
//                            this += hb
//                            this += message
//                            isFillWidth = true
//                        }
//                        graphic = vb
//                    } else {
//                        graphic = null
//                    }
//                }
//            }
//        }
//    }
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

        setOnCloseRequest {
            if (taskList.isNotEmpty()) {
                taskList.forEach { t -> if (t.isRunning) t.cancel() }
                println("cancelled all tasks!")
            }
        }
    }

    var backgroundTimer: java.util.Timer? = null // just to clean up finished tasks
    init {
        showingProperty().onChange { newv ->
            if (newv) {
                val ttask = timerTask {
                    if (taskList.isNotEmpty()) runLater {
                        var iii = 0
                        while (iii < taskList.size) {
                            if (taskList[iii].isDone || taskList.get(iii).isCancelled) {
                                println("remove task ${taskList[iii].title}")
                                taskList.remove(iii, iii + 1)
                            } else
                                iii += 1
                        }
                        if (taskList.isEmpty()) {
                            this@MyWorker.close()
                        }
                    }
                }
                backgroundTimer = java.util.Timer()
                backgroundTimer!!.schedule(ttask, 0, 500)
            } else {
                backgroundTimer!!.cancel()
            }
        }
    }

    fun runTask(atask: MyTask<*>) {
        taskList.add(atask)
        if (!this.isShowing) this.show()
        println("added task " + atask)
        val th = Thread(atask) // doesn't work
        th.isDaemon = true
        th.start()
        this.taskListView.refresh()
    }
}