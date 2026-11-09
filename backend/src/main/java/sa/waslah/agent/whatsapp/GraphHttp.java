package sa.waslah.agent.whatsapp;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * HTTP client for graph.facebook.com. Meta answers some calls (POSTs among them) with
 * {@code Content-Type: text/javascript} around a plain JSON body, which Spring's JSON reader refuses, so the call
 * fails on our side after Meta already did the work. This client also reads that type as JSON.
 */
final class GraphHttp {

    static final MediaType TEXT_JAVASCRIPT = new MediaType("text", "javascript");

    private GraphHttp() {
    }

    static RestClient client(RestClient.Builder builder, String graphVersion) {
        return builder.baseUrl("https://graph.facebook.com/" + graphVersion)
                .messageConverters(converters -> converters.add(javascriptAsJson()))
                .build();
    }

    /** Added after the defaults, so it only reads what they refuse and never changes how requests are written. */
    private static MappingJackson2HttpMessageConverter javascriptAsJson() {
        MappingJackson2HttpMessageConverter c = new MappingJackson2HttpMessageConverter();
        c.setSupportedMediaTypes(List.of(TEXT_JAVASCRIPT));
        return c;
    }
}
