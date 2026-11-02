package sa.waslah.agent.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import sa.waslah.agent.config.WaslahProperties;

/** The exact Graph API requests for creating and finding message templates. */
class MetaTemplatesApiTest {

    MockRestServiceServer server;
    MetaGraphClient meta;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        meta = new MetaGraphClient(builder, new WaslahProperties.WhatsApp("cloud", "v23.0", "app", "secret", "verify", "cfg", "123456"));
    }

    @Test
    void createsAUtilityTemplateWithAnExample() {
        WaTemplates.Kind k = WaTemplates.Kind.HANDOFF;
        server.expect(requestTo("https://graph.facebook.com/v23.0/WABA1/message_templates"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok"))
                .andExpect(jsonPath("$.name").value("hidaa_handoff_v1"))
                .andExpect(jsonPath("$.language").value("ar"))
                .andExpect(jsonPath("$.category").value("UTILITY"))
                .andExpect(jsonPath("$.components[0].type").value("BODY"))
                .andExpect(jsonPath("$.components[0].text").value(k.body))
                .andExpect(jsonPath("$.components[0].example.body_text[0][0]").value("أبو فهد"))
                .andRespond(withSuccess("{\"id\":\"T1\",\"status\":\"PENDING\",\"category\":\"UTILITY\"}", MediaType.APPLICATION_JSON));
        MetaGraphClient.TemplateState t = meta.createTemplate("WABA1", "tok", k.templateName, "ar", k.body, k.example);
        assertThat(t.id()).isEqualTo("T1");
        assertThat(t.status()).isEqualTo("PENDING");
        server.verify();
    }

    @Test
    void anExistingTemplateIsLookedUpInstead() {
        server.expect(requestTo("https://graph.facebook.com/v23.0/WABA1/message_templates"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"message\":\"Message template already exists\",\"code\":100,\"error_subcode\":2388024}}"));
        server.expect(requestTo(startsWith("https://graph.facebook.com/v23.0/WABA1/message_templates?name=hidaa_reminder_v1")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"data":[{"id":"T9","name":"hidaa_reminder_v1","status":"APPROVED","language":"ar","rejected_reason":"NONE"}]}""",
                        MediaType.APPLICATION_JSON));
        WaTemplates.Kind k = WaTemplates.Kind.REMINDER;
        MetaGraphClient.TemplateState t = meta.createTemplate("WABA1", "tok", k.templateName, "ar", k.body, k.example);
        assertThat(t.status()).isEqualTo("APPROVED");
        assertThat(t.id()).isEqualTo("T9");
        assertThat(t.rejectedReason()).isNull();
        server.verify();
    }

    @Test
    void rejectionReasonIsKept() {
        server.expect(requestTo(startsWith("https://graph.facebook.com/v23.0/WABA1/message_templates?name=hidaa_followup_v1")))
                .andRespond(withSuccess("""
                        {"data":[{"id":"T3","name":"hidaa_followup_v1","status":"REJECTED","language":"ar","rejected_reason":"INVALID_FORMAT"}]}""",
                        MediaType.APPLICATION_JSON));
        assertThat(meta.findTemplate("WABA1", "tok", "hidaa_followup_v1", "ar"))
                .contains(new MetaGraphClient.TemplateState("T3", "hidaa_followup_v1", "REJECTED", "INVALID_FORMAT"));
    }
}
