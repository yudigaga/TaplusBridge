package io.github.yudigaga.taplusbridge.probe;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.URLSpan;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public final class AssistContentExtractorAndroidTest {
    private static final int WINDOW_X = 30, WINDOW_Y = 70;

    @Test public void pointSelectsRealNestedTextUsingWindowAndScrolledParentCoordinates() {
        Node root = node(0, 0, 400, 800, null);
        Node scroll = node(0, 100, 400, 500, null); scroll.scrollY = 120;
        scroll.children.add(node(10, 100, 250, 60, "touched message"));
        scroll.children.add(node(10, 200, 250, 60, "unrelated next message"));
        root.children.add(scroll);
        assertEquals("touched message", read(root, WINDOW_X + 50, WINDOW_Y + 110).text());
    }

    @Test public void insetAssistWindowPreservesScreenCoordinatesInsideTheValidatedMainFrame() {
        Node root = node(0, 0, 400, 800, null);
        Node scroll = node(0, 100, 400, 500, null); scroll.scrollY = 120;
        scroll.children.add(node(10, 100, 250, 60, "touched inset message"));
        scroll.children.add(node(10, 200, 250, 60, "different inset message"));
        root.children.add(scroll);
        var windows = List.of(new AssistContentExtractor.Window(30, 70, 400, 800, root));
        assertEquals("touched inset message",
                AssistContentExtractor.extract(windows, 0, 0, 500, 1000, 80, 180).text());
    }

    @Test public void negativeRootPositionIsAddedToItsWindowOriginWithoutClampingOrScaling() {
        Node root = node(-10, -20, 400, 800, null);
        root.children.add(node(10, 100, 250, 60, "actual screen position"));
        var windows = List.of(new AssistContentExtractor.Window(40, 90, 400, 800, root));
        assertEquals("actual screen position",
                AssistContentExtractor.extract(windows, 0, 0, 500, 1000, 80, 180).text());
    }

    @Test public void aPointOutsideTheActualRootCannotBorrowItsInsetWindowContent() {
        Node root = node(0, 60, 400, 740, "unrelated root summary");
        root.children.add(node(0, 0, 300, 100, "content below the physical point"));
        var windows = List.of(new AssistContentExtractor.Window(30, 70, 400, 800, root));
        assertEquals(SystemAssistContent.Result.EMPTY,
                AssistContentExtractor.extract(windows, 0, 0, 500, 1000, 80, 100));
    }

    @Test public void twoContainingAssistWindowsAreAmbiguousEvenWhenOneExactlyMatchesTheFrame() {
        Node full = node(0, 0, 500, 1000, null);
        full.children.add(node(0, 0, 300, 300, "full window message"));
        Node inset = node(0, 0, 400, 800, null);
        inset.children.add(node(0, 0, 300, 300, "inset window message"));
        var windows = List.of(new AssistContentExtractor.Window(0, 0, 500, 1000, full),
                new AssistContentExtractor.Window(30, 70, 400, 800, inset));
        assertEquals(SystemAssistContent.Result.EMPTY,
                AssistContentExtractor.extract(windows, 0, 0, 500, 1000, 80, 100));
    }

    @Test public void actualRootExtendingOutsideTheValidatedFrameIsRejected() {
        Node root = node(-40, 0, 400, 800, "root outside the allowed main window");
        var windows = List.of(new AssistContentExtractor.Window(30, 70, 400, 800, root));
        assertEquals(SystemAssistContent.Result.EMPTY,
                AssistContentExtractor.extract(windows, 0, 0, 500, 1000, 80, 180));
    }

    @Test public void aContainingWindowOnAnotherDisplayCannotProvideTheSelectedContent() {
        Node root = node(0, 0, 400, 800, "another display's content");
        var windows = List.of(new AssistContentExtractor.Window(WINDOW_X, WINDOW_Y, 400, 800, 2, root));
        assertEquals(SystemAssistContent.Result.EMPTY,
                AssistContentExtractor.extract(windows, WINDOW_X, WINDOW_Y, 400, 800, WINDOW_X + 50, WINDOW_Y + 120));
    }

    @Test public void anUninspectedWindowCannotBeIgnoredWhenCheckingForAmbiguity() {
        Node root = node(0, 0, 400, 800, "selected content");
        List<AssistContentExtractor.Window> windows = new ArrayList<>();
        for (int i = 0; i <= AssistContentExtractor.MAX_WINDOWS; i++)
            windows.add(new AssistContentExtractor.Window(WINDOW_X, WINDOW_Y, 400, 800, root));
        assertEquals(SystemAssistContent.Result.EMPTY,
                AssistContentExtractor.extract(windows, WINDOW_X, WINDOW_Y, 400, 800, WINDOW_X + 50, WINDOW_Y + 120));
    }

    @Test public void mostSpecificContainingTextWinsOverAncestorAndOtherRows() {
        Node root = node(0, 0, 400, 800, "whole screen summary");
        Node row = node(0, 100, 400, 200, "whole card");
        row.children.add(node(10, 10, 200, 60, "specific selected text"));
        root.children.add(row);
        root.children.add(node(0, 350, 400, 200, "other card"));
        assertEquals("specific selected text", read(root, WINDOW_X + 50, WINDOW_Y + 130).text());
    }

    @Test public void cardPaddingAggregatesOnlyItsBoundedSubtree() {
        Node root = node(0, 0, 400, 800, null);
        Node row = node(0, 100, 400, 150, null);
        row.children.add(node(30, 20, 300, 30, "title"));
        row.children.add(node(30, 60, 300, 30, "body"));
        root.children.add(row);
        root.children.add(node(0, 400, 400, 80, "unrelated row"));
        assertEquals("title\nbody", read(root, WINDOW_X + 5, WINDOW_Y + 110).text());
        assertEquals("", read(root, WINDOW_X + 5, WINDOW_Y + 700).text());
    }

    @Test public void clippedScrolledCardPaddingCannotMoveOffscreenTextOrHrefIntoTheViewport() {
        Node root = node(0, 0, 400, 800, null);
        Node viewport = node(0, 100, 400, 200, null); viewport.scrollY = 80;
        Node card = node(0, 40, 400, 180, null);
        Node offscreen = node(30, 0, 300, 20, "offscreen hidden text");
        offscreen.hrefs = List.of("https://hidden.example/offscreen");
        card.children.add(offscreen);
        card.children.add(node(30, 70, 300, 20, "visible child"));
        viewport.children.add(card); root.children.add(viewport);
        var result = read(root, WINDOW_X + 5, WINDOW_Y + 110);
        assertEquals("visible child", result.text());
        assertEquals(List.of(), result.links());
    }

    @Test public void aFullWindowAggregateCannotBecomeTheTextOfAnUnrelatedBlankPoint() {
        Node root = node(0, 0, 400, 800, "unrelated complete screen summary");
        root.children.add(node(0, 100, 300, 80, "message above the finger"));
        assertEquals("", read(root, WINDOW_X + 50, WINDOW_Y + 700).text());
    }

    @Test public void invisibleSecureEditableAndPasswordSubtreesContributeNothing() {
        for (int mode = 0; mode < 5; mode++) {
            Node root = node(0, 0, 400, 800, null);
            Node privateNode = node(0, 100, 400, 100, "private input");
            if (mode == 0) privateNode.visible = false;
            if (mode == 1) privateNode.blocked = true;
            if (mode == 2) privateNode.inputType = 0x81;
            if (mode == 3) privateNode.className = "android.widget.EditText";
            if (mode == 4) privateNode.editable = true;
            privateNode.children.add(node(0, 0, 400, 100, "private child"));
            root.children.add(privateNode);
            assertEquals("", read(root, WINDOW_X + 50, WINDOW_Y + 120).text());
        }
    }

    @Test public void ordinaryTextInputTypeDoesNotHideANonEditableTextView() {
        Node root = node(0, 0, 400, 800, null);
        Node label = node(0, 100, 300, 80, "ordinary visible text"); label.inputType = 1;
        root.children.add(label);
        assertEquals("ordinary visible text", read(root, WINDOW_X + 50, WINDOW_Y + 120).text());
    }

    @Test public void lastDrawnOverlappingSiblingWinsWithoutMergingCoveredText() {
        Node root = node(0, 0, 400, 800, null);
        root.children.add(node(0, 100, 300, 80, "covered text"));
        root.children.add(node(0, 100, 300, 80, "top visible text"));
        assertEquals("top visible text", read(root, WINDOW_X + 50, WINDOW_Y + 120).text());
    }

    @Test public void aDeeperCoveredSiblingCannotOutrankTheTopVisibleText() {
        Node root = node(0, 0, 400, 800, null);
        Node underneath = node(0, 100, 300, 80, null);
        underneath.children.add(node(0, 0, 300, 80, "covered deeper text"));
        root.children.add(underneath);
        root.children.add(node(0, 100, 300, 80, "top visible text"));
        assertEquals("top visible text", read(root, WINDOW_X + 50, WINDOW_Y + 120).text());
    }

    @Test public void onlyActualAbsoluteHrefAndUrlSpanProduceLinks() {
        Node root = node(0, 0, 400, 800, null);
        SpannableString message = new SpannableString("open repository");
        message.setSpan(new URLSpan("https://example.com/repository"), 5, 15, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Node selected = node(0, 100, 300, 80, message);
        selected.hrefs = List.of("https://example.com/repository", "/relative", "javascript:alert(1)", "https://other.example/info");
        root.children.add(selected);
        var result = read(root, WINDOW_X + 50, WINDOW_Y + 120);
        assertEquals(List.of("https://example.com/repository", "https://other.example/info"), result.links());
        assertEquals("open repository\nhttps://example.com/repository\nhttps://other.example/info", result.deliveryText());
        assertThrows(UnsupportedOperationException.class, () -> result.links().add("https://fake.example"));
    }

    @Test public void unmatchedWindowAndOutsidePointDoNotReturnWindowTitleOrWebDomain() {
        Node root = node(0, 0, 400, 800, "only other window");
        assertEquals(SystemAssistContent.Result.EMPTY,
                AssistContentExtractor.extract(List.of(new AssistContentExtractor.Window(5, 5, 400, 800, root)),
                        WINDOW_X, WINDOW_Y, 400, 800, WINDOW_X + 30, WINDOW_Y + 100));
        assertEquals("", read(root, WINDOW_X - 1, WINDOW_Y + 100).text());
    }

    @Test public void traversalBudgetBoundsHugeSiblingsAndResultBoundsTextAndLinks() {
        Node root = node(0, 0, 400, 800, null);
        for (int i = 0; i < 700; i++) root.children.add(node(0, 900, 400, 1, "outside"));
        root.children.add(0, node(0, 100, 400, 80, "beyond budget"));
        assertEquals("", read(root, WINDOW_X + 50, WINDOW_Y + 120).text());
        List<String> links = new ArrayList<>();
        for (int i = 0; i < 20; i++) links.add("https://example.com/" + i);
        var result = new SystemAssistContent.Result("x".repeat(140_000), links);
        assertEquals(131072, result.text().length());
        assertEquals(8, result.links().size());
        assertTrue(result.deliveryText().length() <= 131072);
    }

    @Test public void deliveryDoesNotDuplicateAnAlreadyVisibleDestination() {
        String url = "https://example.com/item";
        assertEquals(url, new SystemAssistContent.Result(url, List.of(url, url)).deliveryText());
    }

    @Test public void aVisibleLongerUrlDoesNotHideTheActualShorterHrefDestination() {
        var result = new SystemAssistContent.Result("https://example.com/a2", List.of("https://example.com/a"));
        assertEquals(List.of("https://example.com/a", "https://example.com/a2"), result.links());
        assertEquals("https://example.com/a2\nhttps://example.com/a", result.deliveryText());
    }

    @Test public void aRealUrlInVisibleTextIsAvailableWithoutClickingTheSource() {
        var result = new SystemAssistContent.Result("read https://example.com/item", List.of());
        assertEquals(List.of("https://example.com/item"), result.links());
    }

    @Test public void aFullLengthTextReservesSpaceForItsActualHiddenHref() {
        var result = new SystemAssistContent.Result("x".repeat(131072), List.of("https://example.com/destination"));
        assertTrue(result.deliveryText().endsWith("\nhttps://example.com/destination"));
        assertTrue(result.deliveryText().length() <= 131072);
    }

    private static SystemAssistContent.Result read(Node root, float rawX, float rawY) {
        return AssistContentExtractor.extract(List.of(new AssistContentExtractor.Window(WINDOW_X, WINDOW_Y, 400, 800, root)),
                WINDOW_X, WINDOW_Y, 400, 800, rawX, rawY);
    }

    private static Node node(int left, int top, int width, int height, CharSequence text) {
        return new Node(left, top, width, height, text);
    }

    static final class Node implements AssistContentExtractor.Node {
        final int left, top, width, height;
        final CharSequence text;
        final List<Node> children = new ArrayList<>();
        int scrollY, inputType;
        boolean visible = true, blocked, editable;
        String className = "android.widget.TextView";
        List<String> hrefs = List.of();
        Node(int left, int top, int width, int height, CharSequence text) {
            this.left = left; this.top = top; this.width = width; this.height = height; this.text = text;
        }
        public int left() { return left; }
        public int top() { return top; }
        public int width() { return width; }
        public int height() { return height; }
        public int scrollX() { return 0; }
        public int scrollY() { return scrollY; }
        public boolean visible() { return visible; }
        public boolean blocked() { return blocked; }
        public boolean editable() { return editable; }
        public int inputType() { return inputType; }
        public String className() { return className; }
        public CharSequence text() { return text; }
        public List<String> hrefs() { return hrefs; }
        public int children() { return children.size(); }
        public AssistContentExtractor.Node child(int index) { return children.get(index); }
    }
}
