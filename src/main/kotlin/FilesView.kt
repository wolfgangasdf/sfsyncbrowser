import synchro.Actions.A_CACHEONLY
import synchro.Actions.A_ISEQUAL
import synchro.Actions.A_MERGE
import synchro.Actions.A_RMBOTH
import synchro.Actions.A_RMLOCAL
import synchro.Actions.A_RMREMOTE
import synchro.Actions.A_SKIP
import synchro.Actions.A_SYNCERROR
import synchro.Actions.A_UNCHECKED
import synchro.Actions.A_UNKNOWN
import synchro.Actions.A_USELOCAL
import synchro.Actions.A_USEREMOTE

object CF {
    val amap = mapOf(
            A_MERGE to "M",
    A_ISEQUAL to "==",
    A_RMLOCAL to "<-(rm)",
    A_RMREMOTE to "(rm)to",
    A_UNKNOWN to "?",
    A_USELOCAL to "to",
    A_USEREMOTE to "<-",
    A_CACHEONLY to "C",
    A_RMBOTH to "<-rmto",
    A_UNCHECKED to "???",
    A_SYNCERROR to "SE!",
    A_SKIP to "skip"
    )
    fun stringToAction(actionString: String): Int {
        val x = amap.entries.associate{(k,v)-> v to k}
        return x.get(actionString)!!
    }
    fun stringToColor(actionString: String): String {
        val cmap = mapOf( // http://docs.oracle.com/javafx/2/api/javafx/scene/doc-files/cssref.html#typecolor
                A_MERGE to "salmon",
        A_ISEQUAL to "white",
        A_RMLOCAL to "salmon",
        A_RMREMOTE to "salmon",
        A_UNKNOWN to "red",
        A_USELOCAL to "lightgreen",
        A_USEREMOTE to "lightgreen",
        A_CACHEONLY to "salmon",
        A_RMBOTH to "salmon",
        A_UNCHECKED to "red",
        A_SYNCERROR to "red",
        A_SKIP to "salmon"
        )
        val a = stringToAction(actionString)
        return cmap.get(a)!!
    }
}
