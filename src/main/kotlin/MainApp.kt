@file:Suppress("unused") // TODO

import javafx.scene.Scene
import javafx.scene.image.Image
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

fun openNewWindow(view: UIComponent) {
    val newstage = Stage()
    newstage.scene = Scene(view.root)
    newstage.show()
}



//class SSBApp : App(BookmarksView::class, Styles::class) {
class SSBApp : App(Workspace::class, Styles::class) { // TODO remove workspace or leave to wait for improvements?

    private val bookmarksView = BookmarksView()


    override fun onBeforeShow(view: UIComponent) {
        //workspace.dock<BookmarksView>()
        workspace.dock(bookmarksView)
    }

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
