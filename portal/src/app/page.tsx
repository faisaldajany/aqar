import type { Metadata } from "next";
import Link from "next/link";
import { Icon } from "@/components/Icon";
import { Logo, LogoMark } from "@/components/Logo";
import s from "./home.module.css";

export const metadata: Metadata = {
  title: "حداء — وكيل واتساب لمكاتب العقار",
  description: "وكيل آلي على رقم واتساب مكتبك: يرد خلال ثوانٍ، ويعرض عقاراتك المرخّصة، ويحجز المعاينة، ويحوّل العميل الجاد لمسوّقك.",
};

const CHAT: { who: "in" | "out"; text: string; at: string }[] = [
  { who: "in", text: "السلام عليكم، عندكم فيلا بالنرجس حدود مليونين؟", at: "11:42 م" },
  { who: "out", text: "وعليكم السلام، معك المساعد الآلي لمكتب الأفق. عندنا فيلا درج داخلي 375 م² بسعر 1,950,000 ريال، ترخيص الإعلان 7200418836. أرسل لك موقعها وصورها؟", at: "11:42 م" },
  { who: "in", text: "إيه، وأبي أعاينها بكرة العصر", at: "11:43 م" },
  { who: "out", text: "أبشر، عندي بكرة 4:00 أو 5:00 م مع الأستاذ خالد. أي وقت يناسبك؟", at: "11:43 م" },
  { who: "in", text: "خمس", at: "11:44 م" },
  { who: "out", text: "تم الحجز بكرة الساعة 5:00 م، ويوصلك تذكير قبلها بساعة.", at: "11:44 م" },
];

const DOES = [
  "يرد خلال ثوانٍ في أي ساعة، باللهجة السعودية، ويعرّف نفسه مساعداً آلياً للمكتب.",
  "يسأل عن الغرض والحي والميزانية وطريقة الدفع، ويسجّلها في بطاقة العميل.",
  "يعرض عقارين أو ثلاثة من قائمتك فقط، مع رقم ترخيص الإعلان وتاريخ انتهائه.",
  "يرسل موقع العقار وصوره، ويحجز المعاينة ضمن ساعاتك، ويذكّر العميل قبلها.",
  "يحوّل العميل الجاد لمسوّق الحي مع ملخص من ثلاثة أسطر.",
];
const DOESNT = [
  "لا يفاوض على السعر ولا يعد بخصم.",
  "لا يذكر عقاراً غير موجود في قائمتك.",
  "لا يعرض إعلاناً انتهى ترخيصه.",
  "لا يرسل رسائل جماعية ولا يطلب بيانات حساسة.",
  "يسكت في المحادثة متى ما رد مسوّقك بنفسه.",
];

