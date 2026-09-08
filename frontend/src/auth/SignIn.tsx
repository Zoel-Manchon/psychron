import { useState } from "react";
import { auth, detailOf, type AuthState, type Challenge } from "../api";

// Plate A1 — sign in.
//
// Half a plate, no centred card: the fields are rules rather than boxes, and
// the panel on the right is a revision block. The certificate fingerprint and
// the session length are honest ornament — facts a reader can check, which is
// the only kind of reassurance worth printing.

type Props = {
  state: AuthState;
  onChallenge: (c: Challenge) => void;
  onSignedIn: (who: string) => void;
};

export function SignIn({ state, onChallenge, onSignedIn }: Props) {
  const [identifier, setIdentifier] = useState("");
  const [password, setPassword] = useState("");
  const [show, setShow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [remaining, setRemaining] = useState<number | null>(null);
  const [lockedUntil, setLockedUntil] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true); setError(null); setLockedUntil(null);
    try {
      const c = await auth.login(identifier, password);
      if (c.next === "done") onSignedIn(c.identifier);
      else onChallenge(c);
    } catch (err) {
      const d = detailOf(err);
      if (d.error === "locked") {
        setLockedUntil(String(d.until ?? ""));
        setError("Too many attempts. Locked for 15 minutes.");
      } else {
        // One message whichever way it failed. A different wording for an
        // unknown account would let anyone enumerate who has one here.
        setError("That identifier and password do not match.");
        setRemaining(typeof d.attempts_remaining === "number" ? d.attempts_remaining : null);
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="sheet sheet-narrow" style={{ maxWidth: 940 }}>
      <span className="label" style={{ borderBottom: 0 }}>
        Environmental station · node 01
      </span>
      <div className="row" style={{ justifyContent: "space-between", alignItems: "baseline" }}>
        <h1 style={{ fontFamily: "var(--mono)", fontSize: 26, fontWeight: 500, margin: "4px 0 0" }}>
          Sign in
        </h1>
        <span className="mono-note">TLS · private CA</span>
      </div>
      <p className="mono-note" style={{ marginTop: 6 }}>Access to a single-node panel</p>

      <hr className="rule" />

      <div className="cols cols-2">
        <form onSubmit={submit}>
          <span className="label">Identifier</span>
          <input value={identifier} onChange={(e) => setIdentifier(e.target.value)}
                 placeholder="email or username" autoComplete="username" autoFocus />

          <div style={{ marginTop: 22 }}>
            <div className="row" style={{ justifyContent: "space-between" }}>
              <span className="label" style={{ flex: 1 }}>Password</span>
              <button type="button" onClick={() => setShow((v) => !v)}
                      style={{ borderBottom: 0 }}>{show ? "Hide" : "Show"}</button>
            </div>
            <input type={show ? "text" : "password"} value={password}
                   onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
          </div>

          <div style={{ marginTop: 26 }}>
            <button type="submit" disabled={busy || !identifier || !password}>
              {busy ? "Checking…" : "Continue →"}
            </button>
          </div>

          {error && (
            <p className="mono-note accent" style={{ marginTop: 18 }}>
              {error}
              {remaining !== null && ` · ${remaining} of 3 attempts left`}
              {lockedUntil && ` · until ${new Date(lockedUntil).toLocaleTimeString()}`}
            </p>
          )}

          <hr className="rule-faint" style={{ marginTop: 26 }} />
          <span className="label">No account</span>
          <p className="mono-note">
            Enrolment is by single-use link only. Ask the owner of the panel for one.
          </p>
        </form>

        <div>
          <span className="label">Identity delegation</span>
          <dl className="revision">
            <dt>Provider</dt><dd>{state.provider}</dd>
            <dt>Transport</dt><dd>TLS 1.3 · X25519</dd>
            <dt>Session</dt><dd>{state.session_hours} h</dd>
            <dt>Second factor</dt><dd>authenticator app</dd>
            <dt>Recovery</dt><dd>one-time codes</dd>
          </dl>
          <p className="mono-note" style={{ marginTop: 16 }}>
            The password is verified by the provider and never stored by the panel.
            Enrolment is by invitation.
          </p>
        </div>
      </div>
    </div>
  );
}
