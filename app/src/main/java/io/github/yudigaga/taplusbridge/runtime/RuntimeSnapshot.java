package io.github.yudigaga.taplusbridge.runtime;

import android.content.Context;
import io.github.yudigaga.taplusbridge.BuildConfig;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.rules.LinkRuleEntry;
import io.github.yudigaga.taplusbridge.rules.RuleRepository;
import io.github.yudigaga.taplusbridge.rules.ScopedRuleResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** One framework-owned preference value: no URLs, tickets or chat data. */
public final class RuntimeSnapshot {
    public static final String GROUP="taplus-runtime-v1", KEY="owner_configuration";
    public static final int SCHEMA=1, MAX_BYTES=300_000;
    public final int user,build;
    public final boolean enabled,recognition,capture,nativeLinks,independent;
    public final List<LinkRuleEntry> rules;
    public final String identity;
    private final String raw;
    private RuntimeSnapshot(String raw,int user,int build,boolean enabled,boolean recognition,boolean capture,
                            boolean nativeLinks,boolean independent,List<LinkRuleEntry> rules) {
        this.raw=raw;this.user=user;this.build=build;this.enabled=enabled;this.recognition=recognition;
        this.capture=capture;this.nativeLinks=nativeLinks;this.independent=independent;this.rules=rules;
        this.identity=digest(raw);
    }
    public static String from(Context context) {
        ConfigStore config=new ConfigStore(context);
        try {
            JSONObject data=new JSONObject();data.put("schema",SCHEMA);data.put("user",android.os.Process.myUid()/100000);
            data.put("build",BuildConfig.VERSION_CODE);data.put("enabled",config.enabled());
            data.put("recognition",config.recognitionEnabled());data.put("capture",config.captureEnabled());
            data.put("native",config.nativeEnabled());data.put("independent",config.independentEnabled());
            data.put("mode",config.mode().name());
            JSONArray array=new JSONArray();for(String rule:ScopedRuleResolver.encode(new RuleRepository(context).effectiveRules()))array.put(rule);
            data.put("rules",array);String raw=data.toString();parse(raw);return raw;
        }catch(org.json.JSONException failure){throw new IllegalStateException("configuration_invalid");}
    }
    public static RuntimeSnapshot parse(String raw) {
        try {
            if(raw==null || raw.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IllegalArgumentException();
            JSONObject data=new JSONObject(raw);
            if(data.getInt("schema")!=SCHEMA || data.length()!=10)throw new IllegalArgumentException();
            int user=data.getInt("user"),build=data.getInt("build");
            if(user!=0 || build<=0)throw new IllegalArgumentException();
            if(!java.util.Set.of("ROOT","SHIZUKU").contains(data.getString("mode")))throw new IllegalArgumentException();
            JSONArray array=data.getJSONArray("rules");if(array.length()>768)throw new IllegalArgumentException();
            String[] wire=new String[array.length()];for(int i=0;i<wire.length;i++)wire[i]=array.getString(i);
            return new RuntimeSnapshot(raw,user,build,bool(data,"enabled"),bool(data,"recognition"),bool(data,"capture"),
                    bool(data,"native"),bool(data,"independent"),ScopedRuleResolver.decode(wire));
        }catch(org.json.JSONException|RuntimeException invalid){throw new IllegalArgumentException("configuration_invalid");}
    }
    private static boolean bool(JSONObject data,String key)throws org.json.JSONException {
        Object value=data.get(key);if(!(value instanceof Boolean))throw new IllegalArgumentException();return (Boolean)value;
    }
    public String encode(){return raw;}
    public boolean usable(){return user==0 && build==BuildConfig.VERSION_CODE && independent;}
    public static String digest(String raw) {
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder text=new StringBuilder(64);for(byte b:bytes)text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return text.toString();
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
