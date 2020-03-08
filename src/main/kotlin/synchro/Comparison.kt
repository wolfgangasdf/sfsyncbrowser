package synchro

import mu.KotlinLogging
import store.Cache
import synchro.Actions.A_ISEQUAL
import synchro.Actions.A_RMLOCAL
import synchro.Actions.A_RMREMOTE
import synchro.Actions.A_UNKNOWN
import util.Helpers.getParentFolder
import util.StopWatch

private val logger = KotlinLogging.logger {}


object Actions {
    const val A_UNCHECKED: Int = -99
    const val A_UNKNOWN: Int = -1
    const val A_ISEQUAL = 0
    const val A_USELOCAL = 1
    const val A_USEREMOTE = 2
    const val A_MERGE = 3
    const val A_RMLOCAL = 4
    const val A_RMREMOTE = 5
    const val A_CACHEONLY = 6
    const val A_RMBOTH = 7
    const val A_SYNCERROR = 8
    const val A_SKIP = 9
    val ALLACTIONS = listOf(-99, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
}

object Comparison {
    // compare, update database entries only.
    fun compareSyncEntries(cache: Cache, useNewFiles: Boolean = false): Boolean {
        logger.debug("compare sync entries...")
        val swse = StopWatch()
        // q contains all entries to be checked

        // to avoid first full sync: mark all folders that are subfolders of already synced folder
        // update hasCachedParent for all folders not in cache and check if it has parent folder that has been synced before in current set
        cache.cache.iterate { _, path, se ->
            //logger.debug("cache it: $path")
            if (se.relevant && se.isDir) {
                var tmpf = path
                var haveCachedParentDir = false
                var doit = true
                while (!haveCachedParentDir && doit) {
                    tmpf = getParentFolder(tmpf)
                    if (tmpf == "/") tmpf = ""
                    if (cache.cache.containsKey(tmpf))
                        if (cache.cache[tmpf]!!.cSize != -1L) haveCachedParentDir = true
                    if (tmpf == "") doit = false
                }
                se.hasCachedParent = haveCachedParentDir
                se.compareSetAction(useNewFiles = haveCachedParentDir || useNewFiles) // compare
            }
        }
        logger.debug("TTT a took = " + swse.getTimeRestart())

        // iterate over all folders that are cached
        cache.cache.iterate { _, _, se ->
            if (se.relevant && se.isDir && se.cSize != -1L) {
                se.hasCachedParent = true
                se.compareSetAction(useNewFiles = true) // compare
            }
        }
        logger.debug("TTT b took = " + swse.getTimeRestart())

        // iterate over the rest: all files.
        cache.cache.iterate { _, path, se ->
            if (se.relevant && !se.isDir) {
                if (se.cSize == -1L) { // only get parent folder for unknown files, faster!
                    val parent = getParentFolder(path)
                    if (cache.cache[parent]?.hasCachedParent != true && !useNewFiles) {
                        se.compareSetAction(useNewFiles = false)
                    } else {
                        se.compareSetAction(useNewFiles = true)
                    }
                } else {
                    se.compareSetAction(useNewFiles = true)
                }
            }
        }
        logger.debug("TTT c took = " + swse.getTimeRestart())

        // iterate over all folders that will be deleted: check that other side is not modified below
        cache.cache.iterate { _, path, se ->
            if (se.relevant && se.isDir && listOf(A_RMLOCAL, A_RMREMOTE).contains(se.action)) {
                var fishy = false
                cache.cache.iterate { _, path2, se2 ->
                    if (se.relevant && path2.startsWith(path) && se2.action != se.action) {
                        fishy = true
                    }
                }
                if (fishy) {
                    cache.cache.iterate { _, path2, se2 ->
                        if (se.relevant && path2.startsWith(path)) {
                            se2.action = A_UNKNOWN
                        }
                    }
                }
            }
        }
        logger.debug("TTT d took = " + swse.getTimeRestart())

        // return true if changes
        var res = false
        cache.cache.iterate { _, _, se -> if (se.relevant && se.action != A_ISEQUAL) res = true }
        logger.debug("TTT e took = " + swse.getTimeRestart())
        return res
    }
}
