package io.github.yudigaga.taplusbridge.hook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HostPolicyTest {
    @Test public void hostRequiresBothInspectedNameAndCode() {
        assertTrue(HostPolicy.supportedVersion("4.1.3", 40013));
        assertTrue(HostPolicy.supportedVersion("5.1.5", 50015));
        assertFalse(HostPolicy.supportedVersion("5.1.5", 50016));
        assertFalse(HostPolicy.supportedVersion("5.1.6", 50015));
        assertFalse(HostPolicy.supportedVersion("5.1.5", 40013));
        assertFalse(HostPolicy.supportedVersion("4.1.3", 50015));
        assertFalse(HostPolicy.supportedVersion("4.1.3", 40014));
        assertFalse(HostPolicy.supportedVersion("4.1.4", 40013));
        assertFalse(HostPolicy.supportedVersion(null, 40013));
        assertFalse(HostPolicy.supportedVersion(null, 50015));
    }

    @Test public void targetCannotLoopBackToHostOrBridge() {
        assertTrue(HostPolicy.validTarget("com.ss.android.ugc.aweme"));
        assertFalse(HostPolicy.validTarget(HostPolicy.HOST_PACKAGE));
        assertFalse(HostPolicy.validTarget(HostPolicy.MODULE_PACKAGE));
        assertFalse(HostPolicy.validTarget(null));
        assertFalse(HostPolicy.validTarget("com.example;am start"));
        assertFalse(HostPolicy.validTarget("com..example"));
        assertFalse(HostPolicy.validTarget("com.example\n"));
    }

    @Test public void translationRoutesAlwaysKeepTheirHostFlow() {
        assertTrue(HostPolicy.isTranslation("EN_2_CH_PARA"));
        assertTrue(HostPolicy.isTranslation("EN_2_CH_WORD"));
        assertTrue(HostPolicy.isTranslation("translation"));
        assertFalse(HostPolicy.isTranslation("URL"));
        assertFalse(HostPolicy.isTranslation(null));
    }

    @Test public void launcherFallbackIsRestrictedToHostSpecialCases() {
        assertTrue(HostPolicy.hostUsesLauncher("com.ss.android.ugc.aweme"));
        assertTrue(HostPolicy.hostUsesLauncher("com.taobao.taobao"));
        assertFalse(HostPolicy.hostUsesLauncher("tv.danmaku.bili"));
        assertFalse(HostPolicy.hostUsesLauncher(null));
    }

    @Test public void callsMailSharingAndUnknownActionsStayWithHost() {
        assertTrue(HostPolicy.supportsHandoffAction("android.intent.action.VIEW"));
        assertTrue(HostPolicy.supportsHandoffAction("android.intent.action.MAIN"));
        assertFalse(HostPolicy.supportsHandoffAction("android.intent.action.DIAL"));
        assertFalse(HostPolicy.supportsHandoffAction("android.intent.action.SENDTO"));
        assertFalse(HostPolicy.supportsHandoffAction("android.intent.action.SEND"));
        assertFalse(HostPolicy.supportsHandoffAction("miui.intent.action.screenscanner"));
        assertFalse(HostPolicy.supportsHandoffAction(null));
    }
}
