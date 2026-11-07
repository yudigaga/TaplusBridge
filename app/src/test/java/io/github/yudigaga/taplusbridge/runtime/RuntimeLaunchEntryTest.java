package io.github.yudigaga.taplusbridge.runtime;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RuntimeLaunchEntryTest {
    @Test public void stockTaplusStartActivityHasNoResultEvenWithRequestCodeZero() {
        // 4.1.3 AppsUtils.startActivity uses resultTo=null, requestCode=0, profiler=null.
        assertTrue(RuntimeLaunchEntry.accepts(null,0,null));
    }
    @Test public void ordinaryContextStartRemainsSupported() {
        assertTrue(RuntimeLaunchEntry.accepts(null,-1,null));
    }
    @Test public void aRealResultCallbackOrProfilingRequestRemainsUntouched() {
        assertFalse(RuntimeLaunchEntry.accepts(new Object(),0,null));
        assertFalse(RuntimeLaunchEntry.accepts(new Object(),-1,null));
        assertFalse(RuntimeLaunchEntry.accepts(null,0,new Object()));
    }
}
