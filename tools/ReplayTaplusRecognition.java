import io.github.yudigaga.taplusbridge.recognition.LinkExtractor;
import io.github.yudigaga.taplusbridge.routing.DeepLinks;
import io.github.yudigaga.taplusbridge.routing.RouteDefaults;
import io.github.yudigaga.taplusbridge.routing.RouteMatcher;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipFile;

/** Local replay prints metadata only. Neither recognition text nor URLs are written to disk/stdout. */
public final class ReplayTaplusRecognition {
    public static void main(String[] args) {
        String marker = "onPickText sContent:";
        int events = 0;
        try (ZipFile zip = new ZipFile(args[0]);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        zip.getInputStream(zip.getEntry("full.log")), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.contains("17:56:27.287") || !line.contains("Taplus.TextContentExtensionService")
                        || !line.contains(marker)) continue;
                events++;
                String body = line.substring(line.indexOf(marker) + marker.length());
                List<String> links = LinkExtractor.extract(body);
                System.out.println("event=" + events + " bodyUtf16Chars=" + body.length()
                        + " extractedLinks=" + links.size());
                int index = 0;
                for (String url : links) {
                    URI uri = new URI(url);
                    RouteMatcher.Decision decision = RouteMatcher.decide(RouteDefaults.create(), url, "com.tencent.mm");
                    System.out.println("link=" + (++index) + " urlChars=" + url.length()
                            + " scheme=" + uri.getScheme() + " host=" + uri.getHost()
                            + " pathShape=" + shape(uri.getRawPath())
                            + " queryChars=" + (uri.getRawQuery() == null ? 0 : uri.getRawQuery().length())
                            + " ambiguous=" + decision.ambiguous()
                            + " target=" + (decision.rule() == null ? "none" : decision.rule().targetPackage()));
                    if (decision.rule() != null) {
                        String converted = decision.rule().convert()
                                ? DeepLinks.convert(url, decision.rule().targetPackage()) : url;
                        URI destination = new URI(converted);
                        System.out.println("conversionChanged=" + !url.equals(converted)
                                + " destinationScheme=" + destination.getScheme()
                                + " destinationHost=" + destination.getHost()
                                + " destinationPathShape=" + shape(destination.getRawPath()));
                    }
                }
            }
            System.out.println("matchedEvents=" + events);
        } catch (Exception error) {
            // URI exception messages can embed the full URL; print only the class.
            System.out.println("replayFailure=" + error.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private static String shape(String path) {
        if (path == null) return "none";
        if (path.matches("/(?:share/)?video/[0-9]{1,20}/?")) return "/share-or-video/[numeric-id]";
        if (path.matches("/detail/[0-9]{1,20}")) return "/detail/[numeric-id]";
        return "other-path-" + path.length() + "-chars";
    }
}
