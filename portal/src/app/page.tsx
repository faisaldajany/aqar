import type { Metadata } from "next";
import Link from "next/link";
import { Icon } from "@/components/Icon";
import { Logo, LogoMark } from "@/components/Logo";
import s from "./home.module.css";

export const metadata: Metadata = {
  title: "حداء — وكيل واتساب لمكاتب العقار",
  description: "كل رسالة ما تنرد عليها، عميل يكلّم مكتب ثاني. حداء يرد من رقم مكتبك خلال ثوانٍ، بعقاراتك أنت، ويحجز المعاينة.",
};

type Beat = { at: string; title: string; note?: string; bubble?: { who: "in" | "out" | "sys"; text: string }[] };

const NIGHT: Beat[] = [
  {
    at: "١١:٤٢ م", title: "عميل يسأل، والمكتب مسكّر",
    bubble: [{ who: "in", text: "السلام عليكم، عندكم فيلا بالنرجس حدود مليونين؟" }],
  },
  {
    at: "١١:٤٢ م", title: "حداء يرد من قائمتك، برقم الترخيص",
    bubble: [{ who: "out", text: "وعليكم السلام، معك المساعد الآلي لمكتب الأفق. عندنا فيلا درج داخلي 375 م² بـ1,950,000 ريال، ترخيص الإعلان 7200418836. أرسلت لك الموقع والصور." }],
  },
  {
    at: "١١:٤٤ م", title: "المعاينة محجوزة، وما أحد صحي",
    bubble: [
      { who: "in", text: "أبي أشوفها بكرة العصر" },
      { who: "out", text: "تم الحجز بكرة الساعة 5:00 م مع الأستاذ خالد، ويوصلك تذكير قبلها بساعة." },
    ],
  },
];

const DAY: Beat[] = [
  {
    at: "٨:٠٠ ص", title: "خالد يبدأ يومه بعميل جاهز",
    bubble: [{ who: "sys", text: "عميل جديد: أبو فهد، فيلا النرجس\nالميزانية 2 مليون كاش، يبي 5 غرف\nمعاينة اليوم 5:00 م" }],
  },
  { at: "٤:٠٠ م", title: "تذكير للعميل قبل الموعد بساعة", note: "ويسأله بعد الزيارة عن انطباعه، ويرجع لخالد إذا كان جاداً." },
  {
    at: "٩:٠٠ م", title: "تقرير يومك يوصل جوالك",
    bubble: [{ who: "sys", text: "تقرير مكتب الأفق اليوم\n14 استفسار، 5 جادين، 3 معاينات\n6 رسائل وصلت بعد الدوام" }],
  },
];

const TRUST = [
  { t: "يرد من قائمتك فقط", d: "كل عقار يعرضه موجود عندك، ومعه رقم ترخيص الإعلان وتاريخ انتهائه." },
  { t: "ما يفاوض", d: "أي سؤال عن آخر سعر أو خصم يحوّله لمسوّقك مع ملخص." },
  { t: "يسكت إذا رديت", d: "إذا رد مسوّقك من جواله، يتوقف حداء في هذه المحادثة فوراً." },
  { t: "ما يتظاهر إنه إنسان", d: "يعرّف نفسه مساعداً آلياً لمكتبك من أول رسالة." },
];

function Beats({ items }: { items: Beat[] }) {
  return (
    <ol className={s.beats}>
      {items.map((b, i) => (
        <li key={i}>
          <time className={s.at}>{b.at}</time>
          <div className={s.beatBody}>
            <h3>{b.title}</h3>
            {b.note && <p>{b.note}</p>}
            {b.bubble && (
              <div className={s.bubbles}>
                {b.bubble.map((m, j) => <div key={j} className={`${s.b} ${s[m.who]}`}>{m.text}</div>)}
              </div>
            )}
          </div>
        </li>
      ))}
    </ol>
  );
}

