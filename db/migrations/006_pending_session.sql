-- Between the password and the second factor there is a real state: the first
-- factor is proved and the second is not. Without somewhere to hold it, the
-- second step would have to trust an identifier the client sends back, which
-- means anyone who knows a username can skip straight to guessing codes.
--
-- A pending session is a session that exists but grants nothing.

ALTER TABLE session ADD COLUMN pending boolean NOT NULL DEFAULT false;
