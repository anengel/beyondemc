import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 纯 JDK 的网络探针（不依赖任何第三方库）。
 * 用途：判断 Gradle 所处的 JVM 环境能否完成 HTTPS，并区分是现代 TLS 栈问题还是特定站点问题。
 *
 * 运行：
 *   javac -d <outdir> NetProbe.java
 *   java -cp <outdir> NetProbe <url> [url...]
 */
public final class NetProbe {

    public static void main(String[] args) {
        System.out.println("java.version = " + System.getProperty("java.version"));
        System.out.println("java.home    = " + System.getProperty("java.home"));
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        for (String u : args) {
            probe(client, u);
        }
    }

    private static void probe(HttpClient client, String u) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(u))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(20))
                    .build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            System.out.println("OK   " + resp.statusCode() + "   " + u);
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            System.out.println("FAIL " + root.getClass().getSimpleName() + ": " + root.getMessage() + "   " + u);
        }
    }
}
