import storeold.Cache
import storeold.SyncEntry
import synchro.Actions.A_ISEQUAL
import synchro.Actions.A_UNCHECKED
import synchro.Actions.A_CACHEONLY
import synchro.Actions.A_RMLOCAL
import synchro.Actions.A_RMREMOTE
import synchro.Actions.A_UNKNOWN
import synchro.Actions.A_USELOCAL
import synchro.Actions.A_USEREMOTE
import synchro.Comparison

object Checks {
    fun checkComparedFile() {
        val mod0: Long = 12340000
        val mod1: Long = 12350000
        val mod2: Long = 12360000
        val s0: Long = 1000
        val s1: Long = 1001

        class CheckEntry(val expectedAction: Int, val path: String, val se: SyncEntry)

        Cache.iniCache()
        // setup cachedb
        // stuff in existing & synced subfolder
        val ces = mutableListOf<CheckEntry>()
        ces += CheckEntry(A_ISEQUAL, "/sf1/", SyncEntry(A_UNCHECKED, mod0, s0, mod0, s0, mod0, mod0, s0, true, true))
        ces += CheckEntry(A_ISEQUAL, "/sf1/fileequal", SyncEntry(A_UNCHECKED, mod0, s0, mod0, s0, mod0, mod0, s0, false, true))
        ces += CheckEntry(A_USEREMOTE, "/sf1/fileremmod-t", SyncEntry(A_UNCHECKED, mod0, s0, mod1, s0, mod0, mod0, s0, false, true))
        ces += CheckEntry(A_UNKNOWN, "/sf1/fileremmod-s(strange)", SyncEntry(A_UNCHECKED, mod0, s0, mod0, s1, mod0, mod0, s0, false, true))
        ces += CheckEntry(A_USELOCAL, "/sf1/filelocmod-t", SyncEntry(A_UNCHECKED, mod1, s0, mod0, s0, mod0, mod0, s0, false, true))
        ces += CheckEntry(A_UNKNOWN, "/sf1/filelocmod-s(strange)", SyncEntry(A_UNCHECKED, mod0, s1, mod0, mod0, s0, mod0, s0, false, true))
        ces += CheckEntry(A_USELOCAL, "/sf1/filelocnew", SyncEntry(A_UNCHECKED, mod0, s0, -1, -1, -1, -1, -1, false, true))
        ces += CheckEntry(A_USEREMOTE, "/sf1/fileremnew", SyncEntry(A_UNCHECKED, -1, -1, mod0, s0, -1, -1, -1, false, true))
        ces += CheckEntry(A_USELOCAL, "/sf1/foldlocnew/", SyncEntry(A_UNCHECKED, mod0, s0, -1, -1, -1, -1, -1, true, true))
        ces += CheckEntry(A_USEREMOTE, "/sf1/foldremnew/", SyncEntry(A_UNCHECKED, -1, -1, mod0, s0, -1, -1, -1, true, true))

        // new checks if remote can't set date
        ces += CheckEntry(A_ISEQUAL, "/sf1/filexequal", SyncEntry(A_UNCHECKED, mod0, s0, mod1, s0, mod0, mod1, s0, false, true))
        ces += CheckEntry(A_RMREMOTE, "/sf1/filexrmrem", SyncEntry(A_UNCHECKED, -1, -1, mod1, s0, mod0, mod1, s0, false, true))

        // old checks
        ces += CheckEntry(A_ISEQUAL, "/sf0/", SyncEntry(A_UNCHECKED, mod0, 0, mod0, 0, mod0, 0, 0, true, true))
        ces += CheckEntry(A_CACHEONLY, "/sf0/file01", SyncEntry(A_UNCHECKED, -1, -1, -1, -1, mod0, mod0, s0, false, true)) // cache only?
        ces += CheckEntry(A_ISEQUAL, "/sf0/file02", SyncEntry(A_UNCHECKED, mod0, s0, mod0, s0, mod0, mod0, s0, false, true)) // just equal?
        ces += CheckEntry(A_UNKNOWN, "/sf0/file03", SyncEntry(A_UNCHECKED, mod0, s0, mod1, s0, -1, -1, -1, false, true)) // not equal and not in cache. unknown!
        ces += CheckEntry(A_UNKNOWN, "/sf0/file04", SyncEntry(A_UNCHECKED, mod0, s1, mod0, s0, -1, -1, -1, false, true)) // not equal and not in cache. unknown!

        // new path with file in synced subfolder
        ces += CheckEntry(A_ISEQUAL, "/sf2/", SyncEntry(A_UNCHECKED, mod0, s0, mod0, s0, mod0, mod0, s0, true, true))
        ces += CheckEntry(A_USEREMOTE, "/sf2/foldremnew/", SyncEntry(A_UNCHECKED, -1, -1, mod0, mod0, s0, -1, -1, true, true))
        ces += CheckEntry(A_USEREMOTE, "/sf2/foldremnew/file", SyncEntry(A_UNCHECKED, -1, -1, mod0, mod0, s0, -1, -1, false, true))
        ces += CheckEntry(A_USELOCAL, "/sf2/foldlocnew/", SyncEntry(A_UNCHECKED, mod0, s0, -1, -1, -1, -1, -1, true, true))
        ces += CheckEntry(A_USELOCAL, "/sf2/foldlocnew/file", SyncEntry(A_UNCHECKED, mod0, s0, -1, -1, -1, -1, -1, false, true))
        // new path with file in NOT synced subfolder
        ces += CheckEntry(A_UNKNOWN, "/sf3/", SyncEntry(A_UNCHECKED, -1, -1, mod0, s0, -1, -1, -1, true, true))
        ces += CheckEntry(A_UNKNOWN, "/sf3/foldremnew/", SyncEntry(A_UNCHECKED, -1, -1, mod0, s0, -1, -1, -1, true, true))
        ces += CheckEntry(A_UNKNOWN, "/sf3/foldremnew/file", SyncEntry(A_UNCHECKED, -1, -1, mod0, s0, -1, -1, -1, false, true))
        ces += CheckEntry(A_UNKNOWN, "/sf4/", SyncEntry(A_UNCHECKED, mod0, s0, -1, -1, -1, -1, -1, true, true))
        ces += CheckEntry(A_UNKNOWN, "/sf4/foldlocnew/", SyncEntry(A_UNCHECKED, mod0, s0, -1, -1, -1, -1, -1, true, true))
        ces += CheckEntry(A_UNKNOWN, "/sf4/foldlocnew/file", SyncEntry(A_UNCHECKED, mod0, s0, -1, -1, -1, -1, -1, false, true))

        // unsynced subfolder, some equal and unequal files
        ces += CheckEntry(A_ISEQUAL, "/sf5/", SyncEntry(A_UNCHECKED, mod0, s0, mod0, s0, -1, -1, -1, true, true))
        ces += CheckEntry(A_ISEQUAL, "/sf5/fold/", SyncEntry(A_UNCHECKED, mod0, s0, mod0, s0, -1, -1, -1, true, true))
        ces += CheckEntry(A_ISEQUAL, "/sf5/fold/file1", SyncEntry(A_UNCHECKED, mod0, s0, mod0, s0, -1, -1, -1, false, true))
        ces += CheckEntry(A_UNKNOWN, "/sf5/fold/file2", SyncEntry(A_UNCHECKED, mod0, s0, mod1, s1, -1, -1, -1, false, true))
        ces += CheckEntry(A_UNKNOWN, "/sf5/fold/file3", SyncEntry(A_UNCHECKED, mod0, s0, -1, -1, -1, -1, -1, false, true)) // deleted or not?

        // same with synced subfolder
        ces += CheckEntry(A_ISEQUAL, "/sf6/", SyncEntry(A_UNCHECKED, mod0, s0, mod0, s0, mod0, mod0, s0, true, true))
        ces += CheckEntry(A_ISEQUAL, "/sf6/fold/", SyncEntry(A_UNCHECKED, mod0, s0, mod0, s0, -1, -1, -1, true, true))
        ces += CheckEntry(A_ISEQUAL, "/sf6/fold/file1", SyncEntry(A_UNCHECKED, mod0, s0, mod0, s0, -1, -1, -1, false, true))
        ces += CheckEntry(A_UNKNOWN, "/sf6/fold/file2", SyncEntry(A_UNCHECKED, mod0, s0, mod1, s1, -1, -1, -1, false, true))
        ces += CheckEntry(A_USELOCAL, "/sf6/fold/file3", SyncEntry(A_UNCHECKED, mod0, s0, -1, -1, -1, -1, -1, false, true)) // deleted or not?

        // old checks
        ces += CheckEntry(A_ISEQUAL, "/sf7ca/", SyncEntry(A_UNCHECKED, mod0, s0, mod0, s0, mod0, mod0, s0, true, true))
        ces += CheckEntry(A_USELOCAL, "/sf7ca/filenewloc", SyncEntry(A_UNCHECKED, mod0, s0, -1, -1, -1, -1, -1, false, true))
        ces += CheckEntry(A_USEREMOTE, "/sf7ca/filenewrem", SyncEntry(A_UNCHECKED, -1, -1, mod0, s0, -1, -1, -1, false, true))
        ces += CheckEntry(A_UNKNOWN, "/sf7ca/filenewerlocnocache", SyncEntry(A_UNCHECKED, mod1, s0, mod0, s0, -1, -1, -1, false, true))
        ces += CheckEntry(A_UNKNOWN, "/sf7ca/filenewerremnocache", SyncEntry(A_UNCHECKED, mod0, s0, mod1, s0, -1, -1, -1, false, true))
        ces += CheckEntry(A_UNKNOWN, "/sf7ca/filesizediffnocache", SyncEntry(A_UNCHECKED, mod0, s0, mod0, s1, -1, -1, -1, false, true))
        // with cache
        ces += CheckEntry(A_RMLOCAL, "/sf7ca/filesremdelcache", SyncEntry(A_UNCHECKED, mod0, s0, -1, -1, mod0, mod0, s0, false, true))
        ces += CheckEntry(A_RMREMOTE, "/sf7ca/fileslocdelcache", SyncEntry(A_UNCHECKED, -1, -1, mod0, s0, mod0, mod0, s0, false, true))
        ces += CheckEntry(A_UNKNOWN, "/sf7ca/filesremdellocmodcacheold", SyncEntry(A_UNCHECKED, mod1, s1, -1, -1, mod0, mod0, s0, false, true))
        ces += CheckEntry(A_UNKNOWN, "/sf7ca/fileslocdelremmodcacheold", SyncEntry(A_UNCHECKED, -1, -1, mod1, s1, mod0, mod0, s0, false, true))
        ces += CheckEntry(A_USEREMOTE, "/sf7ca/filesremmodcache", SyncEntry(A_UNCHECKED, mod0, s0, mod1, s0, mod0, mod0, s0, false, true))
        ces += CheckEntry(A_USELOCAL, "/sf7ca/fileslocmodcache", SyncEntry(A_UNCHECKED, mod1, s0, mod0, s0, mod0, mod0, s0, false, true))
        ces += CheckEntry(A_UNKNOWN, "/sf7ca/filesremmodlocmodcache", SyncEntry(A_UNCHECKED, mod2, s0, mod1, s0, mod0, mod0, s0, false, true))
        ces += CheckEntry(A_UNKNOWN, "/sf7ca/fileslocmodremmodcache", SyncEntry(A_UNCHECKED, mod1, s0, mod2, s0, mod0, mod0, s0, false, true))
        ces += CheckEntry(A_UNKNOWN, "/sf7ca/filessizeloccache", SyncEntry(A_UNCHECKED, mod0, s1, mod0, s0, mod0, mod0, s0, false, true))
        ces += CheckEntry(A_UNKNOWN, "/sf7ca/filessizeremcache", SyncEntry(A_UNCHECKED, mod0, s0, mod0, s1, mod0, mod0, s0, false, true))

        // cached, then delete local dir, but added/changed files remote: must all be ?
        ces += CheckEntry(A_UNKNOWN, "/sf8/", SyncEntry(A_UNCHECKED, -1, -1, mod0, s0, mod0, mod0, s0, true, true))
        ces += CheckEntry(A_UNKNOWN, "/sf8/filedelloc", SyncEntry(A_UNCHECKED, -1, -1, mod0, s0, mod0, mod0, s0, false, true))
        ces += CheckEntry(A_UNKNOWN, "/sf8/fileaddrem", SyncEntry(A_UNCHECKED, -1, -1, mod0, mod0, s0, -1, -1, false, true))
        ces += CheckEntry(A_UNKNOWN, "/sf8a/", SyncEntry(A_UNCHECKED, -1, -1, mod0, s0, mod0, mod0, s0, true, true))
        ces += CheckEntry(A_UNKNOWN, "/sf8a/filedelloc", SyncEntry(A_UNCHECKED, -1, -1, mod0, s0, mod0, mod0, s0, false, true))
        ces += CheckEntry(A_UNKNOWN, "/sf8a/filemodrem", SyncEntry(A_UNCHECKED, mod0, s0, mod1, s1, mod0, mod0, s0, false, true))
        ces += CheckEntry(A_UNKNOWN, "/sf8c/", SyncEntry(A_UNCHECKED, -1, -1, mod0, s0, mod0, mod0, s0, true, true))
        ces += CheckEntry(A_UNKNOWN, "/sf8c/filemodrem", SyncEntry(A_UNCHECKED, -1, -1, mod1, s1, mod0, mod0, s0, false, true))


        // insert stuff
        ces.forEach { ce -> Cache.cache[ce.path] = ce.se }

//    println("**** initial:")
//    Cache.dumpAll()

        Comparison.compareSyncEntries()

        // check if ok
        println("**** checks:")
        var fail = false
        ces.forEach { ce ->
            val senew = Cache.cache[ce.path]
            println(
                    (if (senew!!.action == ce.expectedAction) "" else {fail = true; "XX"})
                    +  "[${ce.path}]: action=[${CF.amap[senew.action]}] (expected [${CF.amap[ce.expectedAction]}])")
        }

        assert(!fail)
    }
}
