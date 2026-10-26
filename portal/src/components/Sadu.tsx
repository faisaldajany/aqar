/** Woven Sadu band: the Bedouin weave of the caravan routes the name حداء comes from. Decorative only. */
export function SaduBand({ height = 28, className }: { height?: number; className?: string }) {
  const id = `sadu-${height}`;
  return (
    <svg className={className} width="100%" height={height} aria-hidden="true" focusable="false">
      <defs>
        <pattern id={id} width={height * 2} height={height} patternUnits="userSpaceOnUse">
          <rect width={height * 2} height={height} fill="var(--sadu-ground, #17213F)" />
          <rect y={height * 0.12} width={height * 2} height={height * 0.08} fill="#F4F5F1" />
          <rect y={height * 0.8} width={height * 2} height={height * 0.08} fill="#F4F5F1" />
          <path d={`M0 ${height / 2} L${height / 2} ${height * 0.26} L${height} ${height / 2} L${height / 2} ${height * 0.74} Z`} fill="#A82B2E" />
          <path d={`M${height} ${height / 2} L${height * 1.5} ${height * 0.26} L${height * 2} ${height / 2} L${height * 1.5} ${height * 0.74} Z`} fill="#C8912F" />
          <path d={`M${height * 0.38} ${height / 2} L${height / 2} ${height * 0.42} L${height * 0.62} ${height / 2} L${height / 2} ${height * 0.58} Z`} fill="#F4F5F1" />
          <path d={`M${height * 1.38} ${height / 2} L${height * 1.5} ${height * 0.42} L${height * 1.62} ${height / 2} L${height * 1.5} ${height * 0.58} Z`} fill="#17213F" />
        </pattern>
      </defs>
      <rect width="100%" height={height} fill={`url(#${id})`} />
    </svg>
  );
}

/** Brand mark: a single Sadu diamond. */
export function SaduMark({ size = 26 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 26 26" aria-hidden="true" focusable="false">
      <rect width="26" height="26" rx="6" fill="#17213F" />
      <path d="M13 3 L23 13 L13 23 L3 13 Z" fill="#A82B2E" />
      <path d="M13 8 L18 13 L13 18 L8 13 Z" fill="#C8912F" />
      <path d="M13 11 L15 13 L13 15 L11 13 Z" fill="#F4F5F1" />
    </svg>
  );
}
