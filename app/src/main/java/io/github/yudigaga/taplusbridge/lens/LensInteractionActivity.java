package io.github.yudigaga.taplusbridge.lens;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Explicitly opened only when the existing Lens session needs a real user action. */
public final class LensInteractionActivity extends Activity {
    private LensSession session;
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = LensSession.current(getIntent().getStringExtra("request_id"));
        if (session == null) { finish(); return; }
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        TextView notice = new TextView(this); notice.setText("Google Lens 网页确认\n完成必要交互后，真实结果会返回传送门。");
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        notice.setPadding(pad, pad, pad, pad); column.addView(notice);
        Button cancel = new Button(this); cancel.setText("取消本次识图"); cancel.setOnClickListener(v -> session.userCancel());
        column.addView(cancel);
        FrameLayout content = new FrameLayout(this); column.addView(content,
                new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(column);
        if (!session.attach(this, content)) finish();
    }
    @Override public void onBackPressed() {
        if (session != null) session.userCancel(); else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        if (session != null) session.detach(this);
        super.onDestroy();
    }
}
