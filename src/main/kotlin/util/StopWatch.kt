
package util

// all in seconds!
class StopWatch {
  var deltaSecs: Double = 0.0
  private var startNanos = System.nanoTime()
  fun doit(interval: Double): Boolean =
    if (getTime() > interval) {
      restart()
      true
    } else false


  fun getTime() = (System.nanoTime() - startNanos)/1e9
  fun getTimeRestart() = {
    val x = getTime()
    restart()
    x
  }
  fun stopGetTimeString(): String { // a little overhead... 0.13s
    if (deltaSecs == 0.0) stop()
    return "%g s".format(deltaSecs)
  }
  fun stopPrintTime(msg: String) {
    println(msg + stopGetTimeString())
  }
  fun printLapTime(msg: String) {
    println(msg + getTime())
  }
  fun restart() {
    deltaSecs = 0.0
    startNanos = System.nanoTime()
  }
  fun stop() { // fast stopping
    deltaSecs = (System.nanoTime() - startNanos)/1e9
  }

}
