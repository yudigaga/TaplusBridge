package io.github.yudigaga.taplusbridge.bridge;

import android.net.Uri;
import io.github.yudigaga.taplusbridge.recognition.RouteLink;
import java.util.Locale;
import java.util.Set;
import org.json.JSONObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Verified website receiver contracts, applied only after a user rule selects the target package. */
final class CloudLinkHandoff {
    private static final String CLOUD = "com.mfcloudcalculate.networkdisk";
    private static final String SHARE_HOST = "share.123pan.cn";
    private static final Pattern SHARE_PATH = Pattern.compile("^/(?:123pan|s|ps)/([A-Za-z0-9_-]{1,128})(?:\\.html)?/?$");
    private static final Pattern SIMPLE_SHARE = Pattern.compile("^/s/([A-Za-z0-9_-]{1,128})/?$");
    private CloudLinkHandoff() { }

    static String destination(String target, String original) {
        if (!RouteLink.validHttp(original)) return original;
        try {
            Uri share = Uri.parse(original);
            String host = share.getHost().toLowerCase(Locale.ROOT);
            String path = share.getPath();
            if (path == null) return original;
            if (CLOUD.equals(target) && (host.equals(SHARE_HOST) || host.endsWith("." + SHARE_HOST))) {
                Matcher route = SHARE_PATH.matcher(path);
                if (!route.matches()) return original;
                String password = password(share);
                // The official SDK routes these fields without requiring the per-click _ukid.
                return new Uri.Builder().scheme("pan").authority("umeng.com").path("/share/list")
                        .appendQueryParameter("sharePwd", password)
                        .appendQueryParameter("_sdk_", "umeng")
                        .appendQueryParameter("_linkid_", "usr1ke01qbgngpn9")
                        .appendQueryParameter("action", "share_list")
                        .appendQueryParameter("shareKey", route.group(1))
                        .appendQueryParameter("_bizType_", "ushare").build().toString();
            }
            Matcher simple = SIMPLE_SHARE.matcher(path);
            if ("com.baidu.netdisk".equals(target) && "pan.baidu.com".equals(host) && simple.matches()
                    && simple.group(1).startsWith("1") && simple.group(1).length() > 1) {
                return new Uri.Builder().scheme("bdnetdisk").authority("n").path("/action.SHARE_LINK")
                        .appendQueryParameter("m_n_v", "8.3.0").appendQueryParameter("surl", simple.group(1).substring(1))
                        .appendQueryParameter("pwd", password(share)).appendQueryParameter("userType", "")
                        .appendQueryParameter("slDisFrom", "wap_unlog_page").appendQueryParameter("channelType", "wap")
                        .appendQueryParameter("switchiOSNewSchema", "true").appendQueryParameter("surlType", "1")
                        .appendQueryParameter("hasPanCode", "no").build().toString();
            }
            if ("com.alicloud.databox".equals(target) && Set.of("www.alipan.com", "alipan.com", "aliyundrive.com").contains(host)
                    && simple.matches() && !Set.of("bottle", "iqiyi", "album").contains(simple.group(1))
                    && (share.getEncodedQuery() == null || share.getEncodedQuery().isEmpty()) && share.getFragment() == null) {
                return new Uri.Builder().scheme("smartdrive").authority("share").path("/browse")
                        .appendQueryParameter("shareId", simple.group(1)).appendQueryParameter("sharePwd", "")
                        .appendQueryParameter("source_type", "scheme").build().toString();
            }
            if (("com.quark.browser".equals(target) || "com.quark.clouddrive".equals(target))
                    && "pan.quark.cn".equals(host) && simple.matches()) {
                JSONObject query = new JSONObject().put("pwd_id", simple.group(1));
                String password = password(share);
                if (!password.isEmpty()) query.put("passcode", password);
                JSONObject data = new JSONObject().put("statParams", new JSONObject().put("entry", "share"))
                        .put("flutter_view_mode", new JSONObject().put("immerse", true))
                        .put("params", new JSONObject().put("action", "share").put("query", query));
                String landing = new Uri.Builder().scheme("https").authority("www.myquark.cn").path("/")
                        .appendQueryParameter("qk_tech", "flutter").appendQueryParameter("qk_biz", "cloud_disk")
                        .appendQueryParameter("qk_module", "/clouddrive/main").appendQueryParameter("qk_params", data.toString()).build().toString();
                boolean browser = "com.quark.browser".equals(target);
                return new Uri.Builder().scheme(browser ? "qklink" : "qkcloudlink").authority("www.uc.cn")
                        .path(browser ? "/b20b84fd735a8dd3f7541129bacc4e9a" : "/d28241335bda4522a538cc12052f89dd")
                        .appendQueryParameter("action", "open_url").appendQueryParameter("url", landing)
                        .appendQueryParameter("src_ch", browser ? "kk@product_wangpan_share" : "kkcloud@product_wangpan_share").build().toString();
            }
            if ("com.ylmf.androidclient".equals(target) && ("share.115.com".equals(host) && path.length() > 1
                    || Set.of("115.com", "115cdn.com", "anxia.com").contains(host) && simple.matches()))
                return "oof.disk://url/" + Uri.encode(original);
            if ("com.xunlei.downloadprovider".equals(target) && "pan.xunlei.com".equals(host) && simple.matches())
                return new Uri.Builder().scheme("xunleiapp").authority("xunlei.com").path("/webview")
                        .appendQueryParameter("url", original).appendQueryParameter("title", "迅雷云盘")
                        .appendQueryParameter("from", "pan.xunlei.com").build().toString();
            return original;
        } catch (RuntimeException | org.json.JSONException invalid) { return original; }
    }

    /** Decode query names as the website does, and reject competing or malformed password fields. */
    private static String password(Uri share) {
        if (share.getFragment() != null && !share.getFragment().isEmpty())
            throw new IllegalArgumentException("unsupported_fragment");
        String password = "", query = share.getEncodedQuery();
        int fields = 0;
        if (query != null) for (String field : query.split("&", -1)) {
            if (field.isEmpty()) continue;
            int equal = field.indexOf('=');
            String name = equal < 0 ? field : field.substring(0, equal);
            if (!"pwd".equals(Uri.decode(name.replace("+", " "))))
                throw new IllegalArgumentException("unsupported_share_parameter");
            if (++fields > 1) throw new IllegalArgumentException("ambiguous_password");
            password = Uri.decode((equal < 0 ? "" : field.substring(equal + 1)).replace("+", " "));
        }
        if (password.length() > 128 || password.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("invalid_password");
        return password;
    }
}
