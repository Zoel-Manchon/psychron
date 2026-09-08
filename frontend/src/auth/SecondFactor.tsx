import { useEffect, useRef, useState } from "react";
import { auth, detailOf, type Challenge } from "../api";

// Plate A2 — second factor.
//
// Six fixed-width boxes with a hairline under each: a filled box is marked by
// the weight of its rule, never by colour. The validity bar empties with scale
// marks and says plainly that the code rotates rather than the session expiring
// — otherwise a bar running out reads as losing your place.
//
// The attempt context is a row of facts with the evidence beside them. No alarm
// colour: the reader is the one who knows whether it was them.

type Props = {
  challenge: Challenge;
  onSignedIn: (who: string) => void;
  onCancel: () => void;
};

const LEN = 6;

export function SecondFactor({ challenge, onSignedIn, onCancel }: Props) {
  const period = challenge.period ?? 30;
  const [digits, setDigits] = useState<string[]>(Array(LEN).fill(""));
  const [elapsed, setElapsed] = useState(challenge.elapsed ?? 0);
  const [useRecovery, setUseRecovery] = useState(false);
  const [recoveryCode, setRecoveryCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [remaining, setRemaining] = useState(challenge.attempts_remaining ?? 3);
  const boxes = useRef<(HTMLInputElement | null)[]>([]);

  useEffect(() => {
    const id = setInterval(() => setElapsed((e) => (e + 1) % period), 1000);
    return () => clearInterval(id);
  }, [period]);

  const submit = async (code: string) => {
    setError(null);
    try {
      const c = useRecovery ? await auth.recovery(code) : await auth.totp(code);
      onSignedIn(c.identifier);
    } catch (err) {
      const d = detailOf(err);
      if (d.error === "no_pending_session") { onCancel(); return; }
      setError(useRecovery ? "That recovery code is not valid." : "That code is not valid.");
      if (typeof d.attempts_remaining === "number") setRemaining(d.attempts_remaining);
      setDigits(Array(LEN).fill(""));
      boxes.current[0]?.focus();
    }
  };

  const setDigit = (i: number, v: string) => {
    const clean = v.replace(/\D/g, "").slice(-1);
    const next = [...digits];
    next[i] = clean;
    setDigits(next);
    if (clean && i < LEN - 1) boxes.current[i + 1]?.focus();
    if (next.every((d) => d)) submit(next.join(""));
  };

  const left = period - elapsed;
  const ctx = challenge.context;

  return (
    <div className="sheet sheet-narrow" style={{ maxWidth: 940 }}>
      <span className="label" style={{ borderBottom: 0 }}>Environmental station · node 01</span>
      <div className="row" style={{ justifyContent: "space-between", alignItems: "baseline" }}>
        <h1 style={{ fontFamily: "var(--mono)", fontSize: 26, fontWeight: 500, margin: "4px 0 0" }}>
          Second factor
        </h1>
        <span className="mono-note">Authenticator required</span>
      </div>
      <p className="mono-note" style={{ marginTop: 6 }}>
        From the authenticator registered to <b>{challenge.identifier}</b>.
      </p>

      <hr className="rule" />

      <div className="cols cols-2">
        <div>
          {!useRecovery ? (
            <>
              <span className="label">Six-digit code</span>
              <div className="row" style={{ gap: 8 }}>
                {digits.map((d, i) => (
                  <input key={i} ref={(el) => { boxes.current[i] = el; }}
                         value={d} inputMode="numeric" maxLength={1} autoFocus={i === 0}
                         onChange={(e) => setDigit(i, e.target.value)}
                         onKeyDown={(e) => {
                           if (e.key === "Backspace" && !digits[i] && i > 0) boxes.current[i - 1]?.focus();
                         }}
                         style={{
                           width: 42, textAlign: "center", fontSize: 22,
                           // Filled boxes carry a heavier rule. The state is in
                           // the stroke, not in a colour.
                           borderBottomWidth: d ? 2 : 1,
                           borderBottomColor: d ? "var(--ink)" : "var(--rule)",
                         }} />
                ))}
              </div>

              <div style={{ marginTop: 26 }}>
                <span className="label">Code validity</span>
                <svg viewBox="0 0 300 22" width="100%" style={{ maxWidth: 300, display: "block" }}>
                  <line x1="0" y1="14" x2="300" y2="14" stroke="var(--rule)" strokeWidth="1" />
                  <line x1="0" y1="14" x2={300 * (left / period)} y2="14"
                        stroke="var(--accent)" strokeWidth="3" />
                  {[0, 10, 20, 30].map((m) => (
                    <g key={m}>
                      <line x1={(m / period) * 300} y1="8" x2={(m / period) * 300} y2="14"
                            stroke="var(--rule)" strokeWidth="1" />
                      <text x={(m / period) * 300} y="6" fontSize="7" fill="var(--ink-3)"
                            fontFamily="var(--mono)"
                            textAnchor={m === 0 ? "start" : m === 30 ? "end" : "middle"}>0:{String(m).padStart(2, "0")}</text>
                    </g>
                  ))}
                </svg>
                <p className="mono-note">
                  0:{String(left).padStart(2, "0")} left of 0:{period} · the code rotates,
                  the session does not expire
                </p>
              </div>
            </>
          ) : (
            <>
              <span className="label">One-time recovery code</span>
              <input value={recoveryCode} onChange={(e) => setRecoveryCode(e.target.value)}
                     placeholder="8 hexadecimal characters" autoFocus />
              <div style={{ marginTop: 20 }}>
                <button onClick={() => submit(recoveryCode)} disabled={!recoveryCode}>Verify →</button>
              </div>
            </>
          )}

          {error && <p className="mono-note accent" style={{ marginTop: 18 }}>{error}</p>}
          <p className="mono-note" style={{ marginTop: 10 }}>
            {remaining} of {challenge.max_attempts ?? 3} attempts left before a 15 minute lockout
          </p>
          <div className="row" style={{ marginTop: 18, gap: 2 }}>
            <button onClick={onCancel}>← Back</button>
          </div>
        </div>

        <div>
          <span className="label">Recovery route</span>
          <table>
            <tbody>
              <tr>
                <td>
                  <button onClick={() => { setUseRecovery((v) => !v); setError(null); }}
                          style={{ borderBottom: 0, padding: 0 }}>
                    {useRecovery ? "Use the authenticator" : "Use a one-time code"}
                  </button>
                </td>
                <td className="v">
                  {challenge.recovery
                    ? `${challenge.recovery.unused} of ${challenge.recovery.total} unspent`
                    : "—"}
                </td>
              </tr>
              <tr>
                <td>Lost the authenticator</td>
                <td className="v">reissue by the owner</td>
              </tr>
            </tbody>
          </table>

          {ctx && (
            <>
              <span className="label" style={{ marginTop: 26 }}>Attempt context</span>
              <table>
                <tbody>
                  <tr>
                    <td>
                      {/* A shape carries the state, so nothing here depends on
                          hue: an open square for unusual, a filled one for known. */}
                      <span style={{ fontFamily: "var(--mono)", marginRight: 8 }}>
                        {ctx.location_is_new ? "□" : "■"}
                      </span>
                      {ctx.location_is_new ? "New location for this account" : "Usual location"}
                    </td>
                    <td className="v">{ctx.ip ?? "—"}</td>
                  </tr>
                  <tr>
                    <td>Usual address</td>
                    <td className="v">{ctx.usual_location ?? "none yet"} · {ctx.previous_successes} sign-ins</td>
                  </tr>
                  <tr>
                    <td>
                      <span style={{ fontFamily: "var(--mono)", marginRight: 8 }}>
                        {ctx.browser_seen_before ? "■" : "□"}
                      </span>
                      {ctx.browser_seen_before ? "Known browser" : "Browser not seen before"}
                    </td>
                    <td className="v" />
                  </tr>
                </tbody>
              </table>
              <p className="mono-note" style={{ marginTop: 10 }}>
                If this was not you, do not verify and tell the owner of the panel.
              </p>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
