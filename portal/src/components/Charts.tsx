"use client";

import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";

/**
 * Small charts without a library, drawn in SVG to the card's real width so text and rounded bar ends never stretch.
 * Colors come from the --viz-* variables in globals.css (light and dark). Every chart has a hover/keyboard readout and a
 * table view, so no value depends on color or hovering alone.
 */

export type ChartSeries = { key: string; label: string; color: string };
export type ChartDatum = { label: string; title: string; values: Record<string, number> };

const nf = new Intl.NumberFormat("en-US");
export const num = (n: number) => nf.format(Math.round(n));

function useWidth<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setWidth(el.clientWidth));
    ro.observe(el);
    setWidth(el.clientWidth);
    return () => ro.disconnect();
  }, []);
  return [ref, width] as const;
}

/** 0 → max in 3–4 clean steps: 0 / 5 / 10 / 15, 0 / 200 / 400 … */
function ticks(max: number): number[] {
  if (max <= 0) return [0, 1];
  const raw = max / 3;
  const pow = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * pow).find((s) => s >= raw) ?? raw;
  const out: number[] = [];
  for (let v = 0; v < max + step * 0.999; v += step) out.push(Math.round(v * 100) / 100);
  if (out.length < 2) out.push(step);
  return out;
}

const compact = (v: number) => (v >= 10000 ? `${nf.format(Math.round(v / 1000))}K` : nf.format(v));

/** A rectangle with 4px rounded top corners and a square base. */
function column(x: number, y: number, w: number, h: number, rounded: boolean) {
  if (h <= 0) return "";
  const r = rounded ? Math.min(4, w / 2, h) : 0;
  return `M${x},${y + h}V${y + r}Q${x},${y} ${x + r},${y}H${x + w - r}Q${x + w},${y} ${x + w},${y + r}V${y + h}Z`;
}

/**
 * Stacked columns, one per datum (oldest left). The first series sits on the baseline. Hover or use the arrow keys to
 * read a column; "عرض كجدول" lists every value.
 */
