# Agent eval: gemini-3.5-flash-lite (thinking: low)

**Passed 46 of 49 scenarios (94%)**

| Metric | Value |
|---|---|
| Customer turns | 64 |
| Latency p50 / p95 | 2.3 s / 3.7 s |
| Tokens in / out | 363,805 / 9,398 |
| Est. cost per customer turn | 0.008 SAR (at $0.30 / $2.50 per 1M) |

| Category | Pass | Fail |
|---|---|---|
| greeting | 3 | 0 |
| qualification | 6 | 0 |
| search | 7 | 1 |
| no_match | 4 | 0 |
| expired | 2 | 0 |
| negotiation | 5 | 0 |
| human | 3 | 0 |
| viewing | 5 | 2 |
| listing_card | 2 | 0 |
| out_of_scope | 5 | 0 |
| dialect | 4 | 0 |

## Failures

### search-05

- no reply contains any of [7200418836]

> **العميل:** ابي فله ٥ غرف بالنرجس
>
> _tools: search_listings, save_lead_
>
> **الوكيل:** ما توجد فلل متوفرة حالياً 5 غرف في حي النرجس. تبي نسجل طلبك عشان يتواصل معك المسوّق أول ما يتوفر عقار مناسب؟

### view-03

- did not call get_viewing_slots

> **العميل:** ابي أعاين شقة حطين مع المسوق
>
> _tools: search_listings_
>
> **الوكيل:** لقيت لك هذي الشقة في حطين:
> شقة تمليك 4 غرف، المساحة 205 م٢، السعر 1,120,000 ريال (للبيع).
> رقم ترخيص الإعلان: 7200411630، ينتهي في: 2027-04-09.
> 
> تبي تشوف مواعيد المعاينة مع المسوق لها؟

### view-04

- no reply contains any of [4:00, 5:00, 6:00]

> **العميل:** شقة القيروان للإيجار؟
>
> _tools: search_listings, save_lead_
>
> **الوكيل:** هلا بك، معك المساعد الآلي لمكتب الأفق العقاري.
> شقة للإيجار في القيروان: المساحة 120 م2، غرفتين، السعر 48000 ريال سنوياً.
> رقم ترخيص الإعلان: 7200429955 (ينتهي في 2027-01-18).
> تبي تشوف صورها وموقعها؟

> **العميل:** تمام ابغى أشوفها مع المسوق، متى يناسبكم؟
>
> _tools: get_viewing_slots_
>
> **الوكيل:** أبشر، حياك الله. للمعاينة مع المسوّق، وش اليوم المناسب لك عشان أشوف المواعيد المتاحة؟

