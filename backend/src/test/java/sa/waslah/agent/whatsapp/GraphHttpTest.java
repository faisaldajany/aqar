package sa.waslah.agent.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Meta replies with Content-Type text/javascript on some calls; those replies must still be read as JSON. */
class GraphHttpTest {

    static final MediaType META_JS = MediaType.parseMediaType("text/javascript; charset=UTF-8");

    @Test
    void readsJsonSentAsJavascript() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer meta = MockRestServiceServer.bindTo(builder).build();
        meta.expect(requestTo("https://graph.facebook.com/v27.0/4402536303360170/subscribed_apps"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"success\":true}", META_JS));

        JsonNode res = GraphHttp.client(builder, "v27.0").post().uri("/{waba}/subscribed_apps", "4402536303360170")
                .retrieve().body(JsonNode.class);

        assertThat(res.path("success").asBoolean()).isTrue();
        meta.verify();
    }

    @Test
    void aSentMessageReturnsItsIdWhateverTheReplyType() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer meta = MockRestServiceServer.bindTo(builder).build();
        meta.expect(requestTo("https://graph.facebook.com/v27.0/1060136380514681/messages"))
                .andRespond(withSuccess("{\"messaging_product\":\"whatsapp\",\"contacts\":[{\"input\":\"966500000000\",\"wa_id\":\"966500000000\"}],"
                        + "\"messages\":[{\"id\":\"wamid.TEST123\"}]}", META_JS));

        String id = new CloudApiWhatsAppClient(builder, "v27.0")
                .sendText(new WhatsAppClient.Credentials("1060136380514681", "token"), "966500000000", "مرحبا");

        assertThat(id).isEqualTo("wamid.TEST123");
        meta.verify();
    }

    @Test
    void ordinaryJsonStillWorks() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer meta = MockRestServiceServer.bindTo(builder).build();
        meta.expect(requestTo(startsWith("https://graph.facebook.com/v27.0/1060136380514681?fields=")))
                .andRespond(withSuccess("{\"display_phone_number\":\"+1 555-917-6025\",\"verified_name\":\"Test Number\"}", MediaType.APPLICATION_JSON));

        JsonNode res = GraphHttp.client(builder, "v27.0").get()
                .uri(b -> b.path("/{id}").queryParam("fields", "display_phone_number,verified_name").build("1060136380514681"))
                .retrieve().body(JsonNode.class);

        assertThat(res.path("display_phone_number").asText()).isEqualTo("+1 555-917-6025");
        meta.verify();
    }
}
