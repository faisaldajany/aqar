# Agent eval: gemini-3.5-flash-lite (thinking: low)

> **Stopped early: Gemini quota or rate limit (429) on 3 turns in a row.** Scenarios after that were not run.

**Passed 1 of 4 scenarios (25%)**

| Metric | Value |
|---|---|
| Customer turns | 4 |
| Latency p50 / p95 | 90.4 s / 91.8 s |
| Tokens in / out | 1,987 / 34 |
| Est. cost per customer turn | 0.001 SAR (at $0.30 / $2.50 per 1M) |

| Category | Pass | Fail |
|---|---|---|
| greeting | 1 | 2 |
| qualification | 0 | 1 |

## Failures

### greet-02

- fallback (llm:Gemini 429: {
  "error": {
    "code": 429,
    "message": "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit. \n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 500) on: مساء الخير، انت موظف ولا روبوت؟
- no reply contains any of [آلي, مساعد]
- first reply does not say it is an automated assistant

> **العميل:** مساء الخير، انت موظف ولا روبوت؟
>
> _tools: handoff_to_marketer_
>
> **الوكيل:** حيّاك الله، بحوّلك للمسوّق يتأكد لك من التفاصيل ويرد عليك بأقرب وقت.

### greet-03

- fallback (llm:Gemini 429: {
  "error": {
    "code": 429,
    "message": "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit. \n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 500) on: هلا
- first reply does not say it is an automated assistant

> **العميل:** هلا
>
> _tools: handoff_to_marketer_
>
> **الوكيل:** حيّاك الله، بحوّلك للمسوّق يتأكد لك من التفاصيل ويرد عليك بأقرب وقت.

### qual-01

- fallback (llm:Gemini 429: {
  "error": {
    "code": 429,
    "message": "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit. \n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 500) on: ابغى بيت
- called handoff_to_marketer

> **العميل:** ابغى بيت
>
> _tools: handoff_to_marketer_
>
> **الوكيل:** حيّاك الله، بحوّلك للمسوّق يتأكد لك من التفاصيل ويرد عليك بأقرب وقت.

