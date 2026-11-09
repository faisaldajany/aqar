import type { Metadata } from "next";
import { CompanyLine } from "@/components/CompanyLine";
import { LangSwitch } from "@/components/LangSwitch";
import { HomeInteractions } from "../HomeInteractions";
import { HOME_HTML_EN } from "../home-html-en";
import { HOME_STYLE } from "../home-style";

export const metadata: Metadata = {
  title: "Hidaa — an AI agent for real-estate offices",
  description: "Every message you don't answer is a customer talking to another office. Hidaa answers your customers on WhatsApp around the clock, from your own listings, books the viewing and hands you a ready customer.",
  alternates: { languages: { ar: "/" } },
};

/** The home page in English (the same design, left to right). The office app itself is in Arabic. */
export default function HomeEn() {
  return (
    <>
      <style>{HOME_STYLE}</style>
      <LangSwitch to="/" lang="ar" />
      <div id="home" lang="en" dir="ltr" dangerouslySetInnerHTML={{ __html: HOME_HTML_EN }} />
      <div className="company-bar" dir="ltr"><CompanyLine lang="en" /></div>
      <HomeInteractions lang="en" />
    </>
  );
}
