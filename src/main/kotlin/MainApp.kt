
import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.image.Image
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.stage.Modality
import javafx.stage.Screen
import javafx.stage.Stage
import mu.KotlinLogging
import store.DBSettings
import store.SettingsStore
import store.SyncType
import tornadofx.*
import util.Helpers
import util.Helpers.dialogOkCancel
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

abstract class MyView(title: String? = null, icon: Node? = null) : View(title, icon) {
    open fun doAfterShown() {}
    open fun doBeforeClose() {}
}

fun <T: MyView> openNewWindow(view: T, m: Modality = Modality.NONE): T {
    val newstage = Stage()
    newstage.titleProperty().bind(view.titleProperty)
    newstage.scene = Scene(view.root)
    newstage.initModality(m)
    Screen.getPrimary().bounds.let {
        newstage.x = Random.nextDouble(0.1, 0.3) * it.width
        newstage.y = Random.nextDouble(0.1, 0.3) * it.height
    }
    newstage.setOnShown { Platform.runLater { view.doAfterShown() } }
    newstage.setOnHiding { view.doBeforeClose() }
    newstage.show()
    newstage.addEventFilter(KeyEvent.KEY_RELEASED) {
        if (it.isMetaDown && it.code == KeyCode.W) newstage.close()
    }
    return view
}

class SSBApp : App() {

    override fun stop() {
        logger.info("*************** stop app")
        SettingsStore.saveSettings()
        SettingsStore.shutdown()
        DBSettings.releaseLock()
        exitProcess(0)
    }

    override fun start(stage: Stage) {
        logger.debug("*************** start app: start")
        importStylesheet(Styles::class)
        FX.setPrimaryStage(stage = stage)

        // check lock before init MainView
        if (!DBSettings.getLock()) {
            logger.error("can't get lockfile!")
            Helpers.runUIwait {
                if (!dialogOkCancel("SFSync Error", "Lock file exists",
                        "If you are sure that no other Sfsync instance is running, press OK to remove the lockfile, " +
                                "otherwise cancel!\nLockfile: " + DBSettings.lockFile))
                    exitProcess(1)
            }
        }

        // init
        Checks.checkComparedFile()
        SettingsStore

        // load & show MainView
        stage.scene = createPrimaryScene(MainView())
        stage.scene.stylesheets.add(Styles().externalForm)
        stage.show()

        // tornadofx default handler doesn't show stacktrace properly
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            run {
                logger.error("uncaught exception: $e")
                e.printStackTrace()
                Helpers.dialogMessage(Alert.AlertType.ERROR, "Exception", e.message?:"no message", "logfile:${DBSettings.logFile}\n" + e.stackTraceToString())
            }
        }
        FX.installErrorHandler()
        stage.setOnCloseRequest {
            val filesyncs = SettingsStore.servers.map { s -> s.syncs.filter { sy -> sy.type == SyncType.FILE } }.flatten()
            if (filesyncs.isNotEmpty()) {
                if (SettingsStore.ssbSettings.onExitRemoveFilesyncs.value ||
                        dialogOkCancel("File syncs existing", "File syncs existing. Remove them, including the local file?",
                                    filesyncs.joinToString("\n") { sy -> "${sy.server.title.value}: ${sy.title.value}" })) {
                    val iter = filesyncs.iterator()
                    while (iter.hasNext()) {
                        val sync = iter.next()
                        logger.info("Exit: removing temporary file sync $sync !")
                        sync.server.removeSync(sync)
                    }
                }
            }
            val cachedsyncs = SettingsStore.servers.map { s -> s.syncs.filter { sy -> sy.type == SyncType.TEMP } }.flatten()
            if (cachedsyncs.isNotEmpty()) {
                if (dialogOkCancel("Cached syncs existing", "Cached syncs existing. Remove them, including all local files?",
                        cachedsyncs.joinToString("\n") { sy -> "${sy.server.title.value}: ${sy.title.value}" })) {
                    val iter = cachedsyncs.iterator()
                    while (iter.hasNext()) {
                        val sync = iter.next()
                        logger.info("Exit: removing cached sync $sync !")
                        sync.server.removeSync(sync)
                    }
                }
            }
        }
        // Dock icon
        if (Helpers.isMac()) java.awt.Taskbar.getTaskbar().iconImage = ImageIO.read(this::class.java.getResource("/icons/icon_256x256.png"))
    }

    init {
        logger.debug("*************** start app: init")
        reloadStylesheetsOnFocus() // works only if run in debug mode! remove in production?
        addStageIcon(Image(resources["/icons/icon_16x16.png"]))
        addStageIcon(Image(resources["/icons/icon_32x32.png"]))
        addStageIcon(Image(resources["/icons/icon_256x256.png"]))
    }
}
