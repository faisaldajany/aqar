package sa.waslah.agent.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A Graph refusal shows Meta's own reason, so the team knows what to fix. */
class MetaReasonTest {

    @Test
    void readsMetasMessage() {
        assertThat(MetaGraphClient.metaReason("{\"error\":{\"message\":\"(#200) Permissions error\",\"type\":\"OAuthException\",\"code\":200}}"))
                .isEqualTo("(#200) Permissions error");
    }

    @Test
    void prefersTheUserMessageWhenMetaGivesOne() {
        assertThat(MetaGraphClient.metaReason("{\"error\":{\"message\":\"Invalid parameter\",\"error_user_msg\":\"The phone number is not on this account.\"}}"))
                .isEqualTo("The phone number is not on this account.");
    }

    @Test
    void emptyOrUnreadableBodiesGiveNoReason() {
        assertThat(MetaGraphClient.metaReason(null)).isEmpty();
        assertThat(MetaGraphClient.metaReason("<html>502</html>")).isEmpty();
        assertThat(MetaGraphClient.metaReason("{}")).isEmpty();
    }
}
