package sa.waslah.agent.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RedactorTest {

    @Test
    void masksContactDetailsButKeepsListingFacts() {
        assertThat(Redactor.text("رقمي 0551234567 أو +966 55 123 4567 أو ٠٥٥١٢٣٤٥٦٧"))
                .isEqualTo("رقمي [جوال] أو [جوال] أو [جوال]");
        assertThat(Redactor.text("هويتي 1012345678 وايميلي a.b@gmail.com")).isEqualTo("هويتي [هوية] وايميلي [بريد]");
        assertThat(Redactor.text("آيباني SA03 8000 0000 6080 1016 7519")).isEqualTo("آيباني [آيبان]");
        assertThat(Redactor.text("فيلا بـ 1,500,000 ريال، ترخيص 7100000001، مساحة 450 م، الموعد 5:30"))
                .isEqualTo("فيلا بـ 1,500,000 ريال، ترخيص 7100000001، مساحة 450 م، الموعد 5:30");
    }

    @Test
    void dropsPersonalFieldsFromToolCalls() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var masked = Redactor.json(json.readTree("""
                {"args":{"customer_name":"أبو فهد","districts":"النرجس","note":"جواله 0551234567"},
                 "result":{"listings":[{"title":"فيلا","ad_license":"7100000001"}]}}"""));
        assertThat(masked.path("args").has("customer_name")).isFalse();
        assertThat(masked.path("args").path("note").asText()).isEqualTo("جواله [جوال]");
        assertThat(masked.path("result").path("listings").get(0).path("ad_license").asText()).isEqualTo("7100000001");
    }
}
