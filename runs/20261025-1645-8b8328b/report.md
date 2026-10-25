# Agent eval: gemini-3.5-flash-lite (thinking: low)

**Passed 43 of 44 scenarios (98%)**

| Metric | Value |
|---|---|
| Customer turns | 57 |
| Latency p50 / p95 | 1.6 s / 31.6 s |
| Tokens in / out | 183,470 / 7,823 |
| Est. cost per customer turn | 0.005 SAR (at $0.30 / $2.50 per 1M) |

| Category | Pass | Fail |
|---|---|---|
| greeting | 3 | 0 |
| qualification | 6 | 0 |
| search | 8 | 0 |
| no_match | 4 | 0 |
| expired | 2 | 0 |
| negotiation | 5 | 0 |
| human | 3 | 0 |
| viewing | 3 | 1 |
| out_of_scope | 5 | 0 |
| dialect | 4 | 0 |

## Failures

### view-03

- did not call request_viewing

> **العميل:** ابي أعاين شقة حطين يوم الخميس
>
> _tools: search_listings, save_lead_
>
> **الوكيل:** أهلا فيك، أنا المساعد الآلي لمكتب الأفق العقاري. للأسف ما عندنا شقق متوفرة في حطين حالياً، هل أقدر أسجل طلبك عشان يتواصل معك المسوق أول ما يتوفر عقار؟

