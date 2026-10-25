package sa.waslah.agent.whatsapp;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** WhatsApp numbers connected by offices, plus the tenant-free webhook routing table. */
@Repository
public class WaAccounts {

    public record Account(UUID id, UUID officeId, String wabaId, String phoneNumberId, String accessTokenEnc) {
    }

    private final JdbcClient jdbc;
    private final TokenCipher cipher;

    public WaAccounts(JdbcClient jdbc, TokenCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** Runs without a tenant: only reads the routing table, which holds no customer data. */
    public Optional<UUID> officeForPhoneNumberId(String phoneNumberId) {
        return jdbc.sql("select office_id from wa_phone_routes where phone_number_id = ?")
                .param(phoneNumberId)
                .query(UUID.class)
                .optional();
    }

    /** Tenant-scoped (RLS). */
    public Optional<Account> byPhoneNumberId(String phoneNumberId) {
        return jdbc.sql("select id, office_id, waba_id, phone_number_id, access_token_enc from wa_accounts where phone_number_id = ?")
                .param(phoneNumberId)
                .query((rs, n) -> new Account(
                        rs.getObject("id", UUID.class),
                        rs.getObject("office_id", UUID.class),
                        rs.getString("waba_id"),
                        rs.getString("phone_number_id"),
                        rs.getString("access_token_enc")))
                .optional();
    }

    public Optional<Account> byId(UUID id) {
        return jdbc.sql("select id, office_id, waba_id, phone_number_id, access_token_enc from wa_accounts where id = ?")
                .param(id)
                .query((rs, n) -> new Account(
                        rs.getObject("id", UUID.class),
                        rs.getObject("office_id", UUID.class),
                        rs.getString("waba_id"),
                        rs.getString("phone_number_id"),
                        rs.getString("access_token_enc")))
                .optional();
    }

    public WhatsAppClient.Credentials credentials(Account a) {
        return new WhatsAppClient.Credentials(a.phoneNumberId(), cipher.decrypt(a.accessTokenEnc()));
    }

    /** Must run inside a tenant transaction. Fails with a unique violation if another office owns the number. */
    public UUID save(UUID officeId, String wabaId, String phoneNumberId, String displayPhone, String verifiedName,
                     String accessToken, boolean coexistence) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into wa_accounts (id, office_id, waba_id, phone_number_id, display_phone, verified_name,
                                         access_token_enc, coexistence)
                values (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, officeId, wabaId, phoneNumberId, displayPhone, verifiedName, cipher.encrypt(accessToken), coexistence)
                .update();
        jdbc.sql("insert into wa_phone_routes (phone_number_id, office_id) values (?, ?)")
                .params(phoneNumberId, officeId)
                .update();
        return id;
    }
}
