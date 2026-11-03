# Agent eval: gemini-3.5-flash-lite (thinking: low)

**Passed 59 of 60 scenarios (98%)**

| Metric | Value |
|---|---|
| Customer turns | 75 |
| Latency p50 / p95 | 2.2 s / 3.5 s |
| Tokens in / out | 608,549 / 23,957 |
| Est. cost per customer turn | 0.012 SAR (at $0.30 / $2.50 per 1M) |

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
| viewing | 6 | 1 |
| listing_card | 2 | 0 |
| out_of_scope | 5 | 0 |
| dialect | 4 | 0 |

## Failures

### view-03

- reply longer than 8 lines

> **العميل:** ابي أعاين شقة حطين مع المسوق
>
> _tools: search_listings, save_lead, get_viewing_slots_
>
> **الوكيل:** أهلاً بك، معك المساعد الآلي لمكتب الأفق العقاري للوساطة العقارية (رخصة فال: 1100012345).
> 
> شقة التمليك بحطين (للبيع):
> 1. شقة تمليك 4 غرف بحطين، 205م، 1,120,000 ريال، ترخيص 7200411630 (ينتهي 2027-04-09).
> 
> المواعيد المتاحة للمعاينة مع المسوق:
> - غداً 4:00 م
> - غداً 5:00 م
> - غداً 6:00 م
> 
> أي موعد يناسبك؟

