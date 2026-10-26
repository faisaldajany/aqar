import type { Metadata } from "next";
import { HOME_HTML } from "./home-html";
import { HomeInteractions } from "./HomeInteractions";

export const metadata: Metadata = {
  title: "حداء — وكيل واتساب لمكاتب العقار",
  description: "كل رسالة ما ترد عليها، عميل يكلم مكتب ثاني. حداء يرد على عملائك في واتساب 24 ساعة، من عقاراتك أنت، يحجز المعاينة ويسلمك العميل جاهز.",
};

const MOTION = `
#home a{color:#A3192D}#home a:hover{color:#7A1424}
@keyframes hFadeUp{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}
@keyframes hBounce{0%,80%,100%{transform:translateY(0);opacity:.45}40%{transform:translateY(-4px);opacity:1}}
@keyframes hTwinkle{0%,100%{opacity:.2}50%{opacity:.95}}
@keyframes hFloat{0%,100%{transform:translateY(0)}50%{transform:translateY(-7px)}}
@media (prefers-reduced-motion: reduce){#home *{animation:none !important}}
#home [hidden]{display:none !important}
#home h1,#home h2,#home h3,#home h4{font-family:inherit}
#home{font-size:16px;line-height:1.7}
#home button:focus-visible,#home a:focus-visible{outline:3px solid #F0A8B3;outline-offset:2px}
`;

export default function Home() {
  return (
    <>
      <style>{MOTION}</style>
      <div id="home" dangerouslySetInnerHTML={{ __html: HOME_HTML }} />
      <HomeInteractions />
    </>
  );
}
