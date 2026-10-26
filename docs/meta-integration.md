# Meta / WhatsApp integration

## Prerequisites (owner: Faisal)

1. Meta Business portfolio for Hidaa, **business verification** completed.
2. A Meta app with the WhatsApp product, registered as a **Tech Provider**, so other businesses can
   onboard their numbers through Embedded Signup.
3. An **Embedded Signup configuration** (gives `META_CONFIG_ID`) with WhatsApp Business app
   onboarding (coexistence) enabled.
4. App Review for the permissions Embedded Signup needs (`whatsapp_business_management`,
   `whatsapp_business_messaging`).
5. Webhook callback `https://<api-host>/webhooks/whatsapp`, verify token = `WA_VERIFY_TOKEN`,
   subscribed fields: `messages`, `smb_message_echoes`, `smb_app_state_sync`.
6. A privacy policy URL and a website: Meta asks for both during verification.

## What the code does

| Step | Where |
|---|---|
| Portal opens FB.login with `config_id`, `featureType: whatsapp_business_app_onboarding` | `portal/src/app/(app)/whatsapp/page.tsx` |
| Portal reads `WA_EMBEDDED_SIGNUP` postMessage (WABA id, phone number id, finish event) | same |
| Backend exchanges the code for a token | `MetaGraphClient.exchangeCode` |
| Subscribes the app to the WABA | `MetaGraphClient.subscribeApp` |
| Reads display number and verified name | `MetaGraphClient.phoneInfo` |
| Coexistence: requests contacts sync then history sync | `MetaGraphClient.startCoexistenceSync` |
| Stores the token AES-GCM encrypted, adds the webhook route | `WaAccounts.save` |
| Verifies `X-Hub-Signature-256` on every webhook | `WebhookSignatureVerifier` |
| Customer messages, statuses, marketer echoes | `WebhookProcessor` |
| Agent pauses when a marketer replies from the app | `WebhookProcessor.onHumanEcho` |
| Free-form replies only inside the 24-hour window | `MessagingWindowPolicy`, `OutboundMessageService` |

## Confirm against Meta's current docs before going live

These were written from memory of Meta's API and are not yet tested against a real app:

- [ ] Graph API version to pin (`WA_GRAPH_VERSION`, default `v23.0`).
- [ ] Exact Embedded Signup `extras` for coexistence and the `sessionInfoVersion` value.
- [ ] Names of the postMessage finish events (`FINISH`, `FINISH_WHATSAPP_BUSINESS_APP_ONBOARDING`).
- [ ] `smb_app_data` request body and the deadline for starting the history sync.
- [ ] Shape of `smb_message_echoes` payloads (`message_echoes[]`, `to` = customer).
- [ ] Whether coexistence numbers must skip `/register` (the code skips it).
- [ ] Token type returned by the code exchange and whether it expires.
