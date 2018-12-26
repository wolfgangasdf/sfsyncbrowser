
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
            var sane = ""
            SettingsStore.servers.forEach { s ->
                s.syncs.filter { y -> y.type == SyncType.FILE || y.type == SyncType.CACHED }.forEach {
                    y -> sane += "${y.type}: ${s.title.value} / ${y.title.value}\n"
                }
            }
            if (sane != "") {
                if (!Helpers.dialogOkCancel("Close", "Really close? There are file or cached syncs:", sane))
                    it.consume()
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
                                "otherwise cancel!\nLockfile: " + DBSettings.lockFile.absolutePath))
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
