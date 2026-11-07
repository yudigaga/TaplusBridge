package io.github.yudigaga.taplusbridge.translation;

import static org.junit.Assert.*;

import android.os.CancellationSignal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,manifest=Config.NONE)
public final class AiTranslationClientAndroidTest {
    private TranslationSettings settings() {
        return new TranslationSettings(TranslationSettings.Mode.AI,"","","https://ai.example/custom/chat",
                "sentinel-private-key","model-id","中文","只作忠实翻译");
    }
    @Test public void requestKeepsSourceInItsOwnMessageAndDoesNotFollowRedirects() throws Exception {
        FakeConnection connection=new FakeConnection(200,"{\"choices\":[{\"message\":{\"content\":\"译文\"}}]}");
        AtomicInteger calls=new AtomicInteger();
        assertEquals("译文",AiTranslationClient.translate(settings(),"private source https://private.example/",new CancellationSignal(),url->{
            calls.incrementAndGet();assertEquals("https://ai.example/custom/chat",url.toString());return connection;
        }));
        assertEquals(1,calls.get());assertFalse(connection.getInstanceFollowRedirects());
        assertEquals("Bearer sentinel-private-key",connection.getRequestProperty("Authorization"));
        assertEquals("POST",connection.getRequestMethod());assertTrue(connection.disconnected);
        JSONObject request=new JSONObject(connection.sent.toString(StandardCharsets.UTF_8));
        assertEquals("model-id",request.getString("model"));assertFalse(request.getBoolean("stream"));
        assertEquals("system",request.getJSONArray("messages").getJSONObject(0).getString("role"));
        assertTrue(request.getJSONArray("messages").getJSONObject(0).getString("content").contains("中文"));
        assertFalse(request.getJSONArray("messages").getJSONObject(0).getString("content").contains("private source"));
        assertEquals("private source https://private.example/",request.getJSONArray("messages").getJSONObject(1).getString("content"));
    }
    @Test public void textBlockResponsesAreJoinedWhileEmptyOrOversizedResponsesAreRejected() throws Exception {
        FakeConnection blocks=new FakeConnection(200,"{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"你好\"},{\"type\":\"text\",\"text\":\"世界\"}]}}]}");
        assertEquals("你好世界",AiTranslationClient.translate(settings(),"hello",null,url->blocks));
        FakeConnection blank=new FakeConnection(200,"{\"choices\":[{\"message\":{\"content\":\" \"}}]}");
        assertEquals("translation_response_invalid",assertThrows(IOException.class,
                ()->AiTranslationClient.translate(settings(),"hello",null,url->blank)).getMessage());
        FakeConnection large=new FakeConnection(200,"x".repeat(1024*1024+1));
        assertEquals("translation_response_too_large",assertThrows(IOException.class,
                ()->AiTranslationClient.translate(settings(),"hello",null,url->large)).getMessage());
    }
    @Test public void cancellationAndHttpFailuresNeverRetryOrReturnPrivateRemoteDetails() throws Exception {
        CancellationSignal cancelled=new CancellationSignal();cancelled.cancel();AtomicInteger calls=new AtomicInteger();
        assertEquals("translation_cancelled",assertThrows(IOException.class,()->AiTranslationClient.translate(settings(),"text",cancelled,url->{
            calls.incrementAndGet();return new FakeConnection(200,"{}");
        })).getMessage());assertEquals(0,calls.get());
        FakeConnection redirect=new FakeConnection(302,"sentinel-private-key private text https://private.example/");
        assertEquals("translation_http_302",assertThrows(IOException.class,()->AiTranslationClient.translate(settings(),"text",null,url->{calls.incrementAndGet();return redirect;})).getMessage());
        assertEquals(1,calls.get());assertFalse(redirect.readBody);
        IOException failure=assertThrows(IOException.class,()->AiTranslationClient.translate(settings(),"text",null,url->{
            throw new IOException("sentinel-private-key https://private.example/");
        }));assertEquals("translation_network",failure.getMessage());assertNull(failure.getCause());
    }
    @Test public void cancelledReadDisconnectsAndInputLimitFailsBeforeOpeningTheNetwork() throws Exception {
        CancellationSignal signal=new CancellationSignal();
        FakeConnection connection=new FakeConnection(200,"{}");connection.onRead=signal::cancel;
        assertEquals("translation_cancelled",assertThrows(IOException.class,
                ()->AiTranslationClient.translate(settings(),"text",signal,url->connection)).getMessage());
        assertTrue(connection.disconnected);
        assertEquals("translation_input",assertThrows(IOException.class,
                ()->AiTranslationClient.translate(settings(),"a".repeat(32769),null,url->{throw new AssertionError("must not connect");})).getMessage());
    }
    @Test public void truncatedOrFilteredOutputCannotBeReportedAsACompleteTranslation() throws Exception {
        for (String reason : new String[]{"length","content_filter","tool_calls"}) {
            FakeConnection partial = new FakeConnection(200,"{\"choices\":[{\"finish_reason\":\""+reason+"\",\"message\":{\"content\":\"partial translation\"}}]}");
            assertEquals("translation_response_invalid",assertThrows(IOException.class,
                    ()->AiTranslationClient.translate(settings(),"hello",null,url->partial)).getMessage());
        }
    }
    private static final class FakeConnection extends HttpURLConnection {
        final int status;final byte[] content;final ByteArrayOutputStream sent=new ByteArrayOutputStream();
        boolean disconnected,readBody;Runnable onRead;
        FakeConnection(int status,String response)throws java.net.MalformedURLException {super(new URL("https://ai.example/custom/chat"));this.status=status;content=response.getBytes(StandardCharsets.UTF_8);}
        @Override public int getResponseCode(){return status;}
        @Override public long getContentLengthLong(){return content.length;}
        @Override public java.io.OutputStream getOutputStream(){return sent;}
        @Override public InputStream getInputStream(){readBody=true;if(onRead!=null)onRead.run();return new ByteArrayInputStream(content);}
        @Override public void disconnect(){disconnected=true;}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){}
    }
}