export function ColumnChart({ data, series, height = 190, label, format = num, highlight }: {
  data: ChartDatum[]; series: ChartSeries[]; height?: number; label: string; format?: (n: number) => string;
  /** Color a column's single series differently, e.g. after-hours bars. */
  highlight?: (d: ChartDatum, i: number) => string | undefined;
}) {
  const [ref, width] = useWidth<HTMLDivElement>();
  const [active, setActive] = useState<number | null>(null);
  const totals = data.map((d) => series.reduce((s, x) => s + (d.values[x.key] ?? 0), 0));
  const t = ticks(Math.max(...totals, 0));
  const top = t[t.length - 1];
  const padL = 38, padB = 22, padT = 8;
  const plotW = Math.max(0, width - padL - 4), plotH = height - padB - padT;
  const slot = data.length ? plotW / data.length : 0;
  const barW = Math.max(2, Math.min(24, slot - 2));
  const y = (v: number) => padT + plotH - (v / top) * plotH;
  const every = Math.max(1, Math.ceil(data.length / Math.max(1, Math.floor(plotW / 46))));

  function onKey(e: KeyboardEvent) {
    if (!data.length) return;
    if (e.key === "ArrowRight") setActive((a) => Math.min(data.length - 1, (a ?? -1) + 1));
    else if (e.key === "ArrowLeft") setActive((a) => Math.max(0, (a ?? data.length) - 1));
    else if (e.key === "Escape") setActive(null);
    else return;
    e.preventDefault();
  }

  const a = active == null ? null : data[active];
  const tipX = active == null ? 0 : padL + active * slot + slot / 2;

  return (
    <div className="chart">
      {series.length > 1 && (
        <ul className="legend" aria-hidden="true">
          {series.map((s) => <li key={s.key}><i style={{ background: s.color }} />{s.label}</li>)}
        </ul>
      )}
      <div ref={ref} className="plot" style={{ height }}>
        {width > 0 && (
          <svg width={width} height={height} style={{ direction: "ltr" }} role="img" aria-label={label} tabIndex={0} onKeyDown={onKey}
               onBlur={() => setActive(null)} onPointerLeave={() => setActive(null)}>
            {t.map((v) => (
              <g key={v}>
                <line x1={padL} x2={width - 4} y1={y(v)} y2={y(v)} className="grid" />
                <text x={padL - 6} y={y(v)} dy="0.32em" textAnchor="end" className="tick">{compact(v)}</text>
              </g>
            ))}
            {data.map((d, i) => {
              const x = padL + i * slot + (slot - barW) / 2;
              let base = 0;
              const parts = series.map((s) => ({ s, v: d.values[s.key] ?? 0 })).filter((p) => p.v > 0);
              return (
                <g key={i} className={active === i ? "on" : undefined}>
                  {parts.map((p, j) => {
                    const y0 = y(base), y1 = y(base + p.v);
                    base += p.v;
                    const gap = j > 0 ? 2 : 0;
                    return <path key={p.s.key} d={column(x, y1, barW, Math.max(0, y0 - y1 - gap), j === parts.length - 1)}
                                 fill={(series.length === 1 && highlight?.(d, i)) || p.s.color} />;
                  })}
                  <rect x={padL + i * slot} y={padT} width={slot} height={plotH} fill="transparent"
                        onPointerEnter={() => setActive(i)} onPointerDown={() => setActive(i)} />
                  {i % every === 0 && (
                    <text x={padL + i * slot + slot / 2} y={height - 6} textAnchor="middle" className="tick">{d.label}</text>
                  )}
                </g>
              );
            })}
            {active != null && <line x1={tipX} x2={tipX} y1={padT} y2={padT + plotH} className="cross" />}
          </svg>
        )}
        {a && (
          <div className="tip" role="status" style={{ left: Math.min(Math.max(tipX, 70), width - 70) }}>
            <span className="small muted">{a.title}</span>
            {[...series].reverse().map((s) => (
              <span key={s.key} className="tiprow"><i style={{ background: (series.length === 1 && highlight?.(a, active!)) || s.color }} /><b>{format(a.values[s.key] ?? 0)}</b> {s.label}</span>
            ))}
          </div>
        )}
      </div>
      <details className="tableview">
        <summary className="small">عرض كجدول</summary>
        <div className="tablewrap">
          <table className="table small">
            <thead><tr><th>{label}</th>{series.map((s) => <th key={s.key}>{s.label}</th>)}</tr></thead>
            <tbody>
              {data.map((d, i) => (
                <tr key={i}><td>{d.title}</td>{series.map((s) => <td key={s.key} className="num">{format(d.values[s.key] ?? 0)}</td>)}</tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </div>
  );
}

/** Ranked horizontal bars, value at the bar's end; an optional note per row (e.g. "لا عقارات"). */
export function BarList({ rows, color = "var(--viz-1)", format = num, label }: {
  rows: { name: string; value: number; note?: ReactNode }[]; color?: string; format?: (n: number) => string; label: string;
}) {
  const max = Math.max(1, ...rows.map((r) => r.value));
  return (
    <ul className="barlist" aria-label={label}>
      {rows.map((r) => (
        <li key={r.name}>
          <span className="bl-name">{r.name}</span>
          <span className="bl-track"><i style={{ width: `${(r.value / max) * 100}%`, background: color }} /><b>{format(r.value)}</b></span>
          {r.note !== undefined && <span className="bl-note">{r.note}</span>}
        </li>
      ))}
    </ul>
  );
}

/** One whole split into parts (buy vs rent, hot/warm/cold), with a labelled legend. */
export function SplitBar({ parts, label }: { parts: { label: string; value: number; color: string }[]; label: string }) {
  const total = parts.reduce((s, p) => s + p.value, 0);
  if (total === 0) return <p className="small muted">لا بيانات بعد.</p>;
  return (
    <div className="split">
      <div className="split-bar" role="img" aria-label={`${label}: ${parts.map((p) => `${p.label} ${p.value}`).join("، ")}`}>
        {parts.filter((p) => p.value > 0).map((p) => <i key={p.label} style={{ flexGrow: p.value, background: p.color }} title={`${p.label}: ${p.value}`} />)}
      </div>
      <ul className="legend">
        {parts.map((p) => (
          <li key={p.label}><i style={{ background: p.color }} />{p.label} <b>{num(p.value)}</b> <span className="muted">({Math.round((p.value / total) * 100)}٪)</span></li>
        ))}
      </ul>
    </div>
  );
}

/** "+12٪" against the previous period, or "جديد" when there was nothing before. */
export function Delta({ now, before, upIsGood = true }: { now: number; before: number; upIsGood?: boolean }) {
  if (before === 0 && now === 0) return null;
  if (before === 0) return <span className="delta up">جديد</span>;
  const pct = Math.round(((now - before) / before) * 100);
  if (pct === 0) return <span className="delta">كما هو</span>;
  const good = pct > 0 === upIsGood;
  return <span className={`delta ${good ? "up" : "down"}`} dir="ltr">{pct > 0 ? "▲" : "▼"} {Math.abs(pct)}%</span>;
}
