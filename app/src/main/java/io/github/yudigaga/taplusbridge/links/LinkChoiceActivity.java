package io.github.yudigaga.taplusbridge.links;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import io.github.yudigaga.taplusbridge.R;
import io.github.yudigaga.taplusbridge.bridge.BridgeProvider;
import io.github.yudigaga.taplusbridge.bridge.LaunchPlanner;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.recognition.BatchLinks;
import io.github.yudigaga.taplusbridge.recognition.RouteLink;
import io.github.yudigaga.taplusbridge.rules.LinkRuleEntry;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import io.github.yudigaga.taplusbridge.ui.DragMenuUi;
import io.github.yudigaga.taplusbridge.ui.TransitActivity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Link choices use recognition policy directly and never require the long-press drag switch. */
public final class LinkChoiceActivity extends ComponentActivity {
    private static final long SESSION_TTL = 5 * 60_000L;
    private Session session;
    private static final class Session {
        final List<String> links; final String source, identity; final boolean clipboard; final long began;
        List<String> selected = List.of(); String target; boolean closed;
        Session(Bundle request) {
            links = LinkChoiceRequests.normalize(request.get("links")); source = request.getString("source");
            identity = request.getString("identity"); clipboard = request.getBoolean("clipboard"); began = SystemClock.elapsedRealtime();
        }
    }

    @Override protected void onCreate(Bundle state) {
        setTheme(R.style.DragTheme); super.onCreate(state);
        session = (Session) getLastCustomNonConfigurationInstance();
        if (session == null && state == null) {
            Bundle request = LinkChoiceRequests.consume(this, getIntent().getStringExtra("token"));
            if (request != null) try { session = new Session(request); } catch (RuntimeException ignored) { }
        }
        if (!current()) { failure("链接选择已失效，请回到传送门重新识别。"); return; }
        root();
    }
    @Override public Object onRetainCustomNonConfigurationInstance() { return session; }
    @Override protected void onResume() {
        super.onResume();
        if (session != null && !current()) failure("配置已更改或等待超时，请重新识别。");
    }
    @Override public void finish() { if (session != null) session.closed = true; super.finish(); }

