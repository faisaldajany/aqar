import type { NextConfig } from "next";

const backend = process.env.BACKEND_URL ?? "http://localhost:8080";

// The portal and API share one origin: the browser calls /api/*, Next forwards to Spring.
// This keeps the session cookie first-party (HttpOnly, SameSite=Lax).
const config: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${backend}/api/:path*` }];
  },
  // Security headers on every page. The CSP is deliberately limited to directives that cannot break the
  // Facebook SDK (WhatsApp Embedded Signup) or the Moyasar redirect: no framing, no <base> or plugin tricks,
  // and forms only post back to this site.
  async headers() {
    return [{
      source: "/:path*",
      headers: [
        { key: "Strict-Transport-Security", value: "max-age=31536000; includeSubDomains" },
        { key: "X-Content-Type-Options", value: "nosniff" },
        { key: "X-Frame-Options", value: "DENY" },
        { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
        { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=(), payment=()" },
        { key: "Content-Security-Policy", value: "frame-ancestors 'none'; base-uri 'self'; object-src 'none'; form-action 'self'" },
      ],
    }];
  },
};

export default config;
