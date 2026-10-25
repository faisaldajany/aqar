package sa.waslah.agent.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import sa.waslah.agent.IntegrationTest;
import sa.waslah.agent.auth.AuthService;

class RowLevelSecurityTest extends IntegrationTest {

    @Autowired AuthService auth;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    @Test
    void appRoleCannotBypassRowLevelSecurity() {
        Boolean bypass = jdbc.sql("select rolsuper or rolbypassrls from pg_roles where rolname = current_user")
                .query(Boolean.class).single();
        assertThat(bypass).isFalse();
    }

    @Test
    void officesOnlySeeTheirOwnRows() {
        UUID a = signup("a").officeId();
        UUID b = signup("b").officeId();

        TenantContext.run(a, () -> tx.executeWithoutResult(s ->
                jdbc.sql("insert into contacts (office_id, wa_id) values (?, '966500000001')").param(a).update()));

        assertThat(countContacts(a)).isEqualTo(1);
        assertThat(countContacts(b)).isZero();
        assertThat(jdbc.sql("select count(*) from contacts").query(Integer.class).single())
                .as("no tenant in context sees nothing").isZero();
        assertThat(TenantContext.call(b, () -> jdbc.sql("select count(*) from offices").query(Integer.class).single()))
                .isEqualTo(1);
    }

    @Test
    void cannotWriteIntoAnotherOffice() {
        UUID a = signup("c").officeId();
        UUID b = signup("d").officeId();
        assertThatThrownBy(() -> TenantContext.run(b, () -> tx.executeWithoutResult(s ->
                jdbc.sql("insert into contacts (office_id, wa_id) values (?, 'x')").param(a).update())))
                .rootCause()
                .hasMessageContaining("row-level security");
    }

    @Test
    void pooledConnectionsDoNotLeakTheTenant() {
        UUID a = signup("e").officeId();
        TenantContext.run(a, () -> jdbc.sql("select 1").query(Integer.class).single());
        String leaked = jdbc.sql("select current_setting('app.office_id', true)").query(String.class).single();
        assertThat(leaked == null || leaked.isEmpty()).isTrue();
    }

    private int countContacts(UUID office) {
        return TenantContext.call(office, () -> jdbc.sql("select count(*) from contacts").query(Integer.class).single());
    }

    private AuthService.SignedIn signup(String tag) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        return auth.signup(new AuthService.Signup("مكتب " + tag, "12345678", "مالك", tag + id + "@test.sa", "password123"));
    }
}
