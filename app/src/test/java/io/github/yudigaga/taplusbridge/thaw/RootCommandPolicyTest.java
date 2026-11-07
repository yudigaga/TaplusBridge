package io.github.yudigaga.taplusbridge.thaw;

import org.junit.Test;

import static org.junit.Assert.*;

public class RootCommandPolicyTest {
    @Test
    public void unfreezeAlwaysNamesCurrentProfileExplicitly() {
        String command = RootCommandPolicy.enableCommand("com.example.Target_app", 10);
        assertEquals("pm enable --user 10 com.example.Target_app && pm unhide --user 10 com.example.Target_app", command);
        assertFalse(command.contains("am start"));
    }

    @Test
    public void shellMetacharactersCannotBecomePackageOperations() {
        String[] untrusted = { null, "", "android", "com.example app", "com.example;id",
                "com.example\nid", "com.example$(id)", "com.example`id`", "-p com.example",
                "com.example/Activity", "com..example", "com.example\u0000id", "com.example'" };
        for (String candidate : untrusted) {
            assertFalse(String.valueOf(candidate), RootCommandPolicy.validPackage(candidate));
            try {
                RootCommandPolicy.enableCommand(candidate, 0);
                fail("Must reject " + candidate);
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test
    public void unknownProfileCannotFallBackToOwner() {
        try {
            RootCommandPolicy.enableCommand("com.example.app", -1);
            fail("Unknown user must not be replaced with zero");
        } catch (IllegalArgumentException expected) { }
    }
}
