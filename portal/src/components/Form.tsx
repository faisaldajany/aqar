"use client";

import {
  Children, cloneElement, createContext, FormEvent, isValidElement, ReactElement, ReactNode, useContext, useEffect, useId, useState,
} from "react";
import { ApiError } from "@/lib/api";
import { errorText, kindOf, reportError, type ErrorKind } from "@/lib/errors";
import { SessionContext } from "@/app/(app)/session";
import { useLang } from "./LangProvider";
import { Icon } from "./Icon";

type T = <V>(ar: V, en: V) => V;
type Errors = Record<string, string>;
type Control = HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;
type FieldFor = Record<string, string> | ((code: string) => string | undefined);

/** Where the form-level message goes: an explicit <FormNote/>, else under <Submit>, else at the end of the form. */
type Slot = 0 | 1 | 2;
const FormContext = createContext<{ errors: Errors; busy: boolean; failure: unknown; slot: Slot; claim: (s: Slot) => void }>(
  { errors: {}, busy: false, failure: null, slot: 0, claim: () => undefined });

/**
 * A form whose problems show where they are:
 * - the browser's own checks (required, email, min/max length, date range…) run on submit and each message sits
 *   under its field instead of the browser's bubble;
 * - `validate` adds the page's own checks;
 * - a server answer that names a field (ApiError.fields, or a code listed in `fieldFor`) goes under that field;
 * - anything else shows below the form in the color of its kind, with «أبلغ فريق حداء» when it is our fault.
 * Fields are wrapped in <Field>; the submit button can be <Submit>, which follows the busy state.
 */
export function Form({ onSubmit, validate, fieldFor, resetOnSuccess, className, children, id, "aria-label": ariaLabel }: {
  /** Throw (or let the API call throw) to show the error. */
  onSubmit: (data: FormData, form: HTMLFormElement) => Promise<unknown> | unknown;
  validate?: (data: FormData) => Errors;
  /** Server error codes that belong to one field, e.g. { bad_phone: "phone" }, or a function of the code. */
  fieldFor?: FieldFor;
  resetOnSuccess?: boolean;
  className?: string;
  children: ReactNode;
  id?: string;
  "aria-label"?: string;
}) {
  const { t } = useLang();
  const [errors, setErrors] = useState<Errors>({});
  const [failure, setFailure] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [slot, setSlot] = useState<Slot>(0);
  const claim = (s: Slot) => setSlot((prev) => (s > prev ? s : prev));

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    const data = new FormData(form);
    const found: Errors = { ...(validate?.(data) ?? {}), ...browserChecks(form, t) };
    setFailure(null);
    if (Object.keys(found).length) {
      setErrors(found);
      focusFirst(form, found);
      return;
    }
    setErrors({});
    setBusy(true);
    try {
      await onSubmit(data, form);
      if (resetOnSuccess) form.reset();
    } catch (err) {
      const mine = serverFieldErrors(err, form, fieldFor);
      if (mine) {
        setErrors(mine);
        focusFirst(form, mine);
      } else {
        setFailure(err);
      }
    } finally {
      setBusy(false);
    }
  }

  /** Typing in a field clears its message; the form-level one goes once they change anything. */
  function edited(target: EventTarget) {
    const name = (target as Control).name;
    if (!name) return;
    setErrors((prev) => {
      if (!(name in prev)) return prev;
      const next = { ...prev };
      delete next[name];
      return next;
    });
  }

  return (
    <FormContext.Provider value={{ errors, busy, failure, slot, claim }}>
      <form noValidate onSubmit={submit} onInput={(e) => edited(e.target)} onChange={(e) => edited(e.target)}
        className={className} id={id} aria-label={ariaLabel}>
        {children}
        {failure != null && slot === 0 && <ErrorNote error={failure} />}
      </form>
    </FormContext.Provider>
  );
}

/**
 * Label, the input, and under it either its hint or its error. The input gets its id, aria-invalid and
 * aria-describedby, so screen readers read the message with the field.
 */
