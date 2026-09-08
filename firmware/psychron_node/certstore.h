#pragma once
#include <stddef.h>   // size_t; stdint.h alone does not declare it
#include <stdint.h>

// The node's TLS material, kept in NVS rather than compiled into the binary.
//
// A key in a header is a key in the build output, in the backup of the build
// output, and in anyone's hands who can read the flash over the serial port. NVS
// is not a secure element and does not fix that on its own — only flash
// encryption does — but it keeps the private key out of the source tree, out of
// git, and out of every binary that gets copied around, which is the part that
// leaks in practice.
//
// Material arrives over the serial provisioning channel; see tools/provision.py.

namespace certstore {

bool begin();          // loads whatever is present; false if NVS is unavailable
bool complete();       // all three slots hold something

const char *ca();      // CA that signs the broker, PEM, never null once complete
const char *cert();    // this node's certificate, PEM
const char *key();     // this node's private key, PEM

bool store(const char *slot, const uint8_t *pem, size_t len);
void erase();

}  // namespace certstore
