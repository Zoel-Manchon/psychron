from datetime import datetime, timedelta, timezone

import pytest

from psychron.domain.identity import (
    LOCKOUT,
    MAX_ATTEMPTS,
    LinkState,
    describe_attempt,
    invitation_state,
    lockout_state,
    seconds_into_totp_period,
    session_expiry,
)

NOW = datetime(2026, 9, 8, 12, 0, tzinfo=timezone.utc)


class TestLockout:
    def test_a_clean_account_has_every_attempt_available(self):
        s = lockout_state([], NOW)
        assert not s.locked and s.attempts_remaining == MAX_ATTEMPTS

    def test_attempts_count_down(self):
        s = lockout_state([NOW - timedelta(minutes=1)], NOW)
        assert s.attempts_remaining == MAX_ATTEMPTS - 1

    def test_locks_at_the_limit_and_says_until_when(self):
        fails = [NOW - timedelta(minutes=i) for i in (1, 2, 3)]
        s = lockout_state(fails, NOW)
        assert s.locked and s.attempts_remaining == 0
        assert s.until == max(fails) + LOCKOUT

    def test_old_failures_are_forgiven(self):
        # A lockout that tallies every mistake ever made becomes permanent for
        # anyone who has owned the account long enough.
        old = [NOW - LOCKOUT - timedelta(minutes=i) for i in (1, 2, 3)]
        assert lockout_state(old, NOW).attempts_remaining == MAX_ATTEMPTS

    def test_the_window_slides_rather_than_resetting_on_a_schedule(self):
        fails = [NOW - timedelta(minutes=14), NOW - timedelta(minutes=10)]
        assert lockout_state(fails, NOW).attempts_remaining == 1
        # Two minutes on, the older failure has passed the 15 minute window and
        # the newer one has not: the window slides, it does not reset in steps.
        assert lockout_state(fails, NOW + timedelta(minutes=2)).attempts_remaining == 2
        # And once both are old enough, the account is clean again.
        assert lockout_state(fails, NOW + timedelta(minutes=6)).attempts_remaining == MAX_ATTEMPTS


class TestInvitation:
    def test_issued_but_never_opened(self):
        assert invitation_state(NOW + timedelta(days=1), None, None, NOW) is LinkState.ISSUED

    def test_opened_and_still_usable(self):
        assert invitation_state(NOW + timedelta(days=1), NOW, None, NOW) is LinkState.OPEN

    def test_consumed_stays_consumed_even_before_expiry(self):
        assert invitation_state(NOW + timedelta(days=1), NOW, NOW, NOW) is LinkState.CONSUMED

    def test_expired_when_time_ran_out_unused(self):
        assert invitation_state(NOW - timedelta(seconds=1), None, None, NOW) is LinkState.EXPIRED

    def test_consumed_wins_over_expired(self):
        # An already-used link must never be reported as merely expired: the two
        # mean different things to whoever is trying to work out what happened.
        assert invitation_state(NOW - timedelta(days=1), NOW - timedelta(days=2),
                                NOW - timedelta(days=2), NOW) is LinkState.CONSUMED


class TestAttemptContext:
    def test_first_ever_sign_in_has_no_usual_location(self):
        ctx = describe_attempt("198.51.100.24", [], "UA/1", set())
        assert ctx.usual_location is None and ctx.location_is_new

    def test_recognises_the_usual_address(self):
        history = [("203.0.113.7", NOW)] * 40 + [("198.51.100.24", NOW)]
        ctx = describe_attempt("203.0.113.7", history, "UA/1", {"UA/1"})
        assert ctx.usual_location == "203.0.113.7"
        assert not ctx.location_is_new
        assert ctx.browser_seen_before

    def test_flags_a_new_address_without_calling_it_an_attack(self):
        history = [("203.0.113.7", NOW)] * 40
        ctx = describe_attempt("198.51.100.24", history, "UA/2", {"UA/1"})
        assert ctx.location_is_new
        assert ctx.usual_location == "203.0.113.7"
        assert not ctx.browser_seen_before
        assert ctx.previous_successes == 40


class TestSessionAndTotp:
    def test_session_lasts_twelve_hours(self):
        assert session_expiry(NOW) - NOW == timedelta(hours=12)

    @pytest.mark.parametrize("second,expected", [(0, 0), (11, 11), (29, 29), (30, 0), (45, 15)])
    def test_period_position_wraps_with_the_code(self, second, expected):
        t = datetime(2026, 9, 8, 12, 0, second, tzinfo=timezone.utc)
        assert seconds_into_totp_period(t) == expected