export default function Home() {
  return (
    <div className={s.page}>
      <header className={s.wrap}>
        <div className={s.top}>
          <Link href="/" className={s.logo} aria-label="حداء، الصفحة الرئيسية"><Logo size={30} /></Link>
          <nav className={s.nav} aria-label="روابط الموقع">
            <span className={s.navLinks}>
              <a href="#how">كيف يعمل</a>
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
              <h1>عميلك يكتب الساعة ١١ بالليل، وحداء يرد عليه في ثوانٍ</h1>
              <p className={s.lead}>
                وكيل آلي على رقم واتساب مكتبك نفسه. يفهم اللهجة، ويعرض عقاراتك بأرقام تراخيصها، ويحجز المعاينة،
                ويحوّل العميل الجاد لمسوّقك مع ملخص جاهز.
              </p>
              <div className={s.actions}>
                <Link href="/signup" className={s.cta}>ابدأ تجربة ١٤ يوماً</Link>
                <a href="#how" className={s.ghost}>كيف يعمل</a>
              </div>
              <p className={s.fine}>بدون بطاقة، وبدون رسوم تأسيس. تلغي متى شئت.</p>
            </div>
            <figure className={s.chat} aria-label="مثال محادثة بين عميل ووكيل المكتب في الليل">
              <div className={s.chatHead}><b>مكتب الأفق العقاري</b><span>واتساب</span></div>
              <div className={s.msgs}>
                {CHAT.map((m, i) => (
                  <div key={i} className={`${s.msg} ${m.who === "in" ? s.in : s.out}`}>
                    {m.text}<time>{m.at}</time>
                  </div>
                ))}
                <div className={`${s.msg} ${s.in} ${s.typingIn}`} aria-hidden="true"><i /><i /><i /></div>
              </div>
              <figcaption className={s.tag}>مثال توضيحي لمكتب افتراضي</figcaption>
            </figure>
          </div>
        </section>

        <section id="how" className={`${s.wrap} ${s.section}`}>
          <h2>شغّله بنفسك في نصف ساعة</h2>
          <p className={s.sub}>بدون زيارة وبدون تغيير رقمك. يبقى تطبيق واتساب للأعمال على جوال المكتب كما هو.</p>
          <ol className={s.steps}>
            <li><h3>سجّل مكتبك</h3><p>اسم المكتب ورقم رخصة فال، ونتحقق منها خلال يوم عمل.</p></li>
            <li><h3>اربط رقم واتساب</h3><p>من نافذة Meta الرسمية، على الرقم والتطبيق نفسيهما.</p></li>
            <li><h3>ارفع عقاراتك</h3><p>ملف Excel أو رابط إعلانك في عقار، مع رقم الترخيص لكل إعلان.</p></li>
            <li><h3>جرّبه ثم شغّله</h3><p>حادث وكيلك من البوابة كأنك عميل، وحين يعجبك يبدأ الرد على عملائك.</p></li>
          </ol>
        </section>

        <section className={`${s.wrap} ${s.section}`} style={{ paddingTop: 0 }}>
          <h2>يعمل مثل موظف استقبال يعرف قائمتك</h2>
          <div className={s.split}>
            <div>
              <h3>ما يفعله</h3>
              <ul className={s.list}>{DOES.map((t) => <li key={t}><span className={s.yes}><Icon name="check" /></span>{t}</li>)}</ul>
            </div>
            <div>
              <h3>وما لا يفعله</h3>
              <ul className={s.list}>{DOESNT.map((t) => <li key={t}><span className={s.no}>—</span>{t}</li>)}</ul>
            </div>
          </div>
        </section>

        <section className={s.office}>
          <div className={`${s.wrap} ${s.section}`}>
            <h2>وأنت ترى كل شيء</h2>
            <div className={s.officeGrid}>
              <div><h3>كل المحادثات أمامك</h3><p>تقرأ ما يكتبه الوكيل لحظة بلحظة، وتتولى أي محادثة بضغطة فيسكت الوكيل فيها.</p></div>
              <div><h3>تقرير يومي على جوالك</h3><p>كم استفساراً وصل، ومن الجاد منهم، وكم موعداً حُجز، في رسالة واحدة كل مساء.</p></div>
              <div><h3>الزيارة الذاتية بموافقتك</h3><p>يطلب العميل زيارة بنفسه، فتوافق وترسل له رمز القفل أو بيانات الحارس من البوابة.</p></div>
            </div>
          </div>
        </section>

        <section id="pricing" className={`${s.wrap} ${s.section}`}>
          <h2>سعر واضح، بلا رسوم تأسيس</h2>
          <p className={s.sub}>صفقة إضافية واحدة في السنة تغطي الاشتراك أضعافاً. جرّب ١٤ يوماً أولاً، ثم اختر.</p>
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
          <p className={s.note}>الدفع السنوي: 4,990 و10,000 ريال (شهران مجاناً). الأسعار لا تشمل ضريبة القيمة المضافة 15%.</p>
        </section>

        <section id="faq" className={`${s.wrap} ${s.section}`} style={{ paddingTop: 0 }}>
          <h2>قبل أن تقرر</h2>
          <div className={s.faq}>
            <details><summary>هل سيتغير رقمنا أو تطبيقنا؟</summary><p>لا. الرقم نفسه وتطبيق واتساب للأعمال نفسه، ومحادثاتكم السابقة تبقى كما هي.</p></details>
            <details><summary>عندنا موظف يرد على العملاء</summary><p>الوكيل لا يلغي دوره. يغطي الليل ونهاية الأسبوع وأوقات الضغط، ويسلّم الموظف العميل جاهزاً مع ملخص.</p></details>
            <details><summary>نخاف يرد بمعلومة خاطئة</summary><p>يرد من قائمة عقاراتك فقط، ويحوّل لك أي سؤال خارجها وأي تفاوض، وترى كل محادثة.</p></details>
            <details><summary>عملاؤنا يرسلون رسائل صوتية</summary><p>حالياً يطلب الوكيل من العميل كتابة طلبه بلطف، وفهم الرسائل الصوتية قادم.</p></details>
            <details><summary>ماذا لو ما ناسبنا؟</summary><p>التجربة ١٤ يوماً بدون بطاقة. إن لم تشترك يتوقف الوكيل تلقائياً ولا يُخصم شيء.</p></details>
          </div>
        </section>

        <section className={s.final}>
          <div className={`${s.wrap} ${s.finalIn}`}>
            <h2><LogoMark size={34} inverted /> جرّب حداء على قائمة عقاراتك</h2>
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
