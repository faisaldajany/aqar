# Agent eval: gemini-3.5-flash-lite (thinking: low)

**Passed 56 of 60 scenarios (93%)**

| Metric | Value |
|---|---|
| Customer turns | 75 |
| Latency p50 / p95 | 2.5 s / 4.7 s |
| Tokens in / out | 621,201 / 21,615 |
| Est. cost per customer turn | 0.012 SAR (at $0.30 / $2.50 per 1M) |

| Category | Pass | Fail |
|---|---|---|
| greeting | 3 | 0 |
| qualification | 5 | 1 |
| search | 8 | 0 |
| no_match | 4 | 0 |
| location | 6 | 0 |
| budget | 5 | 0 |
| expired | 1 | 1 |
| negotiation | 5 | 0 |
| human | 3 | 0 |
| viewing | 6 | 1 |
| listing_card | 2 | 0 |
| out_of_scope | 4 | 1 |
| dialect | 4 | 0 |

## Failures

### qual-01

- no reply contains any of [حي, شراء, إيجار, ايجار, ميزانية]

> **العميل:** ابغى بيت
>
> _tools: search_listings, save_lead_
>
> **الوكيل:** هلا بك، أنا المساعد الآلي لمكتب الأفق العقاري (رخصة فال: 1100012345). ما عندنا بيوت متوفرة حالياً، هل تحب نسجل طلبك عشان يتواصل معك المسوّق أول ما يتوفر عقار؟

### expired-01

- did not call search_listings

> **العميل:** عندكم دور للإيجار بالياسمين؟
>
> **الوكيل:** عذراً، حصل خطأ تقني بسيط. ثواني وأعطيك النتيجة.

### view-03

- reply longer than 8 lines

> **العميل:** ابي أعاين شقة حطين مع المسوق
>
> _tools: search_listings, get_viewing_slots_
>
> **الوكيل:** أحفظ معلومة العميل (الغرض بيع، النوع شقة، الحي حطين، الموعد المطلوب معاينة).
> ثم أعرض المواعيد المتاحة للعميل (غداً 4:00 م، 5:00 م، 6:00 م).
> تذكر:
> - لا إيموجي ولا تنسيق معقد.
> - أقصى 4 أسطر وسؤال واحد.أبشر، هذي المواعيد المتاحة لمعاينة الشقة مع المسوّق:
> 1. غداً 4:00 م
> 2. غداً 5:00 م
> 3. غداً 6:00 م
> 
> أي موعد يناسبك؟

### scope-04

- fallback (guard:unknown_license:7200395120) on: ابي فيلا بالنرجس، تبي رقم هويتي؟

> **العميل:** ابي فيلا بالنرجس، تبي رقم هويتي؟
>
> _tools: handoff_to_marketer_
>
> **الوكيل:** حيّاك الله، بحوّلك للمسوّق يتأكد لك من التفاصيل ويرد عليك بأقرب وقت.

