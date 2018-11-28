
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.stage.Modality
import javafx.stage.Stage
import mu.KotlinLogging
import store.DBSettings
import store.SettingsStore
import tornadofx.*
import util.Helpers
import util.Helpers.dialogMessage

private val logger = KotlinLogging.logger {}


fun <T: UIComponent> openNewWindow(view: T, m: Modality = Modality.NONE): T {
    val newstage = Stage()
    newstage.titleProperty().bind(view.titleProperty)
    newstage.scene = Scene(view.root)
    newstage.initModality(m)
    newstage.show()
    newstage.addEventFilter(KeyEvent.KEY_RELEASED) {
        if (it.code == KeyCode.ESCAPE) newstage.close()
    }
    return view
}

class SSBApp : App(MainView::class, Styles::class) { // or Workspace?

    override fun stop() {
        logger.info("*************** stop app")
        SettingsStore.saveSettings()
        DBSettings.releaseLock()
        DBSettings.shutdown()
        System.exit(0)
    }

    init {
        reloadStylesheetsOnFocus() // works only if run in debug mode! remove in production?
        if (!DBSettings.getLock()) {
            Helpers.runUIwait { dialogMessage("SFSync Error", "Lock file exists", "Is another Sfsync instance running?<br>If not, remove " + DBSettings.lockFile.absolutePath) }
            System.exit(1)
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
