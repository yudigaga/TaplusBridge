package io.github.yudigaga.taplusbridge.probe;

import android.app.assist.AssistStructure;
import android.graphics.Matrix;
import android.text.Spanned;
import android.text.InputType;
import android.text.style.URLSpan;
import android.util.Pair;
import android.view.View;
import android.view.ViewStructure;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Reads only the structure supplied for the original main window and physical touch point. */
public final class AssistContentExtractor {
    static final int MAX_NODES = 512, MAX_DEPTH = 32, MAX_WINDOWS = 8;
    private AssistContentExtractor() { }

    public static SystemAssistContent.Result extract(AssistStructure structure, ScopeCaptureWindow.Snapshot target) {
        if (structure == null || target == null || target.display != 0) return SystemAssistContent.Result.EMPTY;
        List<Window> windows = new ArrayList<>();
        int count = structure.getWindowNodeCount();
        if (count < 1 || count > MAX_WINDOWS) return SystemAssistContent.Result.EMPTY;
        for (int i = 0; i < count; i++) {
            AssistStructure.WindowNode window = structure.getWindowNodeAt(i);
            AssistStructure.ViewNode root = window.getRootViewNode();
            windows.add(new Window(window.getLeft(), window.getTop(), window.getWidth(), window.getHeight(),
                    window.getDisplayId(), root == null ? null : new StructureNode(root)));
        }
        return extract(windows, target.bounds.left, target.bounds.top, target.bounds.width(), target.bounds.height(),
                target.x, target.y);
    }

    interface Node {
        int left(); int top(); int width(); int height(); int scrollX(); int scrollY();
        boolean visible(); boolean blocked(); int inputType(); String className(); CharSequence text();
        List<String> hrefs(); int children(); Node child(int index);
        default boolean editable() { return false; }
    }
    record Window(int left, int top, int width, int height, int display, Node root) {
        Window(int left, int top, int width, int height, Node root) { this(left, top, width, height, 0, root); }
    }

    static SystemAssistContent.Result extract(List<Window> windows, int left, int top, int width, int height,
                                               float rawX, float rawY) {
        if (windows == null || windows.size() > MAX_WINDOWS || !Float.isFinite(rawX) || !Float.isFinite(rawY)
                || width <= 0 || height <= 0)
            return SystemAssistContent.Result.EMPTY;
        Bounds expected = new Bounds(left, top, (long) left + width, (long) top + height);
        if (!expected.contains(rawX, rawY)) return SystemAssistContent.Result.EMPTY;
        Window selected = null;
        Bounds selectedBounds = null;
        for (Window window : windows) {
            if (window == null || window.display != 0 || window.root == null || window.width <= 0
                    || window.height <= 0 || window.width > 16384 || window.height > 16384) continue;
            // Framework window origins exclude the root's own left/top; keep their exact sum.
            Bounds actual = bounds(window.root, window.left, window.top);
            if (actual.area() <= 0 || !expected.contains(actual) || !actual.contains(rawX, rawY)) continue;
            if (selected != null) return SystemAssistContent.Result.EMPTY;
            selected = window; selectedBounds = actual;
        }
        if (selected == null) return SystemAssistContent.Result.EMPTY;
        Search search = new Search(rawX, rawY, selectedBounds);
        search.visit(selected.root, selected.left, selected.top, selectedBounds, 0);
        if (search.privateHit) return SystemAssistContent.Result.EMPTY;
        if (search.best != null) return search.best.content;
        if (search.container != null) {
            Accumulator contents = new Accumulator();
            Candidate container = search.container;
            search.collect(container.node, container.originX, container.originY, container.bounds, container.depth, contents);
            return contents.result();
        }
        return SystemAssistContent.Result.EMPTY;
    }

