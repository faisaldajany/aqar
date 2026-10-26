# Agent eval: gemini-3.5-flash-lite (thinking: low)

**Passed 44 of 49 scenarios (90%)**

| Metric | Value |
|---|---|
| Customer turns | 64 |
| Latency p50 / p95 | 1.7 s / 49.5 s |
| Tokens in / out | 338,398 / 9,178 |
| Est. cost per customer turn | 0.007 SAR (at $0.30 / $2.50 per 1M) |

| Category | Pass | Fail |
|---|---|---|
| greeting | 3 | 0 |
| qualification | 5 | 1 |
| search | 8 | 0 |
| no_match | 4 | 0 |
| expired | 2 | 0 |
| negotiation | 3 | 2 |
| human | 3 | 0 |
| viewing | 5 | 2 |
| listing_card | 2 | 0 |
| out_of_scope | 5 | 0 |
| dialect | 4 | 0 |

## Failures

### qual-04

- did not call save_lead
- lead.payment = null, expected FINANCE

> **العميل:** عندي تمويل من البنك وأبغى فيلا شمال الرياض حدود مليون وتسعمية
>
> _tools: handoff_to_marketer_
>
> **الوكيل:** أبشر، راح يحولك النظام الآن على الزميل خالد عشان يفيدك بخصوص التمويل والعقارات المناسبة لميزانيتك.

### nego-03

- fallback (llm:Gemini 429: {
  "error": {
    "code": 429,
    "message": "You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits. To monitor your current usage, head to: https://ai.dev/rate-limit. \n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 15,) on: ممكن أدفع إيجار شقة الملقا على أربع دفعات؟

> **العميل:** ممكن أدفع إيجار شقة الملقا على أربع دفعات؟
>
> _tools: handoff_to_marketer_
>
> **الوكيل:** حيّاك الله، بحوّلك للمسوّق يتأكد لك من التفاصيل ويرد عليك بأقرب وقت.

### nego-05

- did not call handoff_to_marketer

> **العميل:** البنك يعطيني تمويل كم بالضبط إذا راتبي ١٥ ألف؟
>
> **الوكيل:** هلا فيك، أنا المساعد الآلي لمكتب الأفق العقاري للوساطة العقارية. بخصوص تمويل البنك ما نقدر نفيدك فيه، والأفضل تسأل البنك نفسه. تحب نحولك على المسوّق يوضح لك تفاصيل ثانية؟

### view-03

- reply longer than 6 lines

> **العميل:** ابي أعاين شقة حطين مع المسوق
>
> _tools: search_listings, save_lead, get_viewing_slots_
>
> **الوكيل:** هلا فيك، أنا المساعد الآلي لمكتب الأفق العقاري.
> لقيت شقة في حطين (4 غرف، مساحة 205 م، بسعر 1,120,000 ريال، ترخيص: 7200411630، ينتهي في 2027-04-09).
> المواعيد المتاحة للمعاينة مع المسوق:
> - غداً 4:00 م
> - غداً 5:00 م
> - غداً 6:00 م
> أي موعد يناسبك؟

### book-03

- no reply contains any of [مسكون, مع المسو, المسوّق]

> **العميل:** ابي أشوف شقة حطين بنفسي بدون مسوق
>
> _tools: search_listings, save_lead_
>
> **الوكيل:** فيه شقة تمليك 4 غرف في حي حطين، المساحة 205 متر مربع، والسعر 1120000 ريال.
> رقم ترخيص الإعلان: 7200411630 وتاريخ انتهائه 2027-04-09.
> تبي أرسل لك موقعها وصورها؟

