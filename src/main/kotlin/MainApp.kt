@file:Suppress("unused") // TODO

import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.stage.Modality
import javafx.stage.Stage
import mu.KotlinLogging
import store.DBSettings
import store.SettingsStore
import tornadofx.*

private val logger = KotlinLogging.logger {}


class Styles : Stylesheet() {

//    init {
//        button {
//            and(hover) {
//                backgroundColor += Color.RED
//            }
//        }
//        cell {
//            and(selected) {
//                backgroundColor += Color.RED
//            }
//        }
//    }
}

fun <T: UIComponent> openNewWindow(view: T, m: Modality = Modality.NONE): T {
    val newstage = Stage()
//    newstage.title = view.title
    newstage.titleProperty().bind(view.titleProperty)
    newstage.scene = Scene(view.root)
    newstage.initModality(m)
    newstage.show()
    return view
}

class SSBApp : App(MainView::class, Styles::class) { // or Workspace?

    override fun stop() {
        logger.info("*************** stop app")
        SettingsStore.saveSettings()
        DBSettings.releaseLock()
        System.exit(0)
    }

    init {
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
