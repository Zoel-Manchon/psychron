#pragma once

// Serial provisioning of the TLS material.
//
// Non-blocking on purpose: the node keeps reading the sensor and buffering to
// flash while it waits to be given certificates, so the minutes spent
// provisioning do not become a hole in the series.
//
// Line protocol, one command per line:
//   SET <ca|crt|key> <base64 of the PEM>   ->  OK <slot> <bytes>  |  ERR <reason>
//   STATUS                                 ->  PROVISION;ca=..;crt=..;key=..
//   ERASE                                  ->  OK erased
// See tools/provision.py for the other end.

namespace provisioning {

void poll();          // call every loop pass; returns immediately
void announce();      // print the current state, so a host knows what is missing

}  // namespace provisioning
