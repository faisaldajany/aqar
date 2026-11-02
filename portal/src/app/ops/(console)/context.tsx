"use client";

import { createContext, useContext } from "react";

export const OpsContext = createContext<{ mailConfigured: boolean }>({ mailConfigured: false });
export const useOps = () => useContext(OpsContext);
