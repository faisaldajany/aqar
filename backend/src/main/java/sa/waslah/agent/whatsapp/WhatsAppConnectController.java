package sa.waslah.agent.whatsapp;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final WaTemplates templates;
    private final WhatsAppSetup setup;

    public record Choice(Boolean hasBusinessApp) {
    }

    public record Confirm(String value) {
    }

    public WhatsAppConnectController(WaslahProperties props, EmbeddedSignupService signup, WaTemplates templates,
                                     WhatsAppSetup setup) {
        this.setup = setup;
        this.templates = templates;
        this.props = props;
        this.signup = signup;
    }

    /** What the portal needs to open Meta's Embedded Signup window. None of it is secret. */
    /** Meta's review of the office's message templates (needed for messages outside the 24-hour window). */
    @GetMapping("/templates")
    java.util.List<java.util.Map<String, Object>> templateStatuses() {
        return templates.statuses();
    }

    @PostMapping("/templates/sync")
    java.util.List<java.util.Map<String, Object>> syncTemplates() {
        templates.syncOffice();
        return templates.statuses();
    }

    @GetMapping("/config")
    Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", props.whatsapp().isMock() ? "mock" : "cloud");
        m.put("appId", props.whatsapp().appId());
        m.put("configId", props.whatsapp().configId());
        m.put("graphVersion", props.whatsapp().graphVersion());
        return m;
    }

    /** The step-by-step setup for offices without a WhatsApp Business account yet (see {@link WhatsAppSetup}). */
    @GetMapping("/setup")
    Map<String, Object> setup() {
        return setup.state();
    }

    @PostMapping("/setup/path")
    Map<String, Object> choose(@RequestBody Choice c) {
        return setup.choose(c != null && Boolean.TRUE.equals(c.hasBusinessApp()));
    }

    @PostMapping("/setup/steps/{key}")
    Map<String, Object> confirm(@PathVariable String key, @RequestBody(required = false) Confirm c) {
        return setup.confirm(key, c == null ? null : c.value());
    }

    @DeleteMapping("/setup/steps/{key}")
    Map<String, Object> undo(@PathVariable String key) {
        return setup.undo(key);
    }

    @PostMapping("/connect")
    EmbeddedSignupService.Result connect(@RequestBody EmbeddedSignupService.Request request) {
        return signup.complete(request);
    }
}
