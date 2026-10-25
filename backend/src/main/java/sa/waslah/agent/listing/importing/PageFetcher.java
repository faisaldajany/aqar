package sa.waslah.agent.listing.importing;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import sa.waslah.agent.common.ApiException;

/** Fetches a public web page. Kept behind an interface so tests never touch the network. */
public interface PageFetcher {

    String fetch(URI uri);

    @Component
    class Http implements PageFetcher {
        private static final int MAX_BYTES = 2 * 1024 * 1024;
        private final RestClient http;

        public Http(RestClient.Builder builder) {
            SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
            rf.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
            rf.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
            this.http = builder.requestFactory(rf).build();
        }

        @Override
        public String fetch(URI uri) {
            try {
                byte[] body = http.get().uri(uri)
                        .header("User-Agent", "Mozilla/5.0 (compatible; WaslahListingImport/1.0)")
                        .header("Accept-Language", "ar,en;q=0.8")
                        .retrieve().body(byte[].class);
                if (body == null || body.length == 0) {
                    throw ApiException.badGateway("page_empty", "صفحة الإعلان فارغة.");
                }
                int n = Math.min(body.length, MAX_BYTES);
                return new String(body, 0, n, StandardCharsets.UTF_8);
            } catch (RestClientException e) {
                throw ApiException.badGateway("page_unreachable", "تعذّر فتح صفحة الإعلان. تأكد من الرابط وحاول مرة أخرى.");
            }
        }
    }
}
