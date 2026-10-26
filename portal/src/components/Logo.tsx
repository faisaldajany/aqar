/** Hidaa mark: two messages, and the reply rising from them. */
export function LogoMark({ size = 28, inverted = false }: { size?: number; inverted?: boolean }) {
  const dot = inverted ? "#FFFFFF" : "#17213F";
  const reply = inverted ? "#E0574F" : "#A82B2E";
  return (
    <svg width={size} height={size} viewBox="0 0 64 64" aria-hidden="true" focusable="false">
      <circle cx="12" cy="44" r="8" fill={dot} />
      <circle cx="32" cy="44" r="8" fill={dot} />
      <circle cx="52" cy="24" r="9" fill={reply} />
    </svg>
  );
}

/** Mark + name, for headers. */
export function Logo({ size = 28, inverted = false }: { size?: number; inverted?: boolean }) {
  return (
    <span className="logo" style={{ fontSize: size * 0.95 }}>
      <LogoMark size={size} inverted={inverted} />
      <span className="logo-word">حداء</span>
    </span>
  );
}
