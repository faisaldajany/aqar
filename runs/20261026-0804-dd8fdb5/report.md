# Agent eval: gemini-3.5-flash-lite (thinking: low)

> **Stopped early: Gemini quota or rate limit (429) on 3 turns in a row.** Scenarios after that were not run.

**Passed 15 of 20 scenarios (75%)**

| Metric | Value |
|---|---|
| Customer turns | 26 |
| Latency p50 / p95 | 2.7 s / 181.8 s |
| Tokens in / out | 129,773 / 3,769 |
| Est. cost per customer turn | 0.007 SAR (at $0.30 / $2.50 per 1M) |

| Category | Pass | Fail |
|---|---|---|
| greeting | 3 | 0 |
| qualification | 5 | 1 |
| search | 7 | 1 |
| no_match | 0 | 3 |

## Failures

### qual-02

- reply longer than 8 lines
- reply longer than 8 lines

> **العميل:** ابي فيلا للشراء بالنرجس
>
> _tools: search_listings, save_lead_
>
> **الوكيل:** هلا بك، أنا المساعد الآلي لمكتب الأفق العقاري للوساطة العقارية في الرياض. أبشر، لقيت لك هذي الفلل في حي النرجس:
> 
> 1. فيلا درج داخلي، 5 غرف، مساحة 375 م²، السعر 1,950,000 ريال.
> ترخيص إعلان: 7200418836 (ينتهي في 2027-06-30)
> 
> 2. فيلا دوبلكس، 4 غرف، مساحة 312 م²، السعر 1,780,000 ريال.
> ترخيص إعلان: 7200395120 (ينتهي في 2027-05-31)
> 
> تبي نحدد موعد معاينة لأحدهما؟

> **العميل:** ميزانيتي مليونين كاش
>
> _tools: save_lead, search_listings_
>
> **الوكيل:** تمام، تم حفظ ميزانيتك. العروض الحالية بنفس النطاق في النرجس:
> 
> 1. فيلا درج داخلي، 5 غرف، مساحة 375 م²، السعر 1,950,000 ريال.
> ترخيص إعلان: 7200418836 (ينتهي في 2027-06-30)
> 
> 2. فيلا دوبلكس، 4 غرف، مساحة 312 م²، السعر 1,780,000 ريال.
> ترخيص إعلان: 7200395120 (ينتهي في 2027-05-31)
> 
> تبي نحدد موعد معاينة لإحدة الفلتين؟

### search-01

- no reply contains any of [7200418836, 7200395120]

> **العميل:** عندكم فلل للبيع في النرجس؟
>
> _tools: search_listings, save_lead_
>
> **الوكيل:** وش رايك، تبي نحدد معاينة لأحدهم؟

### nomatch-01

- fallback (llm:Gemini 429: {
  "error": {
    "code": 429,
    "message": "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit. \n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 500) on: ابغى فيلا في الدرعية
- did not call search_listings

> **العميل:** ابغى فيلا في الدرعية
>
> _tools: handoff_to_marketer_
>
> **الوكيل:** حيّاك الله، بحوّلك للمسوّق يتأكد لك من التفاصيل ويرد عليك بأقرب وقت.

### nomatch-02

- fallback (llm:Gemini 429: {
  "error": {
    "code": 429,
    "message": "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit. \n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 500) on: فيلا بالنرجس بنص مليون
- did not call search_listings

> **العميل:** فيلا بالنرجس بنص مليون
>
> _tools: handoff_to_marketer_
>
> **الوكيل:** حيّاك الله، بحوّلك للمسوّق يتأكد لك من التفاصيل ويرد عليك بأقرب وقت.

### nomatch-03

- fallback (llm:Gemini 429: {
  "error": {
    "code": 429,
    "message": "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit. \n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 500) on: عندكم محلات تجارية للإيجار؟
- did not call search_listings

> **العميل:** عندكم محلات تجارية للإيجار؟
>
> _tools: handoff_to_marketer_
>
> **الوكيل:** حيّاك الله، بحوّلك للمسوّق يتأكد لك من التفاصيل ويرد عليك بأقرب وقت.

