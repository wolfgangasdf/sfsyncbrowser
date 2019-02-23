import mu.KotlinLogging
import store.DBSettings
import tornadofx.launch
import util.Helpers
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files

// do not initialize logger in Main.kt, first set properties!

fun main(args: Array<String>) {

    val oldOut: PrintStream = System.out
    val oldErr: PrintStream = System.err
    var logps: FileOutputStream? = null
    class MyConsole(val errchan: Boolean): OutputStream() {
        override fun write(b: Int) {
            logps?.write(b)
            (if (errchan) oldErr else oldOut).print(b.toChar().toString())
        }
    }
    System.setOut(PrintStream(MyConsole(false), true))
    System.setErr(PrintStream(MyConsole(true), true))

    System.setProperty("org.slf4j.simpleLogger.log.net.schmizz", "INFO")
    System.setProperty("org.slf4j.simpleLogger.log.io.methvin.watcher", "INFO")
    System.setProperty(org.slf4j.simple.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "TRACE")
    System.setProperty(org.slf4j.simple.SimpleLogger.SHOW_DATE_TIME_KEY, "true")
    System.setProperty(org.slf4j.simple.SimpleLogger.DATE_TIME_FORMAT_KEY, "yyyy-MM-dd HH:mm:ss:SSS")
    System.setProperty(org.slf4j.simple.SimpleLogger.LOG_FILE_KEY, "System.out") // and use intellij "grep console" plugin

    val logfile = Files.createTempFile("reftool5log",".txt").toFile()
    logps = FileOutputStream(logfile)

    val logger = KotlinLogging.logger {} // after set properties!

    logger.error("error")
    logger.warn("warn")
    logger.info("info")
    logger.debug("debug")
    logger.trace("trace")

    logger.info("SSyncBrowser built ${Helpers.getClassBuildTime().toString()}")
    logger.info("Log file: ${logfile.path}")
    DBSettings.logFile = logfile

    launch<SSBApp>(*args)
}
