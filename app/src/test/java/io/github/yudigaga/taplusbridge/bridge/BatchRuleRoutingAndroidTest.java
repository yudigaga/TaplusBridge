package io.github.yudigaga.taplusbridge.bridge;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class BatchRuleRoutingAndroidTest extends BridgeRobolectricFixture {
    private static final String TARGET = "com.ylmf.androidclient";
    private static final String MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=MixedCase%2BName";
    private static final String ED2K = "ed2k://|file|test.iso|123456|0123456789abcdef0123456789abcdef|/";
    @Before public void setupRules() {
        install(TARGET, true, true);
        RuleRepository repository = new RuleRepository(context);
        repository.saveManualRule(-1, "*", "magnet:", TARGET, "none", "batch");
        repository.saveManualRule(-1, "*", "ed2k:", TARGET, "none", "batch");
    }
    private Bundle request(List<String> links) {
        Bundle request = new Bundle(); request.putString("source", "com.example.source");
        request.putStringArrayList("links", new ArrayList<>(links)); return request;
    }
    @Test public void oneBatchReusesTheDirectReceiverWithAllOrderedLinesAndNoMainPageEvent() throws Exception {
        config.setCloudLinkHandoffEnabled(false);
        Bundle request = request(List.of(MAGNET, ED2K, MAGNET));
        LaunchPlanner.Plan plan = LaunchPlanner.plan(context, request);
        assertNotNull(plan); assertEquals(TARGET, plan.targetPackage());
        assertEquals("批量规则", plan.kind()); assertEquals(Intent.ACTION_VIEW, plan.target().getAction());
        assertEquals(TARGET, plan.target().getPackage());
        String destination = plan.target().getDataString();
        assertEquals("magnet", plan.target().getData().getScheme());
        assertEquals(MAGNET + "\n" + ED2K, destination);
        android.content.IntentFilter receiver = new android.content.IntentFilter(Intent.ACTION_VIEW);
        receiver.addCategory(Intent.CATEGORY_DEFAULT); receiver.addCategory(Intent.CATEGORY_BROWSABLE);
        receiver.addDataScheme("magnet"); receiver.addDataScheme("ed2k");
        assertTrue(receiver.matchData(null, "magnet", plan.target().getData()) >= 0);
        android.os.Parcel parcel = android.os.Parcel.obtain();
        try {
            plan.target().writeToParcel(parcel, 0); parcel.setDataPosition(0);
            Intent copied = Intent.CREATOR.createFromParcel(parcel);
            assertEquals(destination, copied.getDataString());
            assertTrue(plan.target().filterEquals(copied));
        } finally { parcel.recycle(); }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("((?i)((https|http|ftp|ed2k|thunder)://)|(magnet):\\?).+").matcher(destination);
        List<String> received = new ArrayList<>(); while (matcher.find()) received.add(matcher.group());
        assertEquals(List.of(MAGNET, ED2K), received);
        assertTrue(plan.target().filterEquals(LaunchPlanner.plan(context, request).target()));
        assertNull(plan.target().getSelector()); assertNull(plan.target().getClipData());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, plan.target().getFlags());
    }
    @Test public void singleBatchItemUsesNormalRuleHandoffAndCanonicalizesOnlyTheScheme() {
        String upper = "MAGNET" + MAGNET.substring(6);
        LaunchPlanner.Plan plan = LaunchPlanner.plan(context, request(List.of(upper)));
        assertNotNull(plan); assertEquals(MAGNET, plan.target().getDataString());
    }
    @Test public void batchRejectsInvalidTypesUnsafeInputsEmptyListsAndCompetingSingleIntents() {
        Bundle malformed = new Bundle(); malformed.putString("links", MAGNET);
        assertEquals("invalid_request", LaunchPlanner.planWithReason(context, malformed).reason());
        for (List<String> invalid : List.of(List.<String>of(), List.of("https://example.com/file"), List.of("magnet:?dn=title-only"),
                java.util.Collections.nCopies(65, MAGNET)))
            assertNull(LaunchPlanner.plan(context, request(invalid)));
        Bundle competing = request(List.of(MAGNET, ED2K)); competing.putString("url", MAGNET);
        assertNull(LaunchPlanner.plan(context, competing));
        competing = request(List.of(MAGNET, ED2K)); competing.putParcelable("candidate", new Intent(Intent.ACTION_MAIN).setPackage(TARGET));
        assertNull(LaunchPlanner.plan(context, competing));
    }
    @Test public void mixedTargetsOrMissingRulesCannotBecomeOneTargetBatchOrFallThroughToNative() {
        install("com.example.other", true, true);
        RuleRepository repository = new RuleRepository(context);
        repository.replaceManualRules(List.of());
        repository.saveManualRule(-1, "*", "magnet:", TARGET, "none", "batch");
        assertEquals("no_match", LaunchPlanner.planWithReason(context, request(List.of(MAGNET, ED2K))).reason());
        repository.saveManualRule(-1, "*", "ed2k:", "com.example.other", "none", "batch");
        assertEquals("batch_mixed_targets", LaunchPlanner.planWithReason(context, request(List.of(MAGNET, ED2K))).reason());
    }
    @Test public void unsupportedTargetDeclinesBulkInsteadOfSendingTextOrOpeningRepeatedActivities() {
        install("com.example.other", true, true);
        RuleRepository repository = new RuleRepository(context); repository.replaceManualRules(List.of());
        repository.saveManualRule(-1, "*", "magnet:", "com.example.other", "none", "batch");
        String second = MAGNET.replace("0123456789abcdef0123456789abcdef01234567", "1123456789abcdef0123456789abcdef01234567");
        assertEquals("batch_unsupported", LaunchPlanner.planWithReason(context, request(List.of(MAGNET, second))).reason());
    }
    @Test public void batchUtf8DataCannotExceedTheLaunchBoundary() {
        String longName = "中".repeat(6000);
        String first = MAGNET + "&dn=" + longName + "One";
        String second = MAGNET + "&dn=" + longName + "Two";
        assertEquals("invalid_request", LaunchPlanner.planWithReason(context, request(List.of(first, second))).reason());
    }
    @Test public void aCallerCannotSmuggleRawMultilineDataThroughSingleOrNativeLinkPaths() {
        String raw = MAGNET + "\n" + ED2K;
        assertFalse(LaunchPlanner.safeUri(raw));
        assertNull(LaunchPlanner.plan(context, web(raw)));
        Bundle nativeRequest = new Bundle();
        nativeRequest.putParcelable("candidate", new Intent(Intent.ACTION_VIEW, Uri.parse(raw)).setPackage(TARGET));
        assertNull(LaunchPlanner.plan(context, nativeRequest));
        assertNull(LaunchPlanner.plan(context, request(List.of(raw))));
    }
    @Test public void eitherProtocolCanBeFirstAndMixedCaseHeadersAreCanonicalizedWithoutChangingData() {
        String upper = "ED2K" + ED2K.substring(4);
        LaunchPlanner.Plan plan = LaunchPlanner.plan(context, request(List.of(upper, MAGNET)));
        assertNotNull(plan); assertEquals("ed2k", plan.target().getData().getScheme());
        assertEquals(ED2K + "\n" + MAGNET, plan.target().getDataString());
    }
}
