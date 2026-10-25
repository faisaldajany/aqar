package sa.waslah.agent.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import sa.waslah.agent.config.WaslahProperties;

class UnitTests {

    @Nested
    class Signature {
        final WebhookSignatureVerifier v = new WebhookSignatureVerifier("secret");
        final byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

        @Test
        void acceptsMatchingSignature() {
            assertThat(v.verify(body, v.header(body))).isTrue();
        }

        @Test
        void rejectsTamperedBodyMissingOrMalformedHeader() {
            String sig = v.header(body);
            assertThat(v.verify("{\"a\":2}".getBytes(StandardCharsets.UTF_8), sig)).isFalse();
            assertThat(v.verify(body, null)).isFalse();
            assertThat(v.verify(body, "sha1=abc")).isFalse();
            assertThat(v.verify(body, "sha256=zz")).isFalse();
        }

        @Test
        void rejectsEverythingWithoutASecret() {
            WebhookSignatureVerifier none = new WebhookSignatureVerifier("");
            assertThat(none.verify(body, v.header(body))).isFalse();
        }
    }

    @Nested
    class Window {
        final MessagingWindowPolicy p = new MessagingWindowPolicy();
        final Instant now = Instant.parse("2026-10-25T18:00:00Z");

        @Test
        void openWithin24Hours() {
            assertThat(p.isOpen(now.minusSeconds(23 * 3600), now)).isTrue();
        }

        @Test
        void closedNearAndAfterTheEdgeOrWithoutInbound() {
            assertThat(p.isOpen(now.minusSeconds(24 * 3600 - 60), now)).isFalse();
            assertThat(p.isOpen(now.minusSeconds(25 * 3600), now)).isFalse();
            assertThat(p.isOpen(null, now)).isFalse();
        }
    }

    @Nested
    class Cipher {
        @Test
        void roundTripsAndUsesFreshIv() {
            TokenCipher c = new TokenCipher(new WaslahProperties(null,
                    "ZGV2LW9ubHkta2V5LWNoYW5nZS1tZS0zMi1ieXRlcyE=", false, false, null, null, null));
            String a = c.encrypt("EAAG-token");
            assertThat(c.decrypt(a)).isEqualTo("EAAG-token");
            assertThat(c.encrypt("EAAG-token")).isNotEqualTo(a);
        }
    }

    @Nested
    class Content {
        final ObjectMapper json = new ObjectMapper();

        @Test
        void extractsTextVoiceAndInteractive() throws Exception {
            assertThat(WebhookProcessor.content(json.readTree("{\"type\":\"text\",\"text\":{\"body\":\"هلا\"}}")).body())
                    .isEqualTo("هلا");
            WebhookProcessor.Content voice = WebhookProcessor.content(json.readTree("{\"type\":\"audio\",\"audio\":{\"id\":\"M1\"}}"));
            assertThat(voice.type()).isEqualTo("audio");
            assertThat(voice.mediaId()).isEqualTo("M1");
            assertThat(WebhookProcessor.content(json.readTree(
                    "{\"type\":\"interactive\",\"interactive\":{\"button_reply\":{\"id\":\"b\",\"title\":\"احجز\"}}}")).body())
                    .isEqualTo("احجز");
        }
    }
}
