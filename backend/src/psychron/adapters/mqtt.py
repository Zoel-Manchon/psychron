"""MQTT adapter over paho, wrapping the broker as a plain callback source."""

from __future__ import annotations

import logging
import os
import secrets
import ssl
from datetime import datetime, timezone

import paho.mqtt.client as mqtt

log = logging.getLogger(__name__)


class MqttTelemetrySource:
    def __init__(self, host: str, port: int, username: str = "", password: str = "",
                 topic: str = "psychron/v1/#", client_id: str | None = None,
                 ca_cert: str | None = None, client_cert: str | None = None,
                 client_key: str | None = None) -> None:
        # Unique per process by default. Two MQTT clients sharing an identifier
        # cannot coexist: the broker evicts the older one on every connect, so a
        # second copy of this service left running turns both into a reconnect
        # loop that stores nothing and reports only "unspecified error".
        if client_id is None:
            client_id = f"psychron-ingest-{os.getpid()}-{secrets.token_hex(3)}"
        self._client_id = client_id
        self._topic = topic
        self._on_message = None
        self._client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=client_id)

        self._tls = ca_cert is not None
        if self._tls:
            # Hostname verification stays on. The broker certificate carries the
            # LAN address in its subjectAltName precisely so this can be left
            # alone; reaching for tls_insecure_set here would throw away the
            # guarantee that the certificate belongs to the host being dialled.
            self._client.tls_set(ca_certs=ca_cert, certfile=client_cert, keyfile=client_key,
                                 tls_version=ssl.PROTOCOL_TLS_CLIENT)
            # On this listener the username comes from the certificate CN, so
            # there is no password to hold, share or let go stale.
        else:
            self._client.username_pw_set(username, password)
        self._client.on_connect = self._handle_connect
        self._client.on_message = self._handle_message
        self._client.on_disconnect = self._handle_disconnect
        self._host, self._port = host, port

    def _handle_connect(self, client, _userdata, _flags, reason_code, _props=None):
        if reason_code != 0:
            # A bad password or a missing ACL entry shows up here and nowhere
            # else; paho will otherwise retry in silence forever.
            log.error("broker refused the connection: %s", reason_code)
            return
        log.info("connected as %s over %s, subscribing to %s", self._client_id,
                 "mTLS" if self._tls else "plaintext", self._topic)
        client.subscribe(self._topic, qos=1)

    def _handle_disconnect(self, _client, _userdata, _flags, reason_code, _props=None):
        log.warning("disconnected from the broker: %s", reason_code)

    def _handle_message(self, _client, _userdata, message):
        # Stamped here rather than deeper in, so the value is as close to arrival
        # as possible: it is the fallback the whole timestamp rule leans on.
        received_at = datetime.now(timezone.utc)
        if self._on_message is not None:
            self._on_message(message.topic, message.payload, received_at)

    def run(self, on_message) -> None:
        self._on_message = on_message
        self._client.connect(self._host, self._port, keepalive=60)
        self._client.loop_forever(retry_first_connection=True)

    def stop(self) -> None:
        self._client.disconnect()
