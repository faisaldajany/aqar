"use client";

import { useEffect } from "react";

/** Sale/rent switch in the hero chat and the FAQ accordion, for the static home page markup. */
export function HomeInteractions() {
  useEffect(() => {
    const root = document.getElementById("home");
    if (!root) return;
    const cleanups: (() => void)[] = [];

    // FAQ: first answer open, the rest closed; one open at a time.
    const buttons = Array.from(root.querySelectorAll<HTMLButtonElement>("button[data-faq]"));
    const setOpen = (open: number) => {
      buttons.forEach((b) => {
        const i = Number(b.dataset.faq);
        const isOpen = i === open;
        b.setAttribute("aria-expanded", String(isOpen));
        const chev = b.querySelector("svg");
        if (chev) chev.style.transform = isOpen ? "rotate(180deg)" : "none";
        root.querySelectorAll<HTMLElement>(`[data-faq-answer="${i}"]`).forEach((a) => { a.hidden = !isOpen; });
      });
    };
    let current = 0;
    setOpen(current);
    buttons.forEach((b) => {
      const onClick = () => { const i = Number(b.dataset.faq); current = current === i ? -1 : i; setOpen(current); };
      b.addEventListener("click", onClick);
      cleanups.push(() => b.removeEventListener("click", onClick));
    });

    // Sale / rent example in the hero phone.
    const sale = root.querySelector<HTMLButtonElement>('button[data-mode="sale"]');
    const rent = root.querySelector<HTMLButtonElement>('button[data-mode="rent"]');
    if (sale && rent) {
      const on = sale.getAttribute("style") ?? "";
      const off = rent.getAttribute("style") ?? "";
      const pick = (mode: "sale" | "rent") => {
        root.querySelectorAll<HTMLElement>("[data-sale]").forEach((el) => { el.textContent = el.dataset[mode] ?? el.textContent; });
        sale.setAttribute("style", mode === "sale" ? on : off);
        rent.setAttribute("style", mode === "rent" ? on : off);
        sale.setAttribute("aria-pressed", String(mode === "sale"));
        rent.setAttribute("aria-pressed", String(mode === "rent"));
      };
      const s = () => pick("sale"), r = () => pick("rent");
      sale.addEventListener("click", s);
      rent.addEventListener("click", r);
      cleanups.push(() => { sale.removeEventListener("click", s); rent.removeEventListener("click", r); });
      pick("sale");
    }
    return () => cleanups.forEach((c) => c());
  }, []);
  return null;
}