export function Field({ name, label, hint, optional, className, children }: {
  name: string;
  label: ReactNode;
  hint?: ReactNode;
  optional?: boolean;
  className?: string;
  children: ReactElement;
}) {
  const { t } = useLang();
  const { errors } = useContext(FormContext);
  const auto = useId();
  const child = Children.only(children) as ReactElement<Record<string, unknown>>;
  const inputId = (child.props.id as string | undefined) ?? `f${auto.replace(/[^a-zA-Z0-9_-]/g, "")}`;
  const error = errors[name];
  const hintId = `${inputId}-hint`;
  const errorId = `${inputId}-err`;
  const described = [error ? errorId : hint ? hintId : null, child.props["aria-describedby"] as string | undefined]
    .filter(Boolean).join(" ") || undefined;
  const input = isValidElement(child)
    ? cloneElement(child, { id: inputId, name: (child.props.name as string | undefined) ?? name, "aria-invalid": error ? true : undefined, "aria-describedby": described })
    : child;
  return (
    <div className={`f${error ? " has-err" : ""}${className ? ` ${className}` : ""}`}>
      <label htmlFor={inputId}>
        {label}
        {optional && <span className="opt"> ({t("اختياري", "optional")})</span>}
      </label>
      {input}
      {error ? (
        <p id={errorId} className="ferr" role="alert"><Icon name="alert" size={15} /><span>{error}</span></p>
      ) : hint ? (
        <p id={hintId} className="fhint">{hint}</p>
      ) : null}
    </div>
  );
}

/**
 * The form's submit button; disabled with its busy label while the form is sending. The form's own error shows
 * right under it, unless the page placed a <FormNote/>.
 */
export function Submit({ children, busyLabel, className = "btn primary" }: { children: ReactNode; busyLabel?: ReactNode; className?: string }) {
  const { t } = useLang();
  const { busy, failure, slot, claim } = useContext(FormContext);
  useEffect(() => claim(1), []);
  return (
    <>
      <button type="submit" className={className} disabled={busy}>{busy ? busyLabel ?? t("جارٍ الحفظ…", "Saving…") : children}</button>
      {failure != null && slot === 1 && <ErrorNote error={failure} />}
    </>
  );
}

/** Where the form's own error shows, when under the submit button is not the right place (e.g. a row of buttons). */
export function FormNote() {
  const { failure, slot, claim } = useContext(FormContext);
  useEffect(() => claim(2), []);
  return failure != null && slot === 2 ? <ErrorNote error={failure} /> : null;
}

const NOTE_CLASS: Record<ErrorKind, string> = { invalid: "note-invalid", blocked: "note-warn", offline: "note-warn", system: "note-bad" };

/** An error that is not about one field: colored by its kind, with «أبلغ فريق حداء» when it is our fault. */
export function ErrorNote({ error }: { error: unknown }) {
  const { t } = useLang();
  const kind = kindOf(error);
  return (
    <div className={`note ${NOTE_CLASS[kind]}`} role="alert">
      <Icon name={kind === "blocked" || kind === "offline" ? "warn" : "alert"} size={18} />
      <div className="note-body">
        <p>{errorText(error, t)}</p>
        {kind === "system" && <ReportError error={error} />}
      </div>
    </div>
  );
}

/** A green confirmation in the same place an error would be. */
export function DoneNote({ children }: { children: ReactNode }) {
  return (
    <div className="note note-ok" role="status">
      <Icon name="check" size={18} />
      <div className="note-body"><p>{children}</p></div>
    </div>
  );
}

/**
 * «أبلغ فريق حداء»: one click opens a short note, sending it emails the support inbox with the error's details.
 * Its inputs belong to no form (form="…" names none), so they never mix with the form the error came from.
 */
