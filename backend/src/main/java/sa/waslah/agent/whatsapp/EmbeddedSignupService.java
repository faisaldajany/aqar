package sa.waslah.agent.whatsapp;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import sa.waslah.agent.common.ApiException;
import sa.waslah.agent.config.WaslahProperties;
import sa.waslah.agent.tenant.TenantContext;

/**
 * Completes Meta Embedded Signup for the signed-in office. The portal runs FB.login with our
 * configuration id and posts back the code plus the WABA and phone number ids Meta reports.
 */
@Service
public class EmbeddedSignupService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedSignupService.class);

    public record Request(String code, String wabaId, String phoneNumberId, boolean coexistence) {
    }

    public record Result(UUID accountId, String displayPhone, boolean coexistence) {
    }

    private final WaslahProperties props;
    private final MetaGraphClient meta;
    private final WaAccounts accounts;
    private final TransactionTemplate tx;

    public EmbeddedSignupService(WaslahProperties props, MetaGraphClient meta, WaAccounts accounts, TransactionTemplate tx) {
        this.props = props;
        this.meta = meta;
        this.accounts = accounts;
        this.tx = tx;
    }

    public Result complete(Request r) {
        UUID officeId = TenantContext.require();

        if (props.whatsapp().isMock()) {
            String phoneId = blank(r.phoneNumberId()) ? "mock-pn-" + officeId.toString().substring(0, 8) : r.phoneNumberId();
            String waba = blank(r.wabaId()) ? "mock-waba" : r.wabaId();
            UUID id = tx.execute(s -> accounts.save(officeId, waba, phoneId, "+966 50 000 0000", "مكتب تجريبي", "mock-token", true));
            return new Result(id, "+966 50 000 0000", true);
        }

        if (blank(r.code()) || blank(r.wabaId()) || blank(r.phoneNumberId())) {
            throw ApiException.badRequest("signup_incomplete", "لم تكتمل نافذة Meta. أعد المحاولة.");
        }
        String token = meta.exchangeCode(r.code());
        meta.subscribeApp(r.wabaId(), token);
        MetaGraphClient.PhoneInfo info = meta.phoneInfo(r.phoneNumberId(), token);

        if (r.coexistence()) {
            meta.startCoexistenceSync(r.phoneNumberId(), token);
        } else if (!blank(props.whatsapp().registrationPin())) {
            meta.register(r.phoneNumberId(), token, props.whatsapp().registrationPin());
        }

        UUID id = tx.execute(s -> accounts.save(officeId, r.wabaId(), r.phoneNumberId(),
                info.displayPhone(), info.verifiedName(), token, r.coexistence()));
        log.info("Office {} connected WhatsApp number {} (coexistence={})", officeId, r.phoneNumberId(), r.coexistence());
        return new Result(id, info.displayPhone(), r.coexistence());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
