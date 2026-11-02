package io.github.yudigaga.taplusbridge.lens;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import java.util.function.BooleanSupplier;

/** A separate native result window; result rendering belongs to the caller. */
public final class LensResultSheet implements View.OnAttachStateChangeListener {
    private final View anchor;
    private final Dialog dialog;
    private final FrameLayout root;
    private final DragSheet sheet;
    private final LinearLayout chrome;
    private final FrameLayout content;
    private final ImageView preview;
    private final Button originalPage;
    private final Runnable onDismiss;
    private BooleanSupplier backHandler;
    private Runnable openOriginalPage;
    private ValueAnimator heightAnimation;
    private boolean expanded, shown, dismissed;
    private int usableHeight, collapsedHeight, currentHeight;

    public LensResultSheet(View anchor, Bitmap selectedImage, Runnable onDismiss) {
        this.anchor = anchor; this.onDismiss = onDismiss;
        boolean dark = (anchor.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        dialog = new Dialog(anchor.getContext(), android.R.style.Theme_Material_NoActionBar) {
            @SuppressWarnings("deprecation")
            @Override public void onBackPressed() { handleBack(); }
        };
        root = new FrameLayout(anchor.getContext());
        root.setOnClickListener(view -> dismiss());
        preview = new ImageView(anchor.getContext()); preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription("当前选中图片"); preview.setPadding(dp(24), dp(24), dp(24), dp(24));
        if (selectedImage != null && !selectedImage.isRecycled()) preview.setImageBitmap(selectedImage);
        root.addView(preview, new FrameLayout.LayoutParams(-1, 0, Gravity.TOP));
        sheet = new DragSheet(); sheet.setOrientation(LinearLayout.VERTICAL); sheet.setClickable(true);
        sheet.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View view, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(view, info);
                info.setDismissable(true); info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS);
            }
            @Override public boolean performAccessibilityAction(View view, int action, android.os.Bundle arguments) {
                if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_DISMISS) { dismiss(); return true; }
                return super.performAccessibilityAction(view, action, arguments);
            }
        });
        sheet.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(dark ? Color.rgb(25, 25, 25) : Color.WHITE);
        float radius = dp(28); background.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        sheet.setBackground(background);
        root.addView(sheet, new FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM));
        chrome = new LinearLayout(anchor.getContext()); chrome.setOrientation(LinearLayout.VERTICAL);
        FrameLayout handleArea = new FrameLayout(anchor.getContext());
        View handle = new View(anchor.getContext());
        GradientDrawable handleColor = new GradientDrawable(); handleColor.setColor(dark ? 0xff707070 : 0xffcccccc);
        handleColor.setCornerRadius(dp(2)); handle.setBackground(handleColor);
        handleArea.addView(handle, new FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER));
        handleArea.setContentDescription("向上滑动展开识图结果，向下滑动收起");
        chrome.addView(handleArea, new LinearLayout.LayoutParams(-1, dp(20)));
        LinearLayout header = new LinearLayout(anchor.getContext()); header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(new View(anchor.getContext()), new LinearLayout.LayoutParams(0, 1, 1));
        originalPage = new Button(anchor.getContext()); originalPage.setText("原网页"); originalPage.setAllCaps(false);
        originalPage.setMinWidth(dp(72)); originalPage.setEnabled(false);
        originalPage.setBackgroundColor(Color.TRANSPARENT); originalPage.setElevation(0); originalPage.setStateListAnimator(null);
        originalPage.setPadding(dp(12), 0, dp(12), 0);
        originalPage.setTextColor(new android.content.res.ColorStateList(new int[][]{
                new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{dark ? 0xff777777 : 0xffaaaaaa, dark ? 0xff8ab4f8 : 0xff1967d2}));
        originalPage.setContentDescription("原网页暂不可用，结果网址尚未返回");
        originalPage.setOnClickListener(view -> { if (originalPage.isEnabled() && openOriginalPage != null) openOriginalPage.run(); });
        header.addView(originalPage, new LinearLayout.LayoutParams(-2, -2));
        chrome.addView(header, new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(chrome, new LinearLayout.LayoutParams(-1, -2));
        content = new FrameLayout(anchor.getContext());
        sheet.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> remeasure());
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime());
                root.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            remeasure(); return insets;
        });
        dialog.setContentView(root); dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(ignored -> closed());
    }

    public FrameLayout contentContainer() { return content; }
    public boolean isExpanded() { return expanded; }
    public boolean isShowing() { return shown && !dismissed; }

    public void setInteractionHidden(boolean hidden) {
        Window window = dialog.getWindow();
        if (!dismissed && window != null) window.getDecorView().setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
    }

    public void setOriginalPage(boolean enabled, Runnable open) {
        openOriginalPage = open;
        originalPage.setEnabled(enabled && open != null);
        originalPage.setContentDescription(originalPage.isEnabled() ? "在浏览器中打开本次识图原网页"
                : "原网页暂不可用，结果网址尚未返回");
    }

    /** Return true when a nested source-page viewer has already handled this back action. */
    public void setBackHandler(BooleanSupplier handler) { backHandler = handler; }

    public boolean show() {
        if (dismissed || !anchor.isAttachedToWindow()) return false;
        if (shown) return true;
        if (!(anchor.getRootView().getLayoutParams() instanceof WindowManager.LayoutParams hostWindow)) return false;
        Window window = dialog.getWindow(); if (window == null) return false;
        window.setType(hostWindow.type);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); window.setDimAmount(0.45f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false);
        else window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        try {
            dialog.show(); shown = true;
            window.setLayout(-1, -1); window.setGravity(Gravity.FILL);
            anchor.addOnAttachStateChangeListener(this); root.requestApplyInsets(); root.post(this::remeasure);
            return true;
        } catch (RuntimeException unavailable) {
            dismissed = true; preview.setImageDrawable(null);
            try { dialog.dismiss(); } catch (RuntimeException ignored) { }
            return false;
        }
    }

    public void expand() { setExpanded(true, true); }
    public void collapse() { setExpanded(false, true); }
    public void dismiss() {
        if (dismissed) return;
        if (shown) dialog.dismiss();
        closed();
    }

    private void handleBack() {
        if (backHandler != null && backHandler.getAsBoolean()) return;
        if (expanded) collapse(); else dismiss();
    }

    private void closed() {
        if (dismissed) return;
        dismissed = true; shown = false;
        if (heightAnimation != null) heightAnimation.cancel();
        anchor.removeOnAttachStateChangeListener(this); preview.setImageDrawable(null);
        originalPage.setEnabled(false); openOriginalPage = null; backHandler = null;
        try { if (onDismiss != null) onDismiss.run(); }
        finally { content.removeAllViews(); }
    }

    private void remeasure() {
        int height = Math.max(0, root.getHeight() - root.getPaddingTop() - root.getPaddingBottom());
        if (height == 0 || height == usableHeight) return;
        usableHeight = height;
        collapsedHeight = Math.min(height, Math.max(dp(180), Math.round(height * 0.45f)));
        if (heightAnimation != null) heightAnimation.cancel();
        applyHeight(expanded ? usableHeight : collapsedHeight);
    }

    private void setExpanded(boolean value, boolean animate) {
        expanded = value;
        if (usableHeight == 0) { root.post(this::remeasure); return; }
        if (heightAnimation != null) heightAnimation.cancel();
        int target = value ? usableHeight : collapsedHeight;
        if (!animate || target == currentHeight) { applyHeight(target); return; }
        heightAnimation = ValueAnimator.ofInt(currentHeight, target); heightAnimation.setDuration(180);
        heightAnimation.setInterpolator(new DecelerateInterpolator());
        heightAnimation.addUpdateListener(animation -> applyHeight((Integer) animation.getAnimatedValue()));
        heightAnimation.start();
    }

    private void applyHeight(int height) {
        currentHeight = height;
        ViewGroup.LayoutParams params = sheet.getLayoutParams(); params.height = height; sheet.setLayoutParams(params);
        ViewGroup.LayoutParams image = preview.getLayoutParams(); image.height = Math.max(0, usableHeight - height);
        preview.setLayoutParams(image); preview.setVisibility(image.height > 0 ? View.VISIBLE : View.GONE);
    }

    private boolean scrollsUp(View view) {
        if (view.getVisibility() != View.VISIBLE) return false;
        if (view.canScrollVertically(-1)) return true;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++)
            if (scrollsUp(group.getChildAt(i))) return true;
        return false;
    }

    private boolean touchesWebView(View view, float x, float y) {
        if (view.getVisibility() != View.VISIBLE || x < 0 || y < 0 || x >= view.getWidth() || y >= view.getHeight()) return false;
        if (view instanceof WebView) return true;
        if (view instanceof ViewGroup group) for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (touchesWebView(child, x + group.getScrollX() - child.getLeft() - child.getTranslationX(),
                    y + group.getScrollY() - child.getTop() - child.getTranslationY())) return true;
        }
        return false;
    }

    private int dp(int value) { return Math.round(value * anchor.getResources().getDisplayMetrics().density); }
    @Override public void onViewAttachedToWindow(View view) { }
    @Override public void onViewDetachedFromWindow(View view) { dismiss(); }

    private final class DragSheet extends LinearLayout {
        private final int slop = ViewConfiguration.get(anchor.getContext()).getScaledTouchSlop();
        private float downX, downY;
        private int startHeight;
        private boolean dragging, childOwns, headerTouch;
        DragSheet() { super(anchor.getContext()); }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    if (heightAnimation != null) heightAnimation.cancel();
                    downX = event.getRawX(); downY = event.getRawY(); startHeight = currentHeight;
                    dragging = false; headerTouch = event.getY() <= chrome.getBottom();
                    childOwns = !headerTouch && touchesWebView(content, event.getX() - content.getLeft(), event.getY() - content.getTop());
                }
                case MotionEvent.ACTION_POINTER_DOWN -> childOwns = true;
                case MotionEvent.ACTION_MOVE -> {
                    float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    if (Math.abs(dx) > slop && Math.abs(dx) >= Math.abs(dy)) childOwns = true;
                    if (childOwns) return false;
                    if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx) * 1.2f
                            && (headerTouch || !scrollsUp(content)) && (dy < 0 ? !expanded : expanded)) {
                        dragging = true; return true;
                    }
                }
            }
            return dragging;
        }

        // LinearLayout handles ordinary taps; this custom branch only consumes intercepted drags.
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!dragging) return super.onTouchEvent(event);
            float dy = event.getRawY() - downY;
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE)
                applyHeight(Math.max(collapsedHeight, Math.min(usableHeight, startHeight - Math.round(dy))));
            else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                boolean target = Math.abs(dy) > dp(48) ? dy < 0 : currentHeight > (collapsedHeight + usableHeight) / 2;
                dragging = false; setExpanded(target, true);
            }
            return true;
        }
    }
}