export function ReportError({ error }: { error: unknown }) {
  const { t } = useLang();
  const signedIn = useContext(SessionContext) != null;
  const [open, setOpen] = useState(false);
  const [note, setNote] = useState("");
  const [email, setEmail] = useState("");
  const [busy, setBusy] = useState(false);
  const [reference, setReference] = useState("");
  const [failed, setFailed] = useState(false);
  const [emailError, setEmailError] = useState("");
  const noteId = useId();
  const emailId = useId();

  async function send() {
    if (email.trim() && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) {
      setEmailError(t("اكتب بريداً صحيحاً، مثل name@example.com", "Enter a valid email, e.g. name@example.com"));
      return;
    }
    setEmailError("");
    setBusy(true);
    setFailed(false);
    try {
      setReference(await reportError(error, note, signedIn ? undefined : email) || "—");
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  }

  if (reference) {
    return (
      <p className="report-done" role="status">
        <Icon name="check" size={16} />
        <span>{t(<>وصل البلاغ إلى فريق حداء، رقمه <bdi className="mono">{reference}</bdi>. نتابعه ونرد عليك{signedIn || email ? " على بريدك" : ""}.</>,
          <>Your report reached the Hidaa team, reference <bdi className="mono">{reference}</bdi>. We&apos;ll look into it and get back to you{signedIn || email ? " by email" : ""}.</>)}</span>
      </p>
    );
  }
  if (!open) {
    return (
      <button type="button" className="btn reportbtn" onClick={() => setOpen(true)}>
        <Icon name="flag" size={16} /> {t("أبلغ فريق حداء", "Report to the Hidaa team")}
      </button>
    );
  }
  const a = error instanceof ApiError ? error : null;
  const mail = `mailto:support@hidaaco.com?subject=${encodeURIComponent(`بلاغ خطأ ${a?.status ?? ""} ${a?.code ?? ""}`.trim())}&body=${encodeURIComponent(
    [errorText(error, t), a?.call.method && `${a.call.method} ${a.call.path}`, a?.call.requestId && `Request: ${a.call.requestId}`,
      typeof location === "undefined" ? "" : location.origin + location.pathname, note].filter(Boolean).join("\n"))}`;
  return (
    <div className="reportbox">
      <div className="f">
        <label htmlFor={noteId}>{t("ماذا كنت تحاول أن تفعل؟", "What were you trying to do?")} <span className="opt">({t("اختياري", "optional")})</span></label>
        <textarea id={noteId} form="hidaa-report" rows={2} maxLength={2000} value={note} onChange={(e) => setNote(e.target.value)}
          placeholder={t("مثال: كنت أحفظ رقم جوال المكتب في خطوات ربط واتساب", "e.g. I was saving the office mobile number in the WhatsApp setup")} />
      </div>
      {!signedIn && (
        <div className="f">
          <label htmlFor={emailId}>{t("بريدك لنرد عليك", "Your email, so we can reply")} <span className="opt">({t("اختياري", "optional")})</span></label>
          <input id={emailId} form="hidaa-report" type="email" dir="ltr" autoComplete="email" maxLength={200} value={email}
            aria-invalid={emailError ? true : undefined} aria-describedby={emailError ? `${emailId}-err` : undefined}
            onChange={(e) => { setEmail(e.target.value); setEmailError(""); }} placeholder="name@example.com" />
          {emailError && <p id={`${emailId}-err`} className="ferr" role="alert"><Icon name="alert" size={15} /><span>{emailError}</span></p>}
        </div>
      )}
      <p className="fhint">
        {t("يصل البلاغ إلى support@hidaaco.com ومعه: الصفحة، ونص الخطأ، ورقم الطلب، ونوع المتصفح. لا نرسل كلمات المرور ولا ما كتبته في النموذج.",
          "The report goes to support@hidaaco.com with the page, the error text, the request number and your browser type. Never your passwords or what you typed in the form.")}
      </p>
      <div className="row">
        <button type="button" className="btn primary" disabled={busy} onClick={send}>
          {busy ? t("جارٍ الإرسال…", "Sending…") : t("أرسل البلاغ", "Send report")}
        </button>
        <button type="button" className="btn" disabled={busy} onClick={() => setOpen(false)}>{t("إلغاء", "Cancel")}</button>
      </div>
      {failed && (
        <p className="ferr" role="alert"><Icon name="alert" size={15} />
          <span>{t(<>تعذّر إرسال البلاغ. <a href={mail}>راسلنا بالبريد</a> وسيصل بالتفاصيل نفسها.</>,
            <>Couldn&apos;t send the report. <a href={mail}>Email us instead</a>; it carries the same details.</>)}</span>
        </p>
      )}
    </div>
  );
}

/** The browser's own checks, one message per field name, in the page's language. */
function browserChecks(form: HTMLFormElement, t: T): Errors {
  const out: Errors = {};
  for (const el of Array.from(form.elements)) {
    const c = el as Control;
    if (!c.name || !("validity" in c) || !c.willValidate || c.validity.valid || out[c.name]) continue;
    out[c.name] = messageFor(c, t);
  }
  return out;
}

function messageFor(el: Control, t: T): string {
  const v = el.validity;
  const type = el instanceof HTMLInputElement ? el.type : el instanceof HTMLSelectElement ? "select" : "textarea";
  if (v.valueMissing) {
    if (el.dataset.required) return el.dataset.required;
    if (type === "select" || type === "radio") return t("اختر واحداً.", "Choose one.");
    if (type === "checkbox") return t("يلزم التأشير هنا للمتابعة.", "Tick this to continue.");
    if (type === "file") return t("اختر ملفاً.", "Choose a file.");
    if (type === "date") return t("اختر التاريخ.", "Choose the date.");
    return t("هذا الحقل مطلوب.", "This field is required.");
  }
  if (el.dataset.error) return el.dataset.error;
  const input = el as HTMLInputElement;
  if (v.typeMismatch) {
    if (type === "email") return t("اكتب بريداً صحيحاً، مثل name@example.com", "Enter a valid email, e.g. name@example.com");
    if (type === "url") return t("اكتب رابطاً كاملاً يبدأ بـ https://", "Enter a full link starting with https://");
    return t("الصيغة غير صحيحة.", "This format isn't valid.");
  }
  if (v.tooShort) {
    return t(`${input.minLength} أحرف على الأقل (كتبت ${input.value.length}).`, `At least ${input.minLength} characters (you typed ${input.value.length}).`);
  }
  if (v.tooLong) return t(`${input.maxLength} حرفاً على الأكثر.`, `${input.maxLength} characters at most.`);
  if (v.badInput) {
    if (type === "number") return t("اكتب رقماً فقط.", "Enter a number only.");
    if (type === "date") return t("اختر تاريخاً صحيحاً.", "Choose a valid date.");
    return t("القيمة غير صحيحة.", "This value isn't valid.");
  }
  const today = new Date().toLocaleDateString("en-CA", { timeZone: "Asia/Riyadh" });
  if (v.rangeUnderflow) {
    if (type === "date") return input.min === today ? t("التاريخ لا يكون قبل اليوم.", "The date can't be before today.") : t(`التاريخ لا يكون قبل ${input.min}.`, `The date can't be before ${input.min}.`);
    return t(`أقل قيمة ${input.min}.`, `The minimum is ${input.min}.`);
  }
  if (v.rangeOverflow) {
    if (type === "date") return input.max === today ? t("التاريخ لا يكون بعد اليوم.", "The date can't be after today.") : t(`التاريخ لا يكون بعد ${input.max}.`, `The date can't be after ${input.max}.`);
    return t(`أعلى قيمة ${input.max}.`, `The maximum is ${input.max}.`);
  }
  if (v.stepMismatch) return t("اكتب رقماً صحيحاً.", "Enter a whole number.");
  if (v.patternMismatch) return el.title || t("الصيغة غير صحيحة.", "This format isn't valid.");
  return el.validationMessage;
}

/** Server messages that name fields present in this form, or null when the error is not about one field. */
function serverFieldErrors(err: unknown, form: HTMLFormElement, fieldFor?: FieldFor): Errors | null {
  if (!(err instanceof ApiError)) return null;
  const has = (name: string) => form.elements.namedItem(name) != null;
  const out: Errors = {};
  for (const [k, msg] of Object.entries(err.fields)) if (has(k)) out[k] = msg;
  const mapped = typeof fieldFor === "function" ? fieldFor(err.code) : fieldFor?.[err.code];
  if (mapped && has(mapped) && !out[mapped]) out[mapped] = err.message;
  // A field the form doesn't show still needs its message seen: fall back to the form-level note.
  const unshown = Object.keys(err.fields).some((k) => !has(k));
  return Object.keys(out).length && !unshown ? out : null;
}

function focusFirst(form: HTMLFormElement, errors: Errors) {
  for (const el of Array.from(form.elements)) {
    const c = el as Control;
    if (c.name && errors[c.name]) {
      c.focus();
      return;
    }
  }
}
