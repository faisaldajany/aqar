# Agent eval: gemini-3.5-flash-lite (thinking: low)

**Passed 58 of 60 scenarios (97%)**

| Metric | Value |
|---|---|
| Customer turns | 75 |
| Latency p50 / p95 | 1.9 s / 3.2 s |
| Tokens in / out | 591,877 / 15,184 |
| Est. cost per customer turn | 0.011 SAR (at $0.30 / $2.50 per 1M) |

| Category | Pass | Fail |
|---|---|---|
| greeting | 3 | 0 |
| qualification | 6 | 0 |
| search | 8 | 0 |
| no_match | 4 | 0 |
| location | 6 | 0 |
| budget | 5 | 0 |
| expired | 2 | 0 |
| negotiation | 5 | 0 |
| human | 3 | 0 |
| viewing | 5 | 2 |
| listing_card | 2 | 0 |
| out_of_scope | 5 | 0 |
| dialect | 4 | 0 |

## Failures

### view-03

- reply longer than 8 lines

> **العميل:** ابي أعاين شقة حطين مع المسوق
>
> _tools: search_listings, get_viewing_slots_
>
> **الوكيل:** لقيت لك هذي الشقة بحطين للبيع:
> 1. شقة تمليك 4 غرف بحطين، 205م، 1,120,000 ريال، ترخيص 7200411630 (ينتهي 2027-04-09).
> 
> وهذي مواعيد المعاينة المتاحة مع المسوق:
> - غداً 4:00 م
> - غداً 5:00 م
> - غداً 6:00 م
> 
> أي موعد يزبك عشان نحجزه لك؟

### view-04

- no reply contains any of [4:00, 5:00, 6:00]

> **العميل:** شقة القيروان للإيجار؟
>
> _tools: search_listings, save_lead_
>
> **الوكيل:** لقيت لك هذه العقارات المطابقة لطلبك:
> 
> 1. شقة غرفتين بالقيروان، 120م، 48,000 ريال سنوي، ترخيص 7200429955 (ينتهي 2027-01-18)
> 
> تبي تحجز معاينة للعقار؟

> **العميل:** تمام ابغى أشوفها مع المسوق، متى يناسبكم؟
>
> _tools: get_viewing_slots, search_listings_
>
> **الوكيل:** أبشر، لقيت لك شقة للإيجار بالقيروان:
> 
> 1. شقة غرفتين بالقيروان، 120م، 48,000 ريال سنوي، ترخيص 7200429955 (ينتهي 2027-01-18)
> 
> تبي أحجز لك معاينة مع المسوّق؟

