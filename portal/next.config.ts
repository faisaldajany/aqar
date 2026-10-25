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
};

export default config;
