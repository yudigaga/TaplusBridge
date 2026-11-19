package io.github.yudigaga.taplusbridge.hook;

import static org.junit.Assert.*;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import io.github.yudigaga.taplusbridge.bridge.BridgeContract;
import io.github.yudigaga.taplusbridge.config.RecognitionChannels;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class RecognitionPolicyDiagnosticsAndroidTest {
    @Test public void eachReadReportsItsOwnProviderDurationAndPublicationIdentity() {
        PolicyProvider provider = register();
        RecognitionPolicy.State first = RecognitionPolicy.current(RuntimeEnvironment.getApplication());
        assertEquals(1, provider.calls);
        assertEquals("a".repeat(64), first.identity());
        assertEquals("ready", first.status());
        assertEquals(25, first.readMillis());
        provider.identity = "b".repeat(64);
        RecognitionPolicy.State second = RecognitionPolicy.current(RuntimeEnvironment.getApplication());
        assertEquals(2, provider.calls);
        assertNotEquals(first.identity(), second.identity());
        assertEquals("a".repeat(64), first.identity());
    }

    @Test public void unavailablePublicationReportsReasonWithoutInventingIdentity() {
        PolicyProvider provider = register(); provider.active = false;
        RecognitionPolicy.State state = RecognitionPolicy.current(RuntimeEnvironment.getApplication());
        assertFalse(state.managed());
        assertEquals("", state.identity());
        assertEquals("publication_unavailable", state.status());
        assertEquals(25, state.readMillis());
    }

    @Test public void diagnosticIdentityNeverEchoesArbitraryProviderText() {
        PolicyProvider provider = register(); provider.identity = "private title https://example.com/?token=hidden";
        RecognitionPolicy.State state = RecognitionPolicy.current(RuntimeEnvironment.getApplication());
        assertFalse(state.diagnosticFields().contains("private"));
        assertFalse(state.diagnosticFields().contains("https://"));
        assertFalse(state.diagnosticFields().contains("hidden"));
    }

    private static PolicyProvider register() {
        PolicyProvider provider = Robolectric.buildContentProvider(PolicyProvider.class).create(BridgeContract.AUTHORITY).get();
        ShadowContentResolver.registerProviderInternal(BridgeContract.AUTHORITY, provider); return provider;
    }

    public static final class PolicyProvider extends ContentProvider {
        int calls; boolean active = true; String identity = "a".repeat(64);
        public boolean onCreate() { return true; }
        public Bundle call(String method, String arg, Bundle input) {
            assertEquals(RecognitionChannels.KEY, method); calls++;
            ShadowSystemClock.advanceBy(Duration.ofMillis(25));
            Bundle result = new Bundle(); result.putBoolean("active", active);
            result.putString("identity", identity); result.putString("channels", RecognitionChannels.defaults().toJson());
            return result;
        }
        public Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;}
        public String getType(Uri u){return null;}
        public Uri insert(Uri u,ContentValues v){return null;}
        public int delete(Uri u,String s,String[] a){return 0;}
        public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
    }
}
