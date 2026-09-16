# Fixed Windows signing identity

Cubical Compare Windows releases use one pinned self-signed code-signing certificate rather than creating a new certificate in each GitHub Actions run.

- Subject: `CN=RetroFrost Development`
- SHA-1 thumbprint: `52C53117BB543E6D5FE401F850CA0A9197948263`
- SHA-256 certificate fingerprint: `115A7C78B7B4C4253B5F1CBC35B47EC452FDFF9DBF5C79E848C29CBB30922B34`
- Valid through: `2036-09-13 20:39:24 UTC`

The private key must never be committed to this repository. GitHub Actions expects these repository secrets:

- `WINDOWS_SIGNING_PFX_BASE64` — Base64 of the password-protected PFX.
- `WINDOWS_SIGNING_PFX_PASSWORD` — PFX password.

The build imports the PFX into the runner's Current User certificate store, verifies the certificate subject and pinned thumbprint, signs the MSIX by thumbprint, then verifies the produced package is signed by that same thumbprint. The release ZIP contains only the public `.cer` so the installer can trust the fixed signer on a user's PC.

The shipped installer also pins the same thumbprint before importing the public certificate or installing the MSIX. This prevents an accidental replacement certificate with the same subject from being accepted.

## Rotation

Do not rotate this certificate during normal releases. A rotation is a deliberate migration because existing installations trust the current signer. Before rotation, create the replacement certificate, update the pinned thumbprint and secrets together, and ship a transition installer that can trust the replacement signer.
