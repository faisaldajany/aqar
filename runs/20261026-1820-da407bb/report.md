# Agent eval: gemini-3.5-flash-lite (thinking: low)

**Passed 46 of 49 scenarios (94%)**

| Metric | Value |
|---|---|
| Customer turns | 64 |
| Latency p50 / p95 | 2.3 s / 3.9 s |
| Tokens in / out | 366,215 / 13,791 |
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
| viewing | 6 | 1 |
| listing_card | 2 | 0 |
| out_of_scope | 5 | 0 |
| dialect | 3 | 1 |

## Failures

### search-05

- reply longer than 8 lines

> **العميل:** ابي فله ٥ غرف بالنرجس
>
> _tools: search_listings, save_lead_
>
> **الوكيل:** هلا بك، معك المساعد الآلي لمكتب الأفق العقاري للوساطة العقارية (رخصة فال: 1100012345).
> 
> لقيت لك هذي الفيلا بالنرجس:
> - الوصف: فيلا درج داخلي، 5 غرف
> - المساحة: 375 متر مربع
> - السعر: 1,950,000 ريال
> - رقم ترخيص الإعلان: 7200418836 (ينتهي في 2027-06-30)
> 
> تبي تحجز معاينة لها؟

### view-03

- did not call get_viewing_slots

> **العميل:** ابي أعاين شقة حطين مع المسوق
>
> _tools: search_listings, save_lead_
>
> **الوكيل:** لقيت لك شقة تمليك في حي حطين:
> - شقة تمليك 4 غرف، مساحتها 205 م², السعر 1,120,000 ريال (للبيع).
> - رقم ترخيص الإعلان: 7200411630 (ينتهي في 2027-04-09).
> 
> وبما إن العقار مسكون، فالمعاينة تكون مع المسوّق. هل تحب أحجز لك موعد معاينة؟

### dialect-02

- fallback (guard:uncorrected) on: يا هلا، أبي فيلا دوبلكس وش عندكم
- did not call search_listings
- no reply contains any of [7200395120]

> **العميل:** يا هلا، أبي فيلا دوبلكس وش عندكم
>
> _tools: handoff_to_marketer_
>
> **الوكيل:** حيّاك الله، بحوّلك للمسوّق يتأكد لك من التفاصيل ويرد عليك بأقرب وقت.