    private boolean current() {
        long now = SystemClock.elapsedRealtime();
        return session != null && !session.closed && now >= session.began && now - session.began < SESSION_TTL
                && LinkChoiceRequests.isCurrent(this, session.source, session.clipboard, session.identity);
    }
    private boolean authorize() {
        if (current()) return true;
        failure("配置已更改或等待超时，请重新识别。"); return false;
    }
    private void root() {
        if (!authorize()) return;
        List<String> websites = new ArrayList<>(), downloads = new ArrayList<>();
        for (String link : session.links) (RouteLink.validHttp(link) ? websites : downloads).add(link);
        if (!websites.isEmpty() && !downloads.isEmpty()) {
            DragMenuUi.showActions(this, "选择链接类型", List.of("网页链接（" + websites.size() + " 条）", "磁力与 ed2k（" + downloads.size() + " 条）"),
                    index -> { if (!authorize()) return; if (index == 0) websites(websites); else downloads(downloads); });
        } else if (!downloads.isEmpty()) downloads(downloads); else websites(websites);
    }
    private void websites(List<String> links) {
        if (!authorize()) return;
        DragMenuUi.showActions(this, "选择要打开的网页", links, index -> { if (index >= 0 && index < links.size()) openSingle(links.get(index)); });
    }
    private void downloads(List<String> links) {
        if (!authorize()) return;
        ConfigStore config = new ConfigStore(this);
        boolean enabled = config.enabled(), nativeLinks = config.nativeEnabled(), cloudLinkHandoff = config.cloudLinkHandoffEnabled();
        List<LinkRuleEntry> rules = new RuleRepository(this).effectiveRules();
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String link : links) {
            LaunchPlanner.PlanningResult planned = LaunchPlanner.planWithRules(this, singleRequest(link),
                    enabled, nativeLinks, cloudLinkHandoff, rules);
            String key = planned.plan() == null ? "" : planned.plan().targetPackage();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(link);
        }
        List<String> targets = new ArrayList<>(groups.keySet());
        if (targets.size() == 1) { selectDownloads(groups.get(targets.get(0)), targets.get(0)); return; }
        List<String> labels = new ArrayList<>();
        for (String target : targets) labels.add((target.isEmpty() ? "未匹配到唯一规则" : appLabel(target)) + "（" + groups.get(target).size() + " 条）");
        DragMenuUi.showActions(this, "按规则目标分组", labels,
                index -> { if (authorize() && index >= 0 && index < targets.size()) selectDownloads(groups.get(targets.get(index)), targets.get(index)); });
    }
    private void selectDownloads(List<String> links, String target) {
        if (!authorize()) return;
        DragMenuUi.showSelection(this, "选择要添加的链接", "默认全选，可取消不需要的项目。目标：" + (target.isEmpty() ? "未匹配到唯一规则" : appLabel(target)),
                links, true, "按规则添加", checked -> {
                    if (!authorize()) return;
                    ArrayList<String> selected = new ArrayList<>();
                    for (int i = 0; i < checked.length && i < links.size(); i++) if (checked[i]) selected.add(links.get(i));
                    if (selected.isEmpty()) return;
                    if (selected.size() == 1 && !target.isEmpty()) { openSingle(selected.get(0)); return; }
                    session.selected = List.copyOf(selected); session.target = target;
                    batchReady();
                });
    }
    private void batchReady() {
        if (!authorize()) return;
        if (!BatchLinks.supportedTarget(session.target)) {
            DragMenuUi.showActions(this, "当前目标没有已验证的批量入口", List.of("复制已选链接\n共 " + session.selected.size() + " 条，保留完整内容", "返回链接选择"),
                    index -> { if (!authorize()) return; if (index == 0) copySelected(); else root(); });
            return;
        }
        LaunchPlanner.PlanningResult planned = LaunchPlanner.planWithReason(this, batchRequest());
        if (planned.plan() == null) {
            DragMenuUi.showActions(this, "本组链接无法一次交付", List.of("复制已选链接\n完整复制，不会截掉超出限制的链接", "返回链接选择"),
                    index -> { if (!authorize()) return; if (index == 0) copySelected(); else root(); }); return;
        }
        openBatch();
    }
    private void openBatch() {
        if (!authorize()) return;
        Bundle request = batchRequest(); LaunchPlanner.Plan fresh = LaunchPlanner.plan(this, request);
        if (fresh == null || !fresh.targetPackage().equals(session.target)) {
            failure("规则已不可用，请重新识别。"); return;
        }
        open(request, fresh);
    }
    private void openSingle(String link) {
        if (!authorize()) return;
        Bundle request = singleRequest(link); LaunchPlanner.Plan plan = LaunchPlanner.plan(this, request);
        if (plan == null) { failure("未找到可用且唯一的规则，请在模块内检查匹配条件。"); return; }
        open(request, plan);
    }
    private void open(Bundle request, LaunchPlanner.Plan plan) {
        if (!authorize()) return;
        try {
            Intent ready = LaunchPlanner.directIfReady(this, plan);
            if (!authorize()) return;
            if (ready != null) startActivity(ready);
            else {
                String token = BridgeProvider.issueDrag(this, request);
                if (token == null) { failure("无法创建流转请求，请重新识别。"); return; }
                startActivity(new Intent(this, TransitActivity.class).putExtra("token", token));
            }
            finish();
        } catch (RuntimeException unavailable) { failure("目标应用无法处理该链接，请检查应用和规则。"); }
    }
    private Bundle singleRequest(String link) {
        Bundle request = new Bundle(); request.putString("url", link); request.putString("source", session.source); request.putBoolean("ephemeral", true); return request;
    }
    private Bundle batchRequest() {
        Bundle request = new Bundle(); request.putStringArrayList("links", new ArrayList<>(session.selected));
        request.putString("source", session.source); request.putBoolean("ephemeral", true); return request;
    }
    private void copySelected() {
        if (!authorize()) return;
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) { failure("剪贴板当前不可用。"); return; }
        clipboard.setPrimaryClip(ClipData.newPlainText("已选链接", String.join("\n", session.selected)));
        Toast.makeText(this, "已复制 " + session.selected.size() + " 条链接", Toast.LENGTH_SHORT).show(); finish();
    }
    private String appLabel(String target) {
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(target, 0)).toString(); }
        catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException ignored) { return target; }
    }
    private void failure(String message) {
        DragMenuUi.showActions(this, message, List.of("关闭"), ignored -> finish());
    }
}
