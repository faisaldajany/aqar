"use client";

import { createContext, useContext } from "react";
import type { Me } from "@/lib/api";

export const SessionContext = createContext<{ me: Me; refresh: () => Promise<void> } | null>(null);

export function useSession() {
  const s = useContext(SessionContext);
  if (!s) throw new Error("useSession outside the app shell");
  return s;
}
