@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package util

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}


// all in seconds!
class StopWatch {
    var deltaSecs: Double = 0.0
    private var startNanos = System.nanoTime()
    fun doit(interval: Double): Boolean =
            if (getTime() > interval) {
                restart()
                true
            } else false


    fun getTime() = (System.nanoTime() - startNanos) / 1e9
    fun getTimeRestart(): Double {
        val x = getTime()
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
        logger.info(msg + getTime())
    }

    fun restart() {
        deltaSecs = 0.0
        startNanos = System.nanoTime()
    }

    fun stop() { // fast stopping
        deltaSecs = (System.nanoTime() - startNanos) / 1e9
    }

}
