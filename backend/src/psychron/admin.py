"""Owner tools: invite someone, reissue an authenticator, see who has access.

The gap the sign-in screens leave open. The design covers *receiving* an
invitation, not creating one, and the second-factor plate promises "reissue by
the owner" without saying where that happens. Until there is a screen for it,
this is where.

Reissue works by issuing a fresh invitation rather than printing a new secret:
the enrolment screen then shows the authenticator secret and the recovery codes
in the browser of whoever is enrolling, once. A secret that travels through a
terminal ends up in scrollback, in a log, and in whatever transcript was open at
the time.
"""

from __future__ import annotations

import argparse
import sys

from .adapters.identity_pg import PostgresIdentity
from .cli import dsn_from, load_env


def _panel_url(env: dict[str, str]) -> str:
    return env.get("PSYCHRON_PANEL_URL", "http://localhost:5173")


def cmd_list(idp: PostgresIdentity, _args, _env) -> int:
    with idp._pool.connection() as c, c.cursor() as cur:
        cur.execute(
            "SELECT a.identifier, a.is_owner, a.created_at,"
            "       a.totp_secret IS NOT NULL AS has_totp,"
            "       (SELECT count(*) FROM recovery_code r"
            "          WHERE r.account_id = a.account_id AND r.used_at IS NULL) AS codes,"
            "       (SELECT max(at) FROM login_attempt l"
            "          WHERE l.identifier = a.identifier AND l.stage = 'totp'"
            "            AND l.outcome = 'ok') AS last_full_signin"
            "  FROM account a WHERE a.disabled_at IS NULL ORDER BY a.created_at")
        rows = cur.fetchall()

    if not rows:
        print("No accounts. The first one enrolled becomes the owner.")
        return 0

    print(f"{'identifier':<32} {'owner':<6} {'2fa':<5} {'codes':<6} last full sign-in")
    for r in rows:
        # A count of unused recovery codes is worth showing plainly: zero means
        # a lost authenticator locks the account out permanently.
        print(f"{r['identifier']:<32} {'yes' if r['is_owner'] else '':<6} "
              f"{'yes' if r['has_totp'] else 'no':<5} {r['codes']:<6} "
              f"{r['last_full_signin'] or 'never'}")
    return 0


def cmd_invite(idp: PostgresIdentity, args, env) -> int:
    token = idp.issue_invitation(args.identifier, issued_by=args.by)
    print(f"Invitation for {args.identifier}, valid 7 days, single use:\n")
    print(f"  {_panel_url(env)}/?invite={token}\n")
    if idp.account_count() == 0:
        print("This is the first account, so it becomes the owner of the panel.")
    return 0


def cmd_reissue(idp: PostgresIdentity, args, env) -> int:
    with idp._pool.connection() as c, c.cursor() as cur:
        cur.execute("SELECT account_id FROM account WHERE identifier = %s", (args.identifier,))
        if cur.fetchone() is None:
            print(f"No account for {args.identifier}. Use `invite` instead.", file=sys.stderr)
            return 1
        # Existing sessions die with the credential they were built on.
        cur.execute(
            "UPDATE session SET revoked_at = now() WHERE revoked_at IS NULL AND account_id ="
            " (SELECT account_id FROM account WHERE identifier = %s)", (args.identifier,))

    token = idp.issue_invitation(args.identifier, issued_by=args.by)
    print(f"Existing sessions for {args.identifier} revoked.\n")
    print("Enrol again to receive a new authenticator secret and recovery codes.")
    print("They are shown once, in the browser, and never printed here:\n")
    print(f"  {_panel_url(env)}/?invite={token}\n")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="psychron-admin")
    ap.add_argument("--by", help="identifier of the owner issuing this", default=None)
    sub = ap.add_subparsers(dest="command", required=True)

    sub.add_parser("list", help="who has access").set_defaults(func=cmd_list)

    inv = sub.add_parser("invite", help="issue a single-use enrolment link")
    inv.add_argument("identifier")
    inv.set_defaults(func=cmd_invite)

    re = sub.add_parser("reissue", help="replace an account's authenticator and codes")
    re.add_argument("identifier")
    re.set_defaults(func=cmd_reissue)

    args = ap.parse_args(argv)
    env = load_env()
    idp = PostgresIdentity(dsn_from(env))
    try:
        return args.func(idp, args, env)
    finally:
        idp.close()


if __name__ == "__main__":
    raise SystemExit(main())
