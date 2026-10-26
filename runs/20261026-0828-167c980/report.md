# Agent eval: gemini-3.5-flash-lite (thinking: low)

> **Stopped early: Gemini quota or rate limit (429) on 3 turns in a row.** Scenarios after that were not run.

**Passed 5 of 8 scenarios (63%)**

| Metric | Value |
|---|---|
| Customer turns | 10 |
| Latency p50 / p95 | 1.9 s / 151.4 s |
| Tokens in / out | 33,367 / 893 |
| Est. cost per customer turn | 0.005 SAR (at $0.30 / $2.50 per 1M) |

| Category | Pass | Fail |
|---|---|---|
| greeting | 3 | 0 |
| qualification | 2 | 3 |

## Failures

### qual-03

- fallback (llm:Gemini 429: {
  "error": {
    "code": 429,
    "message": "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit. \n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 500) on: بالملقا، وميزانيتي 70 ألف بالسنة

> **العميل:** دوري على شقة إيجار
>
> _tools: search_listings_
>
> **الوكيل:** أبشر، لقيت لك هالشقات للإيجار:
> - شقة غرفتين في القيروان، المساحة 120 م²، السعر 48000 ريال سنوياً، ترخيص رقم 7200429955 ينتهي في 2027-01-18.
> - شقة 3 غرف في الملقا، المساحة 160 م²، السعر 65000 ريال سنوياً، ترخيص رقم 7200402271 ينتهي في 2027-04-30.
> 
> أي وحدة منهم تفضل؟

> **العميل:** بالملقا، وميزانيتي 70 ألف بالسنة
>
> _tools: search_listings, save_lead, handoff_to_marketer_
>
> **الوكيل:** حيّاك الله، بحوّلك للمسوّق يتأكد لك من التفاصيل ويرد عليك بأقرب وقت.

### qual-04

- fallback (llm:Gemini 429: {
  "error": {
    "code": 429,
    "message": "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit. \n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 500) on: عندي تمويل من البنك وأبغى فيلا شمال الرياض حدود مليون وتسعمية
- did not call save_lead
- lead.payment = null, expected FINANCE

> **العميل:** عندي تمويل من البنك وأبغى فيلا شمال الرياض حدود مليون وتسعمية
>
> _tools: handoff_to_marketer_
>
> **الوكيل:** حيّاك الله، بحوّلك للمسوّق يتأكد لك من التفاصيل ويرد عليك بأقرب وقت.

### qual-05

- fallback (llm:Gemini 429: {
  "error": {
    "code": 429,
    "message": "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit. \n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 500) on: اسمي أبو فهد، ابغى شقة تمليك في حطين خلال ثلاث شهور
- did not call save_lead
- lead.purpose = null, expected SALE

> **العميل:** اسمي أبو فهد، ابغى شقة تمليك في حطين خلال ثلاث شهور
>
> _tools: handoff_to_marketer_
>
> **الوكيل:** حيّاك الله، بحوّلك للمسوّق يتأكد لك من التفاصيل ويرد عليك بأقرب وقت.

