package util

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}


@Suppress("unused", "MemberVisibilityCanBePrivate")
class StopWatch { // all in seconds!
    var deltaSecs: Double = 0.0
    private var startNanos = System.nanoTime()
    fun doit(interval: Double): Boolean =
            if (getElapsedTime() > interval) {
                restart()
                true
            } else false


    private fun getElapsedTime(): Double = (System.nanoTime() - startNanos) / 1e9
    fun getTimeRestart(): Double {
        val x = getElapsedTime()
        restart()
        return x
    }

    fun stopGetTimeString(): String { // a little overhead... 0.13s
        if (deltaSecs == 0.0) stop()
        return "%g s".format(deltaSecs)
    }

    fun stopPrintTime(msg: String) {
        logger.info(msg + stopGetTimeString())
    }

    fun printLapTime(msg: String) {
        logger.info(msg + getElapsedTime())
    }

    fun restart() {
        deltaSecs = 0.0
        startNanos = System.nanoTime()
    }

    fun stop() { // fast stopping
        deltaSecs = (System.nanoTime() - startNanos) / 1e9
    }

}
