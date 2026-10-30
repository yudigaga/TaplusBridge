package io.github.yudigaga.taplusbridge.hook;

import android.os.AsyncTask;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

/** Invalidating an epoch comes first; cancellation alone is insufficient for already queued callbacks. */
public final class RecognitionTaskCleanup {
    private RecognitionTaskCleanup() { }
    public static void reset(Field segment,Field recommendation,Field ocr,Field segmenting) throws ReflectiveOperationException {
        try { cancel(segment==null?null:segment.get(null),recommendation==null?null:recommendation.get(null),
                ocr==null?null:ocr.get(null)); }
        finally { if(segmenting!=null)segmenting.setBoolean(null,false); }
    }
    @SuppressWarnings("deprecation")
    public static void cancel(Object segment,Object recommendation,Object ocr) throws ReflectiveOperationException {
        if(segment instanceof AsyncTask<?,?,?> task)task.cancel(true);
        if(recommendation instanceof AsyncTask<?,?,?> task && recommendation!=segment)task.cancel(true);
        if(ocr!=null) {
            Method dispose=ocr.getClass().getMethod("dispose");dispose.setAccessible(true);dispose.invoke(ocr);
        }
    }
}
