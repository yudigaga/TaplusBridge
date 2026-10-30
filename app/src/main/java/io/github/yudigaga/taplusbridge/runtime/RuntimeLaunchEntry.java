package io.github.yudigaga.taplusbridge.runtime;

/** Shared public-entry eligibility used by the actual ATMS hook and regression tests. */
public final class RuntimeLaunchEntry {
    private RuntimeLaunchEntry() { }
    public static boolean accepts(Object resultTo,int requestCode,Object profiler) {
        // requestCode has no result recipient when resultTo is null. Stock Taplus uses 0;
        // Context/Instrumentation normally use -1. Neither requests a callback in this case.
        return resultTo==null && profiler==null;
    }
    public static boolean dispatch(RuntimeServer server,String callingPackage,int uid,int user,
                                   android.content.Intent intent,Object resultTo,int requestCode,Object profiler) {
        return server!=null && accepts(resultTo,requestCode,profiler) && server.intercept(callingPackage,uid,user,intent);
    }
}
