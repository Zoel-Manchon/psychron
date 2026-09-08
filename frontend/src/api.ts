// Thin client over the read API.
//
// There is no credential in JavaScript. The session is an httpOnly cookie, so
// no script on this page can read it, and the browser attaches it on its own —
// including to the WebSocket handshake, which is what removes the token from
// the query string where it used to land in logs and history.
//
// Same origin, always: the dev server proxies /api and Caddy serves both, so
// nothing here needs to know a host or a port and nothing can be pointed at
// the wrong one.

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function get<T>(path: string, params?: Record<string, string>): Promise<T> {
  const url = new URL(path, window.location.origin);
  // URLSearchParams encodes the "+" in a timezone offset, which a hand-built
  // query string does not — there it means a space and the timestamp fails to
  // parse on the server.
  if (params) Object.entries(params).forEach(([k, v]) => url.searchParams.set(k, v));

  const res = await fetch(url, { credentials: "same-origin" });
  if (!res.ok) {
    throw new ApiError(res.status, res.status === 401
      ? "the token was rejected"
      : `request failed: ${res.status}`);
  }
  return res.json() as Promise<T>;
}

export type Reference = {
  time: string; temperature_c: number | null; humidity_pct: number | null;
};

export type Current = {
  measured: { time: string; temperature_c: number | null; humidity_pct: number | null };
  derived: Record<string, number | null>;
  // The same room a day earlier, or null when nothing was recorded near that
  // instant. Null is a real answer, not a missing field.
  day_ago: Reference | null;
  provenance: {
    firmware: string; seq: number; boot_id: number;
    quality: number; quality_flags: string[];
    device_time: string | null; received_at: string;
  };
};

export type Point = {
  t: string; temp: number | null; hum: number | null;
  temp_min: number | null; temp_max: number | null;
};

export type Series = {
  from: string; to: string; bucket: string;
  count: number; truncated: boolean; max_points: number; points: Point[];
};

export type Stats = {
  samples: number; expected_samples: number; completeness: number;
  failed: number; flagged: number;
  temp_min: number; temp_max: number; temp_mean: number; temp_sd: number;
  temp_p05: number; temp_median: number; temp_p95: number;
  hum_min: number; hum_max: number; hum_mean: number; hum_sd: number;
  hum_p05: number; hum_median: number; hum_p95: number;
};

export type Device = {
  device: string;
  extent: { first: string | null; last: string | null; total: number };
  boots: { boot_id: number; firmware: string; reset_reason: string; first_seen: string }[];
  gaps_24h: { gap_start: string; gap_end: string; seconds: number }[];
  rejected_24h: number;
};

export type Health = {
  status: string; device: string;
  last_reading_age_s: number | null; device_reporting: boolean;
};

export const api = {
  health: () => get<Health>("/api/health"),
  current: () => get<Current>("/api/current"),
  readings: (from: string) => get<Series>("/api/readings", { from }),
  stats: (from: string) => get<Stats>("/api/stats", { from }),
  device: () => get<Device>("/api/device"),
  exportUrl: (format: string, from: string) =>
    `/api/export?format=${format}&from=${encodeURIComponent(from)}`,
};

export function openLive(onReading: (r: Record<string, unknown>) => void): WebSocket {
  // No token in the URL: the session cookie travels with the handshake.
  const scheme = window.location.protocol === "https:" ? "wss" : "ws";
  const url = `${scheme}://${window.location.host}/api/live`;
  const ws = new WebSocket(url);
  ws.onmessage = (e) => onReading(JSON.parse(e.data));
  return ws;
}

export const RANGES: { label: string; hours: number }[] = [
  { label: "1 h", hours: 1 },
  { label: "6 h", hours: 6 },
  { label: "24 h", hours: 24 },
  { label: "7 d", hours: 24 * 7 },
  { label: "30 d", hours: 24 * 30 },
];

export const since = (hours: number) =>
  new Date(Date.now() - hours * 3600_000).toISOString();

// The range belongs in the URL: a window worth looking at is a window worth
// sending to someone. Slugs are derived from the labels rather than kept in a
// second table that could quietly disagree with them.
export const rangeSlug = (label: string) => label.replace(/[^0-9a-z]/gi, "");

export const hoursFromSlug = (slug: string | null): number | null => {
  const found = RANGES.find((r) => rangeSlug(r.label) === slug);
  return found ? found.hours : null;
};


// ── identity ────────────────────────────────────────────────────────────────

export type AuthState = {
  signed_in_as: string | null;
  first_run: boolean;
  provider: string;
  session_hours: number;
};

export type Challenge = {
  next: "totp" | "done";
  identifier: string;
  attempts_remaining?: number;
  max_attempts?: number;
  period?: number;
  elapsed?: number;
  recovery?: { unused: number; total: number };
  context?: {
    ip: string | null;
    location_is_new: boolean;
    usual_location: string | null;
    previous_successes: number;
    browser_seen_before: boolean;
  };
};

export type InvitationInfo = {
  state: "issued" | "open" | "consumed" | "expired";
  identifier: string | null;
  expires_at?: string;
};

export type Enrolment = {
  identifier: string;
  totp_secret: string;
  otpauth: string;
  recovery_codes: string[];
};

async function post<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(path, {
    method: "POST",
    credentials: "same-origin",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const payload = await res.json().catch(() => ({}));
  if (!res.ok) {
    // FastAPI wraps a structured error under `detail`; the screens need the
    // fields inside it, not a flattened string.
    const detail = (payload as { detail?: Record<string, unknown> }).detail ?? payload;
    throw new ApiError(res.status, JSON.stringify(detail));
  }
  return payload as T;
}

export const auth = {
  state: () => get<AuthState>("/api/auth/state"),
  login: (identifier: string, password: string) =>
    post<Challenge>("/api/auth/login", { identifier, password }),
  totp: (code: string) => post<Challenge>("/api/auth/totp", { code }),
  recovery: (code: string) => post<Challenge>("/api/auth/recovery", { code }),
  logout: () => post<{ ok: boolean }>("/api/auth/logout", {}),
  invitation: (token: string) =>
    get<InvitationInfo>("/api/auth/invitation", { token }),
  accept: (token: string, password: string) =>
    post<Enrolment>("/api/auth/invitation", { token, password }),
};

export const detailOf = (e: unknown): Record<string, unknown> => {
  if (!(e instanceof ApiError)) return {};
  try { return JSON.parse(e.message) as Record<string, unknown>; } catch { return {}; }
};
