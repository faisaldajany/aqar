# Agent eval: gemini-3.5-flash-lite (thinking: low)

**Passed 46 of 49 scenarios (94%)**

| Metric | Value |
|---|---|
| Customer turns | 64 |
| Latency p50 / p95 | 1.6 s / 50.7 s |
| Tokens in / out | 312,347 / 9,519 |
| Est. cost per customer turn | 0.007 SAR (at $0.30 / $2.50 per 1M) |

| Category | Pass | Fail |
|---|---|---|
| greeting | 3 | 0 |
| qualification | 6 | 0 |
| search | 7 | 1 |
| no_match | 4 | 0 |
| expired | 2 | 0 |
| negotiation | 5 | 0 |
| human | 2 | 1 |
| viewing | 7 | 0 |
| listing_card | 1 | 1 |
| out_of_scope | 5 | 0 |
| dialect | 4 | 0 |

## Failures

### search-07

- no reply contains any of [375]

> **العميل:** فيلا النرجس اللي بمليون و٩٥٠ كم مساحتها؟
>
> _tools: search_listings_
>
> **الوكيل:** هلا بك، أنا المساعد الآلي لمكتب الأفق العقاري. مساحة الفيلا ٣٧٥ متر مربع، وسعرها مليون و٩٥٠ ألف ريال. رقم ترخيص الإعلان ٧٢٠٠٤١٨٨٣٦ وتاريخ انتهائه في ٣٠/٠٦/٢٠٢٧. تبي تشوف صورها أو موقعها؟

### human-03

- did not call handoff_to_marketer

> **العميل:** رحت المعاينة أمس وما أحد فتح لي الباب، وش هالتعامل
>
> **الوكيل:** هلا فيك، أنا المساعد الآلي لمكتب الأفق العقاري. أعتذر منك جداً على اللي صار وحقك علينا، وش رايك ألغي لك الموعد القديم وأحجز لك موعد ثاني يرضيك؟

### card-02

- fallback (guard:empty) on: عندكم أراضي بالعارض؟
- did not call send_listing_card

> **العميل:** عندكم أراضي بالعارض؟
>
> _tools: search_listings, save_lead, handoff_to_marketer_
>
> **الوكيل:** حيّاك الله، بحوّلك للمسوّق يتأكد لك من التفاصيل ويرد عليك بأقرب وقت.

> **العميل:** وين موقعها بالضبط؟
>
> _tools: search_listings, save_lead_
>
> **الوكيل:** هلا بك، متوفر أرض سكنية زاوية في العارض مساحتها 625 متر بسعر 1,350,000 ريال، رقم ترخيص الإعلان 7200388712 وينتهي في 2027-03-22.
> تبي أرسل لك موقعها وصورها؟

