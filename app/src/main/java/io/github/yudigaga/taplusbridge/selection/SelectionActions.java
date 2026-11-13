package io.github.yudigaga.taplusbridge.selection;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.InputType;
import android.text.Spanned;
import android.text.method.PasswordTransformationMethod;
import android.text.style.URLSpan;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import io.github.yudigaga.taplusbridge.hook.HostPolicy;
import io.github.yudigaga.taplusbridge.recognition.RouteLink;
import java.lang.ref.WeakReference;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.Objects;
import java.util.function.Supplier;

/** Keeps the source's ActionMode and changes only recognized text translation / web actions. */
public final class SelectionActions {
    private static final int TRANSLATION_ID = 0x0f0b0101, BROWSER_ID = 0x0f0b0102;
    private static final Map<MenuItem, WeakReference<Wrapped>> OWNED = new WeakHashMap<>();
    private SelectionActions() { }
    /** Called before framework MenuItemImpl.invoke, which invokes its listener before the ActionMode callback. */
    public static boolean dispatchMenuItem(MenuItem item) {
        Wrapped wrapped;
        synchronized (OWNED) {
            WeakReference<Wrapped> reference = OWNED.get(item); wrapped = reference == null ? null : reference.get();
            if (wrapped == null) OWNED.remove(item);
        }
        ActionMode mode = wrapped == null ? null : wrapped.mode.get();
        return mode != null && wrapped.dispatch(mode, item);
    }
    public interface TargetSource extends Supplier<SelectionTargets> {
        void watch(ActionMode mode);
        void forget(ActionMode mode);
        void withFresh(ActionMode mode, java.util.function.Consumer<SelectionTargets> action);
        boolean refreshing();
    }
    public static ActionMode.Callback wrap(View view, ActionMode.Callback callback, int type,
            String source, Supplier<SelectionTargets> targets) {
        if (callback == null || callback instanceof Wrapped || targets == null || type != ActionMode.TYPE_FLOATING
                || !(view instanceof TextView text) || !eligible(text, source)) return callback;
        return new Wrapped(text, callback, source, targets);
    }

