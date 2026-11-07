package io.github.yudigaga.taplusbridge.capture;

import java.lang.reflect.Modifier;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AtmsSignatureTest {
    private static String[] expected() {
        return new String[]{"android.app.IApplicationThread", "java.lang.String", "java.lang.String",
                "android.content.Intent", "java.lang.String", "android.os.IBinder", "java.lang.String",
                "int", "int", "android.app.ProfilerInfo", "android.os.Bundle", "int"};
    }

    @Test public void exactPublicInstanceSignatureIsAccepted() {
        assertTrue(AtmsSignature.matches("startActivityAsUser", Modifier.PUBLIC, "int", expected()));
        assertTrue(AtmsSignature.matches("startActivityAsUser", Modifier.PUBLIC | Modifier.FINAL, "int", expected()));
    }

    @Test public void otherMethodsVisibilityAndReturnTypesAreRejected() {
        assertFalse(AtmsSignature.matches("startActivity", Modifier.PUBLIC, "int", expected()));
        assertFalse(AtmsSignature.matches("startActivityAsUser", Modifier.PRIVATE, "int", expected()));
        assertFalse(AtmsSignature.matches("startActivityAsUser", Modifier.PUBLIC | Modifier.STATIC, "int", expected()));
        assertFalse(AtmsSignature.matches("startActivityAsUser", Modifier.PUBLIC, "void", expected()));
    }

    @Test public void changedOverloadsAndEveryWrongParameterAreRejected() {
        assertFalse(AtmsSignature.matches("startActivityAsUser", Modifier.PUBLIC, "int", new String[0]));
        assertFalse(AtmsSignature.matches("startActivityAsUser", Modifier.PUBLIC, "int", (String[]) null));
        String[] valid = expected();
        for (int i = 0; i < valid.length; i++) {
            String[] altered = valid.clone(); altered[i] = "java.lang.Object";
            assertFalse("Parameter " + i, AtmsSignature.matches("startActivityAsUser", Modifier.PUBLIC, "int", altered));
        }
        String[] swapped = expected();
        swapped[0] = "android.os.IBinder"; swapped[5] = "android.app.IApplicationThread";
        assertFalse(AtmsSignature.matches("startActivityAsUser", Modifier.PUBLIC, "int", swapped));
    }

    @Test public void reflectionOverloadRejectsNullAndUnrelatedMethod() throws Exception {
        assertFalse(AtmsSignature.matches((java.lang.reflect.Method) null));
        assertFalse(AtmsSignature.matches(String.class.getMethod("length")));
    }
}
