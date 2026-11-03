package sa.waslah.agent.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class MoyasarWebhookAuthTest {

    static final String SECRET = "a-long-secret-from-the-dashboard";
    final ObjectMapper json = new ObjectMapper();

    ObjectNode event(String token) {
        ObjectNode n = json.createObjectNode().put("type", "payment_paid");
        if (token != null) n.put("secret_token", token);
        n.putObject("data").put("id", "pay_1").put("invoice_id", "inv_1");
        return n;
    }

    @Test
    void withoutASecretEverythingIsLetThrough() {
        assertThat(MoyasarWebhookAuth.allowed("", event(null), null)).isTrue();
        assertThat(MoyasarWebhookAuth.allowed(null, event("anything"), null)).isTrue();
    }

    @Test
    void dashboardWebhookNeedsTheSecretToken() {
        assertThat(MoyasarWebhookAuth.allowed(SECRET, event(SECRET), null)).isTrue();
        assertThat(MoyasarWebhookAuth.allowed(SECRET, event("wrong"), null)).isFalse();
        assertThat(MoyasarWebhookAuth.allowed(SECRET, event(null), null)).isFalse();
    }

    @Test
    void invoiceCallbackNeedsTheDerivedTokenNotTheSecretItself() {
        String token = MoyasarWebhookAuth.callbackToken(SECRET);
        assertThat(token).doesNotContain(SECRET).matches("[A-Za-z0-9_-]{43}");
        assertThat(MoyasarWebhookAuth.allowed(SECRET, json.createObjectNode().put("id", "inv_1"), token)).isTrue();
        assertThat(MoyasarWebhookAuth.allowed(SECRET, json.createObjectNode().put("id", "inv_1"), SECRET)).isFalse();
        assertThat(MoyasarWebhookAuth.allowed(SECRET, json.createObjectNode().put("id", "inv_1"), "x")).isFalse();
        // Spaces copied around the secret don't change anything.
        assertThat(MoyasarWebhookAuth.callbackToken(" " + SECRET + " ")).isEqualTo(token);
    }
}
