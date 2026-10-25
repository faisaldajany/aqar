package sa.waslah.agent.whatsapp;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.config.WaslahProperties;

@RestController
@RequestMapping("/api/whatsapp")
public class WhatsAppConnectController {

    private final WaslahProperties props;
    private final EmbeddedSignupService signup;

    public WhatsAppConnectController(WaslahProperties props, EmbeddedSignupService signup) {
        this.props = props;
        this.signup = signup;
    }

    /** What the portal needs to open Meta's Embedded Signup window. None of it is secret. */
    @GetMapping("/config")
    Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", props.whatsapp().isMock() ? "mock" : "cloud");
        m.put("appId", props.whatsapp().appId());
        m.put("configId", props.whatsapp().configId());
        m.put("graphVersion", props.whatsapp().graphVersion());
        return m;
    }

    @PostMapping("/connect")
    EmbeddedSignupService.Result connect(@RequestBody EmbeddedSignupService.Request request) {
        return signup.complete(request);
    }
}