    private record Bounds(long left, long top, long right, long bottom) {
        boolean contains(float x, float y) { return x >= left && x < right && y >= top && y < bottom; }
        boolean contains(Bounds other) {
            return other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom;
        }
        long area() { return Math.max(0, right - left) * Math.max(0, bottom - top); }
        Bounds clip(Bounds other) {
            return new Bounds(Math.max(left, other.left), Math.max(top, other.top),
                    Math.min(right, other.right), Math.min(bottom, other.bottom));
        }
    }
    private record Candidate(Node node, Bounds bounds, long originX, long originY,
                             int depth, SystemAssistContent.Result content) { }

    private static final class Search {
        final float x, y;
        final Bounds window;
        int remaining = MAX_NODES;
        boolean privateHit;
        Candidate best, container;
        Search(float x, float y, Bounds window) { this.x = x; this.y = y; this.window = window; }

        boolean visit(Node node, long originX, long originY, Bounds clip, int depth) {
            if (node == null || depth > MAX_DEPTH || remaining-- <= 0) return false;
            Bounds bounds = bounds(node, originX, originY).clip(clip);
            if (bounds.area() <= 0 || !bounds.contains(x, y) || !node.visible()) return false;
            if (privateNode(node)) { privateHit = true; return true; }
            int children = Math.max(0, Math.min(MAX_NODES, node.children()));
            SystemAssistContent.Result content = content(node);
            Candidate candidate = new Candidate(node, bounds, originX, originY, depth, content);
            boolean boundedContent = children == 0 || depth > 0 && bounds.area() <= window.area() / 2;
            if (boundedContent && (!content.text().isEmpty() || !content.links().isEmpty()) && preferred(candidate, best)) best = candidate;
            // A blank area of the full window must never aggregate unrelated screen text.
            if (depth > 0 && children > 0 && bounds.area() <= window.area() / 2 && preferred(candidate, container))
                container = candidate;
            long nextX = originX + node.left() - node.scrollX(), nextY = originY + node.top() - node.scrollY();
            for (int i = children - 1; i >= 0 && remaining > 0; i--)
                if (visit(node.child(i), nextX, nextY, bounds, depth + 1)) break;
            return true;
        }

        void collect(Node node, long originX, long originY, Bounds clip, int depth, Accumulator out) {
            if (node == null || depth > MAX_DEPTH || remaining-- <= 0 || out.full() || !node.visible() || privateNode(node)) return;
            Bounds bounds = bounds(node, originX, originY).clip(clip);
            if (bounds.area() <= 0) return;
            SystemAssistContent.Result value = content(node);
            if (!value.text().isEmpty() || !value.links().isEmpty()) {
                out.add(value, bounds);
                if (!value.text().isEmpty()) return;
            }
            long nextX = originX + node.left() - node.scrollX(), nextY = originY + node.top() - node.scrollY();
            int count = Math.min(MAX_NODES, Math.max(0, node.children()));
            for (int i = count - 1; i >= 0 && remaining > 0; i--) collect(node.child(i), nextX, nextY, bounds, depth + 1, out);
        }
    }

