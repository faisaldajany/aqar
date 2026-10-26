import type { Message } from "@/lib/api";
import { Icon } from "./Icon";

/** Text, or the photo / map pin the agent sent with a listing card. */
export function MessageBody({ m }: { m: Message }) {
  if (m.type === "image" && m.mediaId && (m.mediaId.startsWith("https://") || m.mediaId.startsWith("/api/"))) {
    return (
      <figure className="msgmedia">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={m.mediaId} alt={m.body ?? "صورة العقار"} loading="lazy" />
        {m.body && <figcaption>{m.body}</figcaption>}
      </figure>
    );
  }
  if (m.type === "location" && m.mediaId?.startsWith("geo:")) {
    const q = m.mediaId.slice(4);
    return (
      <a className="msgloc" href={`https://maps.google.com/?q=${q}`} target="_blank" rel="noopener noreferrer">
        <Icon name="pin" size={16} />{m.body ?? "موقع العقار"} <span className="small muted">(افتح الخريطة)</span>
      </a>
    );
  }
  if (m.type === "audio") return <>رسالة صوتية</>;
  return <>{m.body ?? `[${m.type}]`}</>;
}
