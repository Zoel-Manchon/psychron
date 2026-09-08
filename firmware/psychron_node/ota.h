#pragma once

// Firmware updates pulled over mutual TLS from the same CA that guards the
// broker.
//
// Three separate things protect an update, and it is worth being clear about
// which covers what. mTLS proves the image came from our server and was not
// altered in flight. The SHA-256 in the manifest catches a truncated or corrupt
// transfer before anything is committed. Neither protects against the server
// itself being compromised — only a signature over the image would, and that is
// what Keystone exists to issue. Until then this is a trusted-server model, and
// saying so is better than implying otherwise.

namespace ota {

void begin();
void poll();               // checks on a schedule; does nothing most passes
const char *lastResult();  // short label for the panel and the boot record

}  // namespace ota
