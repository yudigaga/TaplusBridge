package io.github.yudigaga.taplusbridge.translation;

import static org.junit.Assert.*;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadow.api.Shadow;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, manifest=Config.NONE)
public final class TranslationAppsAndroidTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private final ShadowPackageManager packages=Shadow.extract(context.getPackageManager());

    @Test public void advertisedComponentsStayDistinctByActionAndExcludeInaccessibleTargets() {
        ResolveInfo valid=target("com.example.translate",true,true,null);
        for(String action:List.of(TranslationApps.ACTION_TRANSLATE,Intent.ACTION_PROCESS_TEXT,Intent.ACTION_SEND)) {
            packages.addResolveInfoForIntent(probe(action),valid);
            packages.addResolveInfoForIntent(probe(action),valid);
        }
        packages.addResolveInfoForIntent(new Intent(Intent.ACTION_SEND).setType("text/plain"),target("com.example.privateapp",false,true,null));
        packages.addResolveInfoForIntent(new Intent(Intent.ACTION_SEND).setType("text/plain"),target("com.example.disabled",true,false,null));
        packages.addResolveInfoForIntent(new Intent(Intent.ACTION_SEND).setType("text/plain"),target("com.example.permission",true,true,"com.example.UNGRANTED"));
        packages.addResolveInfoForIntent(new Intent(Intent.ACTION_SEND).setType("text/plain"),target("io.github.yudigaga.taplusbridge",true,true,null));
        packages.addResolveInfoForIntent(new Intent(Intent.ACTION_SEND).setType("text/plain"),target("com.miui.contentextension",true,true,null));
        List<TranslationApps.Target> found=TranslationApps.installed(context);
        assertEquals(3,found.size());
        assertEquals(List.of(TranslationApps.ACTION_TRANSLATE,Intent.ACTION_PROCESS_TEXT,Intent.ACTION_SEND),found.stream().map(TranslationApps.Target::action).toList());
        assertTrue(found.get(0).label().contains("专用翻译"));
        assertTrue(found.get(1).label().contains("文字处理"));
        assertTrue(found.get(2).label().contains("分享兼容"));
    }

    @Test public void dispatchUsesOnlyTheSavedExplicitActionAndItsDocumentedTextExtra() {
        ResolveInfo valid=target("com.example.translate",true,true,null);
        for(String action:List.of(TranslationApps.ACTION_TRANSLATE,Intent.ACTION_PROCESS_TEXT,Intent.ACTION_SEND)) {
            packages.addResolveInfoForIntent(probe(action),valid);
            TranslationSettings settings=settings(action);
            Intent intent=TranslationApps.intent(context,settings,"private source text");
            assertEquals(new ComponentName("com.example.translate","com.example.translate.Entry"),intent.getComponent());
            assertEquals(action,intent.getAction());assertEquals(TranslationApps.ACTION_TRANSLATE.equals(action)?null:"text/plain",intent.getType());
            String extra=Intent.ACTION_PROCESS_TEXT.equals(action)?Intent.EXTRA_PROCESS_TEXT:Intent.EXTRA_TEXT;
            assertEquals("private source text",intent.getStringExtra(extra));
            if(Intent.ACTION_PROCESS_TEXT.equals(action))assertTrue(intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY,false));
            assertNull(intent.getData());assertNull(intent.getSelector());
        }
        context.getPackageManager().setComponentEnabledSetting(new ComponentName("com.example.translate","com.example.translate.Entry"),PackageManager.COMPONENT_ENABLED_STATE_DISABLED,0);
        assertThrows(IllegalArgumentException.class,()->TranslationApps.intent(context,settings(Intent.ACTION_SEND),"source"));
    }

    private TranslationSettings settings(String action) {
        return new TranslationSettings(TranslationSettings.Mode.APP,"com.example.translate/com.example.translate.Entry",action,"","","","中文","");
    }
    private Intent probe(String action) {
        return TranslationApps.ACTION_TRANSLATE.equals(action)?new Intent(action):new Intent(action).setType("text/plain");
    }
    private ResolveInfo target(String pkg,boolean exported,boolean enabled,String permission) {
        PackageInfo info=new PackageInfo();info.packageName=pkg;info.applicationInfo=new ApplicationInfo();
        info.applicationInfo.packageName=pkg;info.applicationInfo.enabled=true;info.applicationInfo.flags=ApplicationInfo.FLAG_INSTALLED;
        packages.installPackage(info);
        ResolveInfo result=new ResolveInfo();result.isDefault=true;result.nonLocalizedLabel=pkg;
        result.activityInfo=new ActivityInfo();result.activityInfo.packageName=pkg;result.activityInfo.name=pkg+".Entry";
        result.activityInfo.applicationInfo=info.applicationInfo;result.activityInfo.enabled=enabled;
        result.activityInfo.exported=exported;result.activityInfo.permission=permission;return result;
    }
}
