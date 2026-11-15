import { api, ApiError } from "./api";

/**
 * What kind of problem a failed action is, which decides its color and what the person can do:
 * - invalid: something they typed. Red, under the field when we know which one.
 * - blocked: not possible right now (a limit, a conflict, no permission, already done). Amber.
 * - offline: the call never reached Hidaa. Amber, try again.
 * - system:  our side failed, or Meta refused. Red, with «أبلغ فريق حداء».
 */
export type ErrorKind = "invalid" | "blocked" | "offline" | "system";

/** A message the page itself raises (not from the server), with the kind it should look like. */
export class Notice extends Error {
  constructor(public kind: ErrorKind, message: string) {
    super(message);
  }
}

/** Refusals that are about what the person typed, whatever their HTTP status (a wrong password is a 401). */
const TYPED_WRONG = new Set(["bad_credentials"]);

export function kindOf(e: unknown): ErrorKind {
  if (e instanceof Notice) return e.kind;
  if (!(e instanceof ApiError)) return "system";
  if (e.status === 0) return "offline";
  // No error code means the server did not answer the way our API does: a bug or an outage, not the person's input.
  if (e.status >= 500 || e.code === "error") return "system";
  if (e.status === 400 || e.status === 422 || TYPED_WRONG.has(e.code)) return "invalid";
  return "blocked";
}

export function errorText(e: unknown, t: <V>(ar: V, en: V) => V): string {
  if (e instanceof ApiError || e instanceof Notice) return e.message;
  return t("حدث خطأ في الصفحة. أعد تحميلها وحاول مرة أخرى، وإن تكرر فأبلغنا.",
    "Something went wrong on this page. Reload it and try again; if it happens again, report it.");
}

/**
 * Sends «أبلغ فريق حداء» to the support inbox (support@hidaaco.com) and returns the report's reference.
 * Only what identifies the error goes: the page address without its query (reset links carry tokens), the call,
 * its status and request id, and what the person saw. Nothing they typed in the form.
 */
export async function reportError(e: unknown, note: string, email?: string): Promise<string> {
  const a = e instanceof ApiError ? e : null;
  const r = await api<{ reference?: string }>("/public/support/error-report", {
    method: "POST",
    json: {
      code: a?.code ?? (e instanceof Error ? e.name : "error"),
      status: a?.status ?? null,
      message: e instanceof Error ? e.message : String(e),
      requestId: a?.call.requestId,
      method: a?.call.method,
      path: a?.call.path,
      page: typeof location === "undefined" ? "" : location.origin + location.pathname,
      note: note.trim() || undefined,
      email: email?.trim() || undefined,
    },
  });
  return r.reference ?? "";
}
