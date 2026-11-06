package io.github.yudigaga.taplusbridge.runtime;

import android.content.Context;
import io.github.yudigaga.taplusbridge.BuildConfig;
import io.github.yudigaga.taplusbridge.config.ConfigStore;
import io.github.yudigaga.taplusbridge.config.DragSettings;
import io.github.yudigaga.taplusbridge.config.RecognitionChannels;
import io.github.yudigaga.taplusbridge.config.SourceScopePolicy;
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
    public static final int SCHEMA=6, MAX_BYTES=364_000;
    public final int user,build;
    public final boolean enabled,recognition,capture,nativeLinks,independent,scopeCapture,scopeLinkProbe;
    public final List<LinkRuleEntry> rules;
    public final SourceScopePolicy sourceScope;
    public final DragSettings drag;
    public final RecognitionChannels channels;
    public final String identity;
    private final String raw;
    private RuntimeSnapshot(String raw,int user,int build,boolean enabled,boolean recognition,boolean capture,
                            boolean nativeLinks,boolean independent,List<LinkRuleEntry> rules,SourceScopePolicy sourceScope,
                            boolean scopeCapture,boolean scopeLinkProbe,DragSettings drag,RecognitionChannels channels) {
        this.raw=raw;this.user=user;this.build=build;this.enabled=enabled;this.recognition=recognition;
        this.capture=capture;this.nativeLinks=nativeLinks;this.independent=independent;this.rules=rules;
        this.sourceScope=sourceScope;this.scopeCapture=scopeCapture;this.scopeLinkProbe=scopeLinkProbe;
        this.drag=drag;this.channels=channels;
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
            data.put(SourceScopePolicy.KEY,new JSONObject(config.sourceScope().encode()));
            data.put(ConfigStore.SCOPE_CAPTURE_KEY,config.scopeCaptureEnabled());
            data.put(ConfigStore.SCOPE_LINK_PROBE_KEY,config.scopeLinkProbeEnabled());
            data.put(DragSettings.KEY,new JSONObject(config.getDragSettings().toJson()));
            data.put(RecognitionChannels.KEY,new JSONObject(config.recognitionChannels().toJson()));
            JSONArray array=new JSONArray();for(String rule:ScopedRuleResolver.encode(new RuleRepository(context).effectiveRules()))array.put(rule);
            data.put("rules",array);String raw=data.toString();parse(raw);return raw;
        }catch(org.json.JSONException failure){throw new IllegalStateException("configuration_invalid");}
    }
    public static RuntimeSnapshot parse(String raw) {
        try {
            if(raw==null || raw.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IllegalArgumentException();
            JSONObject data=new JSONObject(raw);
            Object schemaValue=data.get("schema");
            if(!(schemaValue instanceof Integer))throw new IllegalArgumentException();
            int schema=(Integer)schemaValue;
            if((schema<1 || schema>SCHEMA) || data.length()!=schema+9
                    || (schema==1 && data.has(SourceScopePolicy.KEY))
                    || (schema<3 && data.has(ConfigStore.SCOPE_CAPTURE_KEY))
                    || (schema<4 && data.has(ConfigStore.SCOPE_LINK_PROBE_KEY))
                    || (schema<5 && data.has(DragSettings.KEY))
                    || (schema<6 && data.has(RecognitionChannels.KEY)))throw new IllegalArgumentException();
            int user=data.getInt("user"),build=data.getInt("build");
            if(user!=0 || build<=0)throw new IllegalArgumentException();
            if(!java.util.Set.of("ROOT","SHIZUKU").contains(data.getString("mode")))throw new IllegalArgumentException();
            JSONArray array=data.getJSONArray("rules");if(array.length()>768)throw new IllegalArgumentException();
            String[] wire=new String[array.length()];for(int i=0;i<wire.length;i++)wire[i]=array.getString(i);
            SourceScopePolicy sourceScope=SourceScopePolicy.unmanaged();
            if(schema>=2) {
                Object value=data.get(SourceScopePolicy.KEY);
                if(!(value instanceof JSONObject) && !(value instanceof String))throw new IllegalArgumentException();
                sourceScope=SourceScopePolicy.parse(value.toString());
            }
            return new RuntimeSnapshot(raw,user,build,bool(data,"enabled"),bool(data,"recognition"),bool(data,"capture"),
                    bool(data,"native"),bool(data,"independent"),ScopedRuleResolver.decode(wire),sourceScope,
                    schema>=3 && bool(data,ConfigStore.SCOPE_CAPTURE_KEY),
                    schema>=4 && bool(data,ConfigStore.SCOPE_LINK_PROBE_KEY),
                    schema>=5 ? DragSettings.parse(data.getJSONObject(DragSettings.KEY).toString()) : DragSettings.defaults(),
                    schema>=6 ? RecognitionChannels.parse(data.getJSONObject(RecognitionChannels.KEY).toString()) : RecognitionChannels.defaults());
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
