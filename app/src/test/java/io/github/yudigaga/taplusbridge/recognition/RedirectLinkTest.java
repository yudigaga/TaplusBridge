package io.github.yudigaga.taplusbridge.recognition;

import static org.junit.Assert.*;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class RedirectLinkTest {
    private static final String DIRECT="https://1817601681.share.123pan.cn/123pan/E4XTjv-BeZ53";
    private static String normalize(String value) throws Exception {
        try {
            Class<?> type=Class.forName("io.github.yudigaga.taplusbridge.recognition.RedirectLink");
            Method method=type.getDeclaredMethod("normalize",String.class);
            return (String)method.invoke(null,value);
        } catch(ClassNotFoundException|NoSuchMethodException missing) { throw new AssertionError("generic redirect normalization is missing",missing); }
    }
    private static String wrap(String path,String key,String target) {
        return "https://bridge.example/"+path+"?"+key+"="+URLEncoder.encode(target,StandardCharsets.UTF_8);
    }
    @Test public void directLinksAndOrdinaryPageParametersRemainUnchanged() throws Exception {
        assertEquals(DIRECT,normalize(DIRECT));
        String ordinary=wrap("article","url",DIRECT);
        assertEquals(ordinary,normalize(ordinary));
    }
    @Test public void explicitRedirectPathsDecodeOneWholeHttpTargetAcrossSources() throws Exception {
        for(String path:new String[]{"redirect","link","go","jump","out"})
            for(String key:new String[]{"url","target","redirect_url","destination","redirect_uri"})
                assertEquals(DIRECT,normalize(wrap(path,key,DIRECT)));
        assertEquals(DIRECT,normalize("https://www.coolapk.com/link?url="+URLEncoder.encode(DIRECT,StandardCharsets.UTF_8)));
    }
    @Test public void atMostTwoLayersResolveAndDeeperWrappersRemainWhole() throws Exception {
        String first=wrap("link","url",DIRECT),second=wrap("redirect","target",first),third=wrap("go","url",second);
        assertEquals(DIRECT,normalize(second));
        assertEquals(third,normalize(third));
    }
    @Test public void multipleTargetFieldsAreAmbiguousEvenWhenTheyRepeatTheSameValue() throws Exception {
        String first=wrap("link","url",DIRECT);
        assertNull(normalize(first+"&target="+URLEncoder.encode("https://other.example/path",StandardCharsets.UTF_8)));
        assertNull(normalize(first+"&url="+URLEncoder.encode(DIRECT,StandardCharsets.UTF_8)));
        assertNull(normalize("https://bridge.example/link?url=%C3%28&target="+URLEncoder.encode(DIRECT,StandardCharsets.UTF_8)));
    }
    @Test public void malformedEncodedTargetsKeepTheirValidWrapperAndNeverInventAUrl() throws Exception {
        for(String target:new String[]{"javascript:alert(1)","https://user:pass@target.example/path","https://target.example/%0A","not a url"}) {
            String wrapper=wrap("link","url",target);assertEquals(wrapper,normalize(wrapper));
        }
        String invalidUtf8="https://bridge.example/link?url=https%3A%2F%2Ftarget.example%2F%C3%28";
        assertEquals(invalidUtf8,normalize(invalidUtf8));
        String plus="https://target.example/a+b?value=x+y";
        assertEquals(plus,normalize(wrap("link","url",plus)));
    }
    @Test public void outerCredentialsControlsAndOversizedInputAreRejected() throws Exception {
        assertNull(normalize("https://user:pass@bridge.example/link?url=https%3A%2F%2Ftarget.example"));
        assertNull(normalize("https://bridge.example/a\nb"));
        assertNull(normalize("https://bridge.example/"+"a".repeat(16384)));
    }
}
