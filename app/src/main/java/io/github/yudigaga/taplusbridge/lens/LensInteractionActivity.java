package io.github.yudigaga.taplusbridge.lens;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Displays the live Google Lens WebView from the owning session process. */
public final class LensInteractionActivity extends Activity {
    private LensSession session;
    private boolean returnToSheet;
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = LensSession.current(getIntent().getStringExtra("request_id"));
        if (session == null) { finish(); return; }
        returnToSheet = getIntent().getBooleanExtra("return_to_sheet", false);
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        TextView notice = new TextView(this);
        notice.setText(returnToSheet ? "Google Lens 原网页（当前图片会话）" : "Google Lens 网页确认\n完成必要交互后，真实结果会返回传送门。");
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        notice.setPadding(pad, pad, pad, pad); column.addView(notice);
        Button action = new Button(this);
        action.setText(returnToSheet ? "返回识图结果" : "取消本次识图");
        action.setOnClickListener(v -> {
            if (returnToSheet) { session.returnToSheet(this); finish(); }
            else session.userCancel();
        });
        column.addView(action);
        FrameLayout content = new FrameLayout(this); column.addView(content,
                new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(column);
        if (!session.attach(this, content)) finish();
    }
    @Override public void onBackPressed() {
        if (session == null) { super.onBackPressed(); return; }
        if (returnToSheet) {
            if (!session.goBackInViewer(this)) { session.returnToSheet(this); finish(); }
        }
        else session.userCancel();
    }
    boolean isSessionViewer() { return returnToSheet; }
    @Override protected void onDestroy() {
        if (session != null) {
            if (returnToSheet && isFinishing()) session.returnToSheet(this);
            else session.detach(this);
        }
        super.onDestroy();
    }
}
