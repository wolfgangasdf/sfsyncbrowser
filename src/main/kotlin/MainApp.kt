
import javafx.scene.Node
import javafx.scene.Scene
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
import tornadofx.App
import tornadofx.View
import tornadofx.addStageIcon
import tornadofx.reloadStylesheetsOnFocus
import util.Helpers
import util.Helpers.dialogOkCancel
import kotlin.random.Random

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
    newstage.setOnShown { view.doAfterShown() }
    newstage.setOnHiding { view.doBeforeClose() }
    newstage.show()
    newstage.addEventFilter(KeyEvent.KEY_RELEASED) {
        if (it.isMetaDown && it.code == KeyCode.W) newstage.close()
    }
    return view
}

class SSBApp : App(MainView::class, Styles::class) { // or Workspace?

    override fun stop() {
        logger.info("*************** stop app")
        SettingsStore.saveSettings()
        SettingsStore.shutdown()
        DBSettings.releaseLock()
        System.exit(0)
    }

    override fun start(stage: Stage) {
        stage.setOnCloseRequest {
            val filesyncs = SettingsStore.servers.map { s -> s.syncs.filter { sy -> sy.type == SyncType.FILE } }.flatten()
            if (filesyncs.isNotEmpty()) {
                // TODO: check for modifications cache <> local!
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
        }
        super.start(stage)
    }

    init {
        reloadStylesheetsOnFocus() // works only if run in debug mode! remove in production?
        if (!DBSettings.getLock()) {
            Helpers.runUIwait {
                if (!dialogOkCancel("SFSync Error", "Lock file exists",
                        "If you are sure that no other Sfsync instance is running, press OK to remove the lockfile, " +
                                "otherwise cancel!\nLockfile: " + DBSettings.lockFile))
                    System.exit(1)
            }
        }
        addStageIcon(Image(resources["/icons/icon_16x16.png"]))
        addStageIcon(Image(resources["/icons/icon_32x32.png"]))
        addStageIcon(Image(resources["/icons/icon_256x256.png"]))
        initit()
    }
}

fun initit() {
    Checks.checkComparedFile()
    SettingsStore
}