export default function Home() {
  return (
    <div className={s.page}>
      <header className={s.wrap}>
        <div className={s.top}>
          <Link href="/" className={s.logo} aria-label="حداء، الصفحة الرئيسية"><Logo size={30} /></Link>
          <nav className={s.nav} aria-label="روابط الموقع">
            <span className={s.navLinks}>
              <a href="#night">ليلة مع حداء</a>
              <a href="#pricing">الأسعار</a>
              <a href="#faq">أسئلة</a>
            </span>
            <Link href="/login">دخول</Link>
            <Link href="/signup" className={s.cta}>ابدأ التجربة</Link>
          </nav>
        </div>
      </header>

      <main>
        <section className={s.wrap}>
          <div className={s.hero}>
            <div>
              <h1>كل رسالة ما تنرد عليها، عميل يكلّم مكتب ثاني.</h1>
              <p className={s.lead}>
                العميل يراسل أكثر من مكتب في نفس الليلة، وأول رد مفيد هو اللي يكسب المعاينة. حداء يرد من رقم مكتبك
                خلال ثوانٍ، بعقاراتك أنت، ويحجز الموعد قبل ما يصحى منافسك.
              </p>
              <div className={s.actions}>
                <Link href="/signup" className={s.cta}>ابدأ تجربة ١٤ يوماً</Link>
                <a href="#night" className={s.ghost}>شوف ليلة مع حداء</a>
              </div>
              <p className={s.fine}>بدون بطاقة، وبدون رسوم تأسيس. على رقم واتساب مكتبك نفسه.</p>
            </div>
            <div className={s.lock} aria-label="شاشة جوال المكتب الساعة ١١:٤٢ مساءً">
              <div className={s.clock}>11:42</div>
              <div className={s.date}>الأحد</div>
              <div className={s.notif}>
                <span className={s.app}>واتساب، الآن</span>
                <b>أبو فهد</b>
                <span>عندكم فيلا بالنرجس حدود مليونين؟</span>
              </div>
              <div className={`${s.notif} ${s.notifReply}`}>
                <span className={s.app}><LogoMark size={14} inverted /> حداء، بعد ٣ ثوانٍ</span>
                <b>رددنا وعرضنا فيلا النرجس</b>
                <span>وحجز معاينة بكرة ٥:٠٠ م مع خالد</span>
              </div>
            </div>
          </div>
        </section>

        <section id="night" className={s.night}>
          <div className={s.wrap}>
            <h2>ليلة مع حداء</h2>
            <p className={s.subDark}>مثال توضيحي لمكتب افتراضي، من أول رسالة إلى تقرير اليوم التالي.</p>
            <Beats items={NIGHT} />
            <p className={s.statement}>أنت نمت. المكتب ما نام.</p>
          </div>
        </section>
        <section className={s.day}>
          <div className={s.wrap}>
            <Beats items={DAY} />
          </div>
        </section>

        <section className={`${s.wrap} ${s.section}`}>
          <h2>يرد مثل أفضل موظف عندك، بدون ما يتجاوز حدوده</h2>
          <div className={s.trust}>
            {TRUST.map((x) => (
              <div key={x.t}><h3><span className={s.tick}><Icon name="check" size={16} /></span>{x.t}</h3><p>{x.d}</p></div>
            ))}
          </div>
        </section>

        <section className={`${s.wrap} ${s.section}`} style={{ paddingTop: 0 }}>
          <h2>جاهز خلال نص ساعة</h2>
          <p className={s.sub}>بدون زيارة وبدون تغيير رقمك. يبقى تطبيق واتساب للأعمال على جوال المكتب كما هو.</p>
          <ol className={s.steps}>
            <li><h3>سجّل مكتبك</h3><p>اسم المكتب ورقم رخصة فال، ونتحقق منها خلال يوم عمل.</p></li>
            <li><h3>اربط رقمك</h3><p>من نافذة Meta الرسمية، على الرقم والتطبيق نفسيهما.</p></li>
            <li><h3>ارفع عقاراتك</h3><p>ملف Excel أو رابط إعلانك في عقار، مع رقم الترخيص لكل إعلان.</p></li>
            <li><h3>جرّبه بنفسك</h3><p>حادثه من البوابة كأنك عميل، قبل ما يرد على أي أحد.</p></li>
          </ol>
        </section>

        <section id="pricing" className={s.math}>
          <div className={`${s.wrap} ${s.section}`}>
            <h2>معاينة وحدة زيادة في الشهر تكفي</h2>
            <div className={s.equation}>
              <div><b>٤٧٬٥٠٠</b><span>ريال عمولة بيع فيلا بـ١٫٩ مليون (٢٫٥٪)</span></div>
              <div className={s.vs}>مقابل</div>
              <div><b>٤٩٩</b><span>ريال اشتراك حداء الشهري</span></div>
            </div>
            <p className={s.sub}>صفقة بيع وحدة تغطي اشتراك أكثر من ٧ سنوات. ومع ذلك، جرّب ١٤ يوماً قبل ما تدفع شي.</p>
            <div className={s.plans}>
              <div className={`${s.plan} ${s.planMain}`}>
                <h3>الأساسي</h3>
                <div className={s.price}>499 <small>ريال شهرياً</small></div>
                <ul>
                  <li>10,000 رد آلي شهرياً</li>
                  <li>حتى مسوّقَين</li>
                  <li>الحجز والتذكير والتقرير اليومي</li>
                  <li>الرد الإضافي 12 هللة، بلا إيقاف للخدمة</li>
                </ul>
                <Link href="/signup" className={s.cta}>ابدأ التجربة</Link>
              </div>
              <div className={s.plan}>
                <h3>النمو</h3>
                <div className={s.price}>1,000 <small>ريال شهرياً</small></div>
                <ul>
                  <li>20,000 رد آلي شهرياً</li>
                  <li>حتى 10 مسوّقين</li>
                  <li>كل ما في الأساسي</li>
                  <li>الرد الإضافي 10 هللات</li>
                </ul>
                <Link href="/signup" className={s.ghost}>ابدأ التجربة</Link>
              </div>
            </div>
            <p className={s.note}>بلا رسوم تأسيس. الدفع السنوي 4,990 و10,000 ريال (شهران مجاناً). الأسعار لا تشمل ضريبة القيمة المضافة 15%.</p>
          </div>
        </section>

        <section id="faq" className={`${s.wrap} ${s.section}`}>
          <h2>قبل ما تقرر</h2>
          <div className={s.faq}>
            <details><summary>يتغير رقمنا أو تطبيقنا؟</summary><p>لا. الرقم نفسه وتطبيق واتساب للأعمال نفسه، ومحادثاتكم السابقة تبقى.</p></details>
            <details><summary>عندنا موظف يرد</summary><p>حداء ما يلغي دوره. يغطي الليل ونهاية الأسبوع وأوقات الضغط، ويسلّمه العميل جاهزاً.</p></details>
            <details><summary>وش لو رد بمعلومة غلط؟</summary><p>يرد من قائمتك فقط، ويحوّل لك أي سؤال خارجها، وتشوف كل محادثة لحظة بلحظة.</p></details>
            <details><summary>عملاؤنا يرسلون صوتيات</summary><p>حالياً يطلب من العميل يكتب طلبه بلطف، وفهم الصوتيات قادم.</p></details>
            <details><summary>وإذا ما ناسبنا؟</summary><p>التجربة ١٤ يوماً بدون بطاقة. إذا ما اشتركت يتوقف حداء تلقائياً ولا يُخصم شي.</p></details>
          </div>
        </section>

        <section className={s.final}>
          <div className={`${s.wrap} ${s.finalIn}`}>
            <div>
              <h2><LogoMark size={34} inverted /> جرّبه الليلة على عقاراتك</h2>
              <p>١٤ يوماً مجاناً، بدون بطاقة.</p>
            </div>
            <Link href="/signup" className={s.cta}>سجّل مكتبك</Link>
          </div>
        </section>
      </main>

      <footer className={`${s.wrap} ${s.foot}`}>
        <span>حداء، الرياض</span>
        <span style={{ display: "flex", gap: 16 }}><Link href="/contact">تواصل معنا</Link><Link href="/login">دخول المكاتب</Link></span>
      </footer>
    </div>
  );
}