    private static boolean preferred(Candidate value, Candidate previous) {
        return previous == null || value.depth > previous.depth
                || value.depth == previous.depth && value.bounds.area() < previous.bounds.area();
    }
    private static Bounds bounds(Node node, long originX, long originY) {
        int width = node.width(), height = node.height();
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384) return new Bounds(0, 0, 0, 0);
        long left = originX + node.left(), top = originY + node.top();
        return new Bounds(left, top, left + width, top + height);
    }
    private static boolean privateNode(Node node) {
        String name = node.className();
        int type = node.inputType(), variation = type & InputType.TYPE_MASK_VARIATION;
        boolean password = (type & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
                && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                || (type & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER
                    && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        return node.blocked() || node.editable() || password || name != null
                && (name.endsWith("EditText") || name.endsWith("AutoCompleteTextView"));
    }
    private static SystemAssistContent.Result content(Node node) {
        CharSequence raw = node.text();
        String text = raw == null ? "" : raw.toString().trim();
        List<String> links = new ArrayList<>();
        if (raw instanceof Spanned spanned) {
            for (URLSpan span : spanned.getSpans(0, spanned.length(), URLSpan.class)) {
                if (links.size() >= SystemAssistContent.MAX_LINKS) break;
                links.add(span.getURL());
            }
        }
        List<String> hrefs = node.hrefs();
        if (hrefs != null) for (String href : hrefs) {
            if (links.size() >= SystemAssistContent.MAX_LINKS) break;
            links.add(href);
        }
        return new SystemAssistContent.Result(text, links);
    }
    private static final class Accumulator {
        record Piece(String text, Bounds bounds) { }
        final List<Piece> text = new ArrayList<>();
        final LinkedHashSet<String> links = new LinkedHashSet<>();
        final LinkedHashSet<String> pieces = new LinkedHashSet<>();
        int length;
        void add(SystemAssistContent.Result value, Bounds bounds) {
            // Reverse child traversal sees the last drawn text first; covered siblings cannot leak.
            for (Piece piece : text) if (piece.bounds.clip(bounds).area() > 0) return;
            if (!value.text().isEmpty() && pieces.add(value.text())) {
                int available = SystemAssistContent.MAX_TEXT - length - (text.isEmpty() ? 0 : 1);
                if (available > 0) {
                    String part = value.text().substring(0, Math.min(available, value.text().length()));
                    text.add(new Piece(part, bounds)); length += part.length() + (text.size() > 1 ? 1 : 0);
                }
            }
            for (String link : value.links()) if (links.size() < SystemAssistContent.MAX_LINKS) links.add(link);
        }
        boolean full() { return length >= SystemAssistContent.MAX_TEXT; }
        SystemAssistContent.Result result() {
            text.sort(java.util.Comparator.comparingLong((Piece piece) -> piece.bounds.top).thenComparingLong(piece -> piece.bounds.left));
            return new SystemAssistContent.Result(String.join("\n", text.stream().map(Piece::text)
                    .collect(java.util.stream.Collectors.toList())), new ArrayList<>(links));
        }
    }

    private record StructureNode(AssistStructure.ViewNode view) implements Node {
        public int left() { return view.getLeft(); }
        public int top() { return view.getTop(); }
        public int width() { return view.getWidth(); }
        public int height() { return view.getHeight(); }
        public int scrollX() { return view.getScrollX(); }
        public int scrollY() { return view.getScrollY(); }
        public boolean visible() { return view.getVisibility() == View.VISIBLE && view.getAlpha() > 0; }
        public boolean blocked() {
            Matrix matrix = view.getTransformation();
            return view.isAssistBlocked() || matrix != null && !matrix.isIdentity();
        }
        public int inputType() { return view.getInputType(); }
        public String className() { return view.getClassName(); }
        public CharSequence text() { return view.getText(); }
        public boolean editable() {
            ViewStructure.HtmlInfo html = view.getHtmlInfo();
            if (html == null) return false;
            String tag = html.getTag();
            if ("input".equalsIgnoreCase(tag) || "textarea".equalsIgnoreCase(tag) || "select".equalsIgnoreCase(tag)) return true;
            List<Pair<String, String>> attributes = html.getAttributes();
            if (attributes != null) for (int i = 0; i < Math.min(64, attributes.size()); i++) {
                Pair<String, String> attribute = attributes.get(i);
                if (attribute != null && "contenteditable".equalsIgnoreCase(attribute.first)
                        && !"false".equalsIgnoreCase(attribute.second)) return true;
            }
            return false;
        }
        public List<String> hrefs() {
            ViewStructure.HtmlInfo html = view.getHtmlInfo();
            if (html == null || html.getAttributes() == null) return List.of();
            List<String> hrefs = new ArrayList<>();
            List<Pair<String, String>> attributes = html.getAttributes();
            for (int i = 0; i < Math.min(64, attributes.size()); i++) {
                Pair<String, String> attribute = attributes.get(i);
                if (attribute != null && "href".equalsIgnoreCase(attribute.first)) hrefs.add(attribute.second);
            }
            return hrefs;
        }
        public int children() { return view.getChildCount(); }
        public Node child(int index) {
            AssistStructure.ViewNode child = view.getChildAt(index);
            return child == null ? null : new StructureNode(child);
        }
    }
}
