import type { Metadata } from "next";
import { CompanyLine } from "@/components/CompanyLine";
import { LangSwitch } from "@/components/LangSwitch";
import { HOME_HTML } from "./home-html";
import { HomeInteractions } from "./HomeInteractions";
import { HOME_STYLE } from "./home-style";

export const metadata: Metadata = {
  title: "حداء — وكيل لمكاتب العقار",
  description: "كل رسالة ما ترد عليها، عميل يكلم مكتب ثاني. حداء يرد على عملائك في واتساب 24 ساعة، من عقاراتك أنت، يحجز المعاينة ويسلمك العميل جاهز.",
  alternates: { languages: { en: "/en" } },
};

export default function Home() {
  return (
    <>
      <style>{HOME_STYLE}</style>
      <LangSwitch to="/en" lang="en" />
      <div id="home" dangerouslySetInnerHTML={{ __html: HOME_HTML }} />
      <div className="company-bar"><CompanyLine /></div>
      <HomeInteractions />
    </>
  );
}
