package sa.waslah.agent.whatsapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sa.waslah.agent.config.WaslahProperties;

@RestController
@RequestMapping("/webhooks/whatsapp")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WaslahProperties props;
    private final WebhookSignatureVerifier verifier;
    private final WebhookProcessor processor;

    public WebhookController(WaslahProperties props, WebhookSignatureVerifier verifier, WebhookProcessor processor) {
        this.props = props;
        this.verifier = verifier;
        this.processor = processor;
    }

    /** Meta's one-time subscription check. */
    @GetMapping
    ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
                                  @RequestParam(name = "hub.verify_token", required = false) String token,
                                  @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode) && props.whatsapp().verifyToken().equals(token) && challenge != null) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    /**
     * Rejects unsigned bodies. Returns 500 on processing errors so Meta retries; storage is
     * idempotent, so a retried delivery never duplicates a message.
     */
    @PostMapping
    ResponseEntity<Void> receive(@RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
                                 @RequestBody byte[] body) {
        if (!verifier.verify(body, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            processor.process(body);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Webhook processing failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
