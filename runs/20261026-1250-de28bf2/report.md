# Agent eval: gemini-3.5-flash-lite (thinking: low)

**Passed 41 of 49 scenarios (84%)**

| Metric | Value |
|---|---|
| Customer turns | 64 |
| Latency p50 / p95 | 1.9 s / 3.5 s |
| Tokens in / out | 353,029 / 9,232 |
| Est. cost per customer turn | 0.008 SAR (at $0.30 / $2.50 per 1M) |

| Category | Pass | Fail |
|---|---|---|
| greeting | 3 | 0 |
| qualification | 5 | 1 |
| search | 6 | 2 |
| no_match | 4 | 0 |
| expired | 2 | 0 |
| negotiation | 5 | 0 |
| human | 3 | 0 |
| viewing | 4 | 3 |
| listing_card | 1 | 1 |
| out_of_scope | 5 | 0 |
| dialect | 3 | 1 |

## Failures

### qual-04

- fallback (guard:unknown_license:7200000001) on: عندي تمويل من البنك وأبغى فيلا شمال الرياض حدود مليون وتسعمية
- did not call save_lead
- lead.payment = null, expected FINANCE

> **العميل:** عندي تمويل من البنك وأبغى فيلا شمال الرياض حدود مليون وتسعمية
>
> _tools: handoff_to_marketer_
>
> **الوكيل:** حيّاك الله، بحوّلك للمسوّق يتأكد لك من التفاصيل ويرد عليك بأقرب وقت.

### search-05

- no reply contains any of [7200418836]

> **العميل:** ابي فله ٥ غرف بالنرجس
>
> _tools: search_listings_
>
> **الوكيل:** أعتذر منك، ما فيه فلل حالياً بالمواصفات اللي طلبتها بحي النرجس. 
> تبي أسجل طلبك عشان يتواصل معك المسوّق أول ما يتوفر عقار مناسب؟

### search-07

- did not call search_listings
- no reply contains any of [375]

> **العميل:** فيلا النرجس اللي بمليون و٩٥٠ كم مساحتها؟
>
> **الوكيل:** هلا بك، معك المساعد الآلي لمكتب الأفق العقاري.
> أبشر، خليني أتأكد لك من مساحة الفيلا وتفاصيلها.

### view-02

- did not call get_viewing_slots

> **العميل:** عندكم أراضي بالعارض؟
>
> _tools: search_listings, save_lead_
>
> **الوكيل:** حالياً ما عندنا أراضي متوفرة في العارض، تبي نسجل طلبك عشان يتواصل معك المسوق أول ما يتوفر شي؟

> **العميل:** ممكن أروح أشوفها بنفسي بكرة؟
>
> _tools: search_listings_
>
> **الوكيل:** هلا بك، أنا المساعد الآلي لمكتب الأفق العقاري. 
> لقيت لك أرض سكنية زاوية في العارض، المساحة 625 متر مربع، السعر 1,350,000 ريال، رقم ترخيص الإعلان 7200388712 وينتهي في 2027-03-22.
> تبي أرسل لك موقع العقار وصوره؟

### view-03

- did not call get_viewing_slots

> **العميل:** ابي أعاين شقة حطين مع المسوق
>
> _tools: search_listings_
>
> **الوكيل:** أبشر، لقيت شقة تمليك 4 غرف في حطين، مساحتها 205 م، بسعر 1,120,000 ريال (رخيص الإعلان: 7200411630، ينتهي في 2027-04-09). العقار مسكون لذا تتوفر المعاينة مع المسوق.
> 
> تبي أحجز لك موعد معاينة مع المسوق؟
> *(ملاحظة: هذا العقار مسكون فلا تتوفر زيارة ذاتية).*

### view-04

- no reply contains any of [4:00, 5:00, 6:00]

> **العميل:** شقة القيروان للإيجار؟
>
> _tools: search_listings_
>
> **الوكيل:** لقيت لك شقة غرفتين في حي القيروان، مساحتها 120 متر مربع، للإيجار بسعر 48000 ريال سنوياً.
> رقم ترخيص الإعلان: 7200429955 (ينتهي في 2027-01-18).
> تبي تحجز موعد لمعاينتها؟

> **العميل:** تمام ابغى أشوفها مع المسوق، متى يناسبكم؟
>
> _tools: get_viewing_slots, save_lead_
>
> **الوكيل:** هلا بك، أنا المساعد الآلي لمكتب «مكتب الأفق العقاري».
> تبي تشوف عقار ثاني في حي القيروان أو أي حي ثاني؟

### card-02

- did not call send_listing_card

> **العميل:** عندكم أراضي بالعارض؟
>
> _tools: search_listings_
>
> **الوكيل:** ما توفرت أراضي حالياً في العارض. تبي نسجل طلبك عشان يتواصل معك المسوّق أول ما يتوفر شي؟

> **العميل:** وين موقعها بالضبط؟
>
> **الوكيل:** أنا المساعد الآلي لمكتب الأفق العقاري. عذراً، ما توفرت أراضي حالياً في العارض، وتبي نسجل طلبك عشان يتواصل معك المسوّق أول ما يتوفر شي؟

### dialect-04

- reply longer than 8 lines

> **العميل:** Hi, do you have villas for sale in Al Narjis?
>
> _tools: search_listings, save_lead_
>
> **الوكيل:** Hello! I am the automated assistant for Al Afaq Real Estate Office. Yes, we have villas for sale in Al Narjis:
> 
> 1. فيلا دوبلكس
> Area: 312 sqm, Price: 1,780,000 SAR
> License: 7200395120 (Expires: 2027-05-31)
> 
> 2. فيلا درج داخلي
> Area: 375 sqm, Price: 1,950,000 SAR
> License: 7200418836 (Expires: 2027-06-30)
> 
> Would you like to schedule a viewing for one of them?

