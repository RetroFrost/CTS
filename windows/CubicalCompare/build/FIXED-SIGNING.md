# Fixed Windows signing identity

Cubical Compare Windows releases use one pinned self-signed code-signing certificate rather than creating a new certificate in each GitHub Actions run.

- Subject: `CN=RetroFrost Development`
- SHA-1 thumbprint: `548F320EFE4885F54B93F254606BB723DB37FF99`
- SHA-256 certificate fingerprint: `AD8A676C5791512696CA72791F13D6F1DD05E022F3E5F0762B68FE08B4AF1F36`
- Valid through: `9999-12-31 20:53:51 UTC`

X.509 certificates require a finite `NotAfter` value, so 31 December 9999 is used as the practical equivalent of a permanently valid signing certificate.

The private key must never be committed to this repository. GitHub Actions expects these repository secrets:

- `WINDOWS_SIGNING_PFX_BASE64` — Base64 of the password-protected PFX.
- `WINDOWS_SIGNING_PFX_PASSWORD` — PFX password.

The build imports the PFX into the runner's Current User certificate store, verifies the certificate subject and pinned thumbprint, signs the MSIX by thumbprint, then verifies the produced package is signed by that same thumbprint. The release ZIP contains only the public `.cer` so the installer can trust the fixed signer on a user's PC.

The shipped installer also pins the same thumbprint before importing the public certificate or installing the MSIX. This prevents an accidental replacement certificate with the same subject from being accepted.

## Rotation

Do not rotate this certificate during normal releases. A rotation is a deliberate migration because existing installations trust the current signer. Before rotation, create the replacement certificate, update the pinned thumbprint and secrets together, and ship a transition installer that can trust the replacement signer.
