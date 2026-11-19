package io.github.yudigaga.taplusbridge.probe;

import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.SystemClock;
import java.time.Duration;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAudioManager;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class SourceRecordingStateAndroidTest {
    private static final int UID = 10123;
    private Context context;
    private ShadowAudioManager audio;
    private SourceRecordingState.Source source;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        audio = (ShadowAudioManager) Shadow.extract(
                (AudioManager) context.getSystemService(Context.AUDIO_SERVICE));
        source = SourceRecordingState.from(() -> context);
        ShadowSystemClock.advanceBy(Duration.ofMillis(100));
    }

    @Test public void sameUidThroughPublicProviderReturnsQueryStartTime() {
        audio.setActiveRecordingConfigurations(List.of(recording(UID)), false);
        long began = SystemClock.elapsedRealtime();
        assertEquals(new SourceRecordingState.Observation(UID, began), source.observe(UID));
    }

    @Test public void timestampPrecedesContextResolutionInsteadOfUsingQueryCompletion() {
        audio.setActiveRecordingConfigurations(List.of(recording(UID)), false);
        SourceRecordingState.Source delayed = SourceRecordingState.from(() -> {
            ShadowSystemClock.advanceBy(Duration.ofMillis(40));
            return context;
        });
        long began = SystemClock.elapsedRealtime();
        assertEquals(new SourceRecordingState.Observation(UID, began), delayed.observe(UID));
        assertEquals(began + 40, SystemClock.elapsedRealtime());
    }

    @Test public void primaryUserAppUidBoundariesAreAccepted() {
        audio.setActiveRecordingConfigurations(List.of(recording(10000), recording(99999)), false);
        long began = SystemClock.elapsedRealtime();
        assertEquals(new SourceRecordingState.Observation(10000, began), source.observe(10000));
        assertEquals(new SourceRecordingState.Observation(99999, began), source.observe(99999));
    }

    @Test public void invalidOrNonPrimaryUserQueryNeverObtainsContext() {
        AtomicInteger reads = new AtomicInteger();
        SourceRecordingState.Source guarded = SourceRecordingState.from(() -> {
            reads.incrementAndGet();
            return context;
        });
        for (int uid : new int[] {-1, 0, 1000, 9999, 100000, 110123, Integer.MAX_VALUE}) {
            assertUnknown(guarded.observe(uid));
        }
        assertEquals(0, reads.get());
    }

    @Test public void otherUidCannotBecomePositiveEvidence() {
        audio.setActiveRecordingConfigurations(List.of(recording(UID + 1)), false);
        assertUnknown(source.observe(UID));
    }

    @Test public void sameAppIdFromAnotherUserCannotBecomePositiveEvidence() {
        audio.setActiveRecordingConfigurations(List.of(recording(110123)), false);
        assertUnknown(source.observe(UID));
    }

    @Test public void otherUidBeforeMatchingUidDoesNotHidePositiveEvidence() {
        audio.setActiveRecordingConfigurations(List.of(recording(UID + 1), recording(UID)), false);
        assertEquals(UID, source.observe(UID).uid());
    }

    @Test public void anonymousConfigurationIsUnknown() {
        audio.setActiveRecordingConfigurations(List.of(recording(-1)), false);
        assertUnknown(source.observe(UID));
    }

    @Test public void anonymousEntryFailureDoesNotHideLaterMatchingUid() {
        audio.setActiveRecordingConfigurations(List.of(recording(-1), recording(UID)), false);
        assertEquals(UID, source.observe(UID).uid());
    }

    @Test public void emptyListIsUnknownInsteadOfClear() {
        audio.setActiveRecordingConfigurations(List.of(), false);
        assertUnknown(source.observe(UID));
    }

    @Test public void disappearedRecordingDoesNotReusePreviousPositiveObservation() {
        audio.setActiveRecordingConfigurations(List.of(recording(UID)), false);
        assertEquals(UID, source.observe(UID).uid());
        audio.setActiveRecordingConfigurations(List.of(), false);
        ShadowSystemClock.advanceBy(Duration.ofMillis(1));
        assertUnknown(source.observe(UID));
    }

    @Test public void nullContextIsUnknown() {
        assertUnknown(SourceRecordingState.from(() -> null).observe(UID));
    }

    @Test public void absentContextSupplierIsUnknown() {
        assertUnknown(SourceRecordingState.from(null).observe(UID));
    }

    @Test public void contextResolutionExceptionIsUnknown() {
        assertUnknown(SourceRecordingState.from(() -> {
            throw new IllegalStateException("context unavailable");
        }).observe(UID));
    }

    @Test public void unavailableContextCapabilityIsUnknown() {
        assertUnknown(SourceRecordingState.from(() -> {
            throw new NoClassDefFoundError("context capability unavailable");
        }).observe(UID));
    }

    @Test public void missingAudioServiceIsUnknown() {
        assertUnknown(SourceRecordingState.from(() -> new ContextWrapper(context) {
            @Override public Object getSystemService(String name) { return null; }
        }).observe(UID));
    }

    @Test public void malformedAudioServiceIsUnknown() {
        assertUnknown(SourceRecordingState.from(() -> new ContextWrapper(context) {
            @Override public Object getSystemService(String name) { return new Object(); }
        }).observe(UID));
    }

    @Test public void audioServiceResolutionDeniedIsUnknown() {
        assertUnknown(SourceRecordingState.from(() -> new ContextWrapper(context) {
            @Override public Object getSystemService(String name) {
                throw new SecurityException("service unavailable");
            }
        }).observe(UID));
    }

    @Test @Config(shadows = DeniedPollingShadow.class)
    public void publicPollingDenialIsUnknown() {
        assertUnknown(source.observe(UID));
    }

    @Test @Config(shadows = MissingPollingShadow.class)
    public void unavailablePublicPollingCapabilityIsUnknown() {
        assertUnknown(source.observe(UID));
    }

    @Test @Config(shadows = MissingUidShadow.class)
    public void unavailableHiddenUidCapabilityIsUnknown() {
        audio.setActiveRecordingConfigurations(List.of(recording(UID)), false);
        assertUnknown(source.observe(UID));
    }

    @Test public void nullListIsUnknown() {
        publishMalformedList(null);
        assertUnknown(source.observe(UID));
    }

    @Test public void nullAndMalformedEntriesAreUnknown() {
        publishMalformedList(Arrays.asList(null, new Object(), recording(UID + 1)));
        assertUnknown(source.observe(UID));
    }

    @Test public void malformedEntriesDoNotHideLaterMatchingConfiguration() {
        publishMalformedList(Arrays.asList(null, new Object(), recording(UID)));
        assertEquals(UID, source.observe(UID).uid());
    }

    @Test public void brokenEntryReadDoesNotHideLaterMatchingConfiguration() {
        publishMalformedList(new AbstractList<AudioRecordingConfiguration>() {
            @Override public int size() { return 2; }
            @Override public AudioRecordingConfiguration get(int index) {
                if (index == 0) throw new IllegalStateException("bad entry");
                return recording(UID);
            }
        });
        assertEquals(UID, source.observe(UID).uid());
    }

    @Test public void brokenListSizeIsUnknown() {
        publishMalformedList(new AbstractList<AudioRecordingConfiguration>() {
            @Override public int size() { throw new IllegalStateException("bad list"); }
            @Override public AudioRecordingConfiguration get(int index) {
                throw new AssertionError("must not read an unbounded list");
            }
        });
        assertUnknown(source.observe(UID));
    }

    @Test public void matchingLastEntryWithinBoundIsObserved() {
        AudioRecordingConfiguration other = recording(UID + 1), match = recording(UID);
        publishMalformedList(new AbstractList<AudioRecordingConfiguration>() {
            @Override public int size() { return 129; }
            @Override public AudioRecordingConfiguration get(int index) {
                if (index >= 128) throw new AssertionError("scan exceeded its bound");
                return index == 127 ? match : other;
            }
        });
        assertEquals(UID, source.observe(UID).uid());
    }

    @Test public void matchingEntryBeyondBoundRemainsUnknown() {
        AudioRecordingConfiguration other = recording(UID + 1), match = recording(UID);
        AtomicInteger reads = new AtomicInteger();
        publishMalformedList(new AbstractList<AudioRecordingConfiguration>() {
            @Override public int size() { return 129; }
            @Override public AudioRecordingConfiguration get(int index) {
                reads.incrementAndGet();
                if (index > 128) throw new AssertionError("scan exceeded its bound");
                return index == 128 ? match : other;
            }
        });
        assertUnknown(source.observe(UID));
        assertEquals(128, reads.get());
    }

    @Test public void hugeListStillHasBoundedReadsAndIsUnknown() {
        AudioRecordingConfiguration other = recording(UID + 1);
        AtomicInteger reads = new AtomicInteger();
        publishMalformedList(new AbstractList<AudioRecordingConfiguration>() {
            @Override public int size() { return Integer.MAX_VALUE; }
            @Override public AudioRecordingConfiguration get(int index) {
                reads.incrementAndGet();
                if (index >= 128) throw new AssertionError("scan exceeded its bound");
                return other;
            }
        });
        assertUnknown(source.observe(UID));
        assertEquals(128, reads.get());
    }

    private void publishMalformedList(List<?> configurations) {
        // The 4.14.1 shadow returns this field directly; production still uses public polling.
        ReflectionHelpers.setField(audio, "activeRecordingConfigurations", configurations);
    }

    private static AudioRecordingConfiguration recording(int uid) {
        // ShadowAudioManager's convenience factory fixes uid=0, so use the real platform object.
        AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
        return ReflectionHelpers.callConstructor(AudioRecordingConfiguration.class,
                ClassParameter.from(int.class, uid), ClassParameter.from(int.class, 1),
                ClassParameter.from(int.class, 1), ClassParameter.from(AudioFormat.class, format),
                ClassParameter.from(AudioFormat.class, format), ClassParameter.from(int.class, -1),
                ClassParameter.from(String.class, "com.example.recording.fixture"));
    }

    private static void assertUnknown(SourceRecordingState.Observation observation) {
        assertEquals(new SourceRecordingState.Observation(-1, -1), observation);
    }

    @Implements(AudioManager.class)
    public static final class DeniedPollingShadow extends ShadowAudioManager {
        @Implementation protected List<AudioRecordingConfiguration> getActiveRecordingConfigurations() {
            throw new SecurityException("polling denied");
        }
    }

    @Implements(AudioManager.class)
    public static final class MissingPollingShadow extends ShadowAudioManager {
        @Implementation protected List<AudioRecordingConfiguration> getActiveRecordingConfigurations() {
            throw new NoSuchMethodError("polling unavailable");
        }
    }

    @Implements(AudioRecordingConfiguration.class)
    public static final class MissingUidShadow {
        @Implementation protected int getClientUid() {
            throw new NoSuchMethodError("hidden UID unavailable");
        }
    }
}