    static boolean eligible(TextView text, String source) {
        try {
            if (!HostPolicy.validTarget(source) || !source.equals(text.getContext().getPackageName())
                    || text instanceof EditText || text.getText() instanceof Editable || text.getKeyListener() != null
                    || text.getTransformationMethod() instanceof PasswordTransformationMethod) return false;
            if (text.getRootView().getLayoutParams() instanceof WindowManager.LayoutParams window
                    && (window.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0) return false;
            int input = text.getInputType(), variation = input & InputType.TYPE_MASK_VARIATION;
            if ((input & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
                    && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                    || (input & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return false;
            Context context = text.getContext();
            Set<Context> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            while (context != null && seen.add(context)) {
                if (context instanceof Activity activity) return activity.getWindow() != null
                        && (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) == 0;
                context = context instanceof ContextWrapper wrapper ? wrapper.getBaseContext() : null;
            }
        } catch (RuntimeException unavailable) { }
        return false;
    }

    private static final class Wrapped extends ActionMode.Callback2 {
        private final TextView text;
        private final ActionMode.Callback original;
        private final String source;
        private final Supplier<SelectionTargets> targets;
        private final Map<MenuItem, Binding> bindings = new IdentityHashMap<>();
        private WeakReference<ActionMode> mode = new WeakReference<>(null);
        private SelectionTargets presented;
        private boolean destroyed;
        Wrapped(TextView text, ActionMode.Callback original, String source, Supplier<SelectionTargets> targets) {
            this.text = text; this.original = original; this.source = source; this.targets = targets;
        }
        @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            boolean created = original.onCreateActionMode(mode, menu);
            if (created) { this.mode = new WeakReference<>(mode); watch(mode); apply(menu); }
            return created;
        }
        @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            boolean restored = restore(menu);
            boolean prepared = original.onPrepareActionMode(mode, menu);
            watch(mode);
            return apply(menu) || restored || prepared;
        }
        @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            return dispatch(mode, item) || original.onActionItemClicked(mode, item);
        }
        private boolean dispatch(ActionMode mode, MenuItem item) {
            Binding binding = bindings.get(item);
            if (destroyed || binding == null) return false;
            String selection = selectedText(text);
            int start = text.getSelectionStart(), end = text.getSelectionEnd();
            String url = binding.translation ? null : binding.url == null ? uniqueLink(text) : binding.url;
            java.util.function.Consumer<SelectionTargets> action = current -> {
                if (destroyed || current == null || !eligible(text, source) || bindings.get(item) != binding
                        || start != text.getSelectionStart() || end != text.getSelectionEnd()
                        || !Objects.equals(selection, selectedText(text))) return;
                Intent intent = binding.translation ? current.translationIntent(selection)
                        : current.browserIntent(url);
                if (intent == null) { unavailable(binding.translation); return; }
                try { text.getContext().startActivity(intent); }
                catch (RuntimeException unavailable) { unavailable(binding.translation); }
            };
            if (targets instanceof TargetSource reader) reader.withFresh(mode, action);
            else action.accept(get());
            return true;
        }
        @Override public void onDestroyActionMode(ActionMode mode) {
            destroyed = true;
            try { if (targets instanceof TargetSource reader) reader.forget(mode); }
            finally { unregister(); bindings.clear(); presented = null; this.mode.clear(); original.onDestroyActionMode(mode); }
        }
        @Override public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
            if (original instanceof ActionMode.Callback2 callback) callback.onGetContentRect(mode, view, outRect);
            else super.onGetContentRect(mode, view, outRect);
        }
        private void watch(ActionMode mode) { if (!destroyed && targets instanceof TargetSource reader) reader.watch(mode); }
        private SelectionTargets get() {
            try { return targets.get(); } catch (RuntimeException unavailable) { return null; }
        }
        private boolean restore(Menu menu) {
            boolean changed = !bindings.isEmpty();
            unregister();
            for (var entry : bindings.entrySet()) {
                MenuItem item = entry.getKey(); Binding binding = entry.getValue();
                if (binding.added) menu.removeItem(item.getItemId());
                else { item.setTitle(binding.title); item.setIcon(binding.icon); }
            }
            bindings.clear(); return changed;
        }
        private boolean apply(Menu menu) {
            if (destroyed || !eligible(text, source)) return false;
            SelectionTargets current = get();
            // A pending refresh must not reopen native listeners for actions already owned by this mode.
            if (current == null && targets instanceof TargetSource reader)
                current = reader.refreshing() ? presented : get();
            if (current == null) { presented = null; return false; }
            presented = current;
            boolean translated = false, browsed = false;
            for (int i = 0; i < menu.size(); i++) {
                MenuItem item = menu.getItem(i);
                if (protectedItem(item)) continue;
                if (current.translationConfigured && translationItem(item)) {
                    bind(item, current, true, null, false); translated = true;
                } else if (current.browserConfigured && webUrl(item) != null) {
                    bind(item, current, false, webUrl(item), false); browsed = true;
                }
            }
            if (current.translationConfigured && !translated && menu.findItem(TRANSLATION_ID) == null
                    && selectedText(text) != null) bind(menu.add(0, TRANSLATION_ID, 100, ""), current, true, null, true);
            if (current.browserConfigured && !browsed && menu.findItem(BROWSER_ID) == null && uniqueLink(text) != null)
                bind(menu.add(0, BROWSER_ID, 101, ""), current, false, null, true);
            return !bindings.isEmpty();
        }
        private void bind(MenuItem item, SelectionTargets current, boolean translation, String url, boolean added) {
            bindings.put(item, new Binding(translation, url, added, item.getTitle(), item.getIcon()));
            synchronized (OWNED) { OWNED.put(item, new WeakReference<>(this)); }
            boolean available = translation ? current.hasTranslation() : current.hasBrowser();
            item.setTitle(available ? translation ? current.translationTitle : current.browserTitle
                    : translation ? "翻译应用不可用" : "浏览器不可用");
            if (available) item.setIcon(new BitmapDrawable(text.getResources(), translation ? current.translationIcon : current.browserIcon));
            if (added) item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        }
        private void unavailable(boolean translation) {
            Toast.makeText(text.getContext(), translation ? "所选翻译应用不可用，请在模块中重新选择" : "所选浏览器不可用，请在模块中重新选择", Toast.LENGTH_SHORT).show();
        }
        private void unregister() {
            synchronized (OWNED) {
                for (MenuItem item : bindings.keySet()) {
                    WeakReference<Wrapped> reference = OWNED.get(item);
                    if (reference != null && reference.get() == this) OWNED.remove(item);
                }
            }
        }
    }
    private record Binding(boolean translation, String url, boolean added, CharSequence title, Drawable icon) { }
    private static boolean protectedItem(MenuItem item) {
        int id = item.getItemId();
        String title = item.getTitle() == null ? "" : item.getTitle().toString();
        if (title.contains("小爱") || title.contains("小愛") || title.equalsIgnoreCase("Ask AI")
                || item.getIntent() != null && Intent.ACTION_ASSIST.equals(item.getIntent().getAction())) return true;
        return id == android.R.id.copy || id == android.R.id.cut || id == android.R.id.paste || id == android.R.id.selectAll
                || id == android.R.id.shareText || id == android.R.id.pasteAsPlainText;
    }
    private static boolean translationItem(MenuItem item) {
        Intent intent = item.getIntent();
        if (intent != null && "android.intent.action.TRANSLATE".equals(intent.getAction())) return true;
        CharSequence title = item.getTitle();
        return title != null && Set.of("翻译", "翻譯", "Translate").contains(title.toString());
    }
    private static String webUrl(MenuItem item) {
        Intent intent = item.getIntent();
        return intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && RouteLink.validHttp(intent.getDataString())
                ? intent.getDataString() : null;
    }
    private static String selectedText(TextView text) {
        CharSequence value = text.getText(); int start = text.getSelectionStart(), end = text.getSelectionEnd();
        if (start < 0 || end < 0 || start == end || Math.max(start, end) > value.length()) return null;
        String selected = value.subSequence(Math.min(start, end), Math.max(start, end)).toString();
        return selected.isBlank() || selected.length() > 32768 ? null : selected;
    }
    private static String uniqueLink(TextView text) {
        String selected = selectedText(text);
        if (selected == null) return null;
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        String literal = selected.strip(); if (RouteLink.validHttp(literal)) urls.add(literal);
        CharSequence value = text.getText(); int start = Math.min(text.getSelectionStart(), text.getSelectionEnd());
        int end = Math.max(text.getSelectionStart(), text.getSelectionEnd());
        if (value instanceof Spanned spans) for (URLSpan span : spans.getSpans(start, end, URLSpan.class)) {
            if (spans.getSpanStart(span) < end && spans.getSpanEnd(span) > start && RouteLink.validHttp(span.getURL())) urls.add(span.getURL());
        }
        return urls.size() == 1 ? urls.iterator().next() : null;
    }
}
