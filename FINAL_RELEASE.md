# Cubical Compare 2.0.4 — WatchData Fidelity Hotfix

Cubical Compare 2.0.4 corrects the visible renderer drift found by comparing a real Android export against the supplied 1920x1080 60 FPS WatchData-style reference at identical source frames.

Release channel: `release/cubical-compare-final`.
Renderer visual-audit baseline: `56824d34ab99c37bf9a7869a3b15ab57809a2e74`.

## 2.0.4 renderer corrections

- The exact decoded card-conveyor positions remain unchanged. 2.0.4 corrects the rendered appearance around that measured timeline instead of replacing it with generic easing.
- The settled active badge now uses the measured 298x344 WatchData contour and measured opening-card scale hierarchy.
- Opening badge ingress receives source-frame contour corrections while preserving the existing measured skew/rotation path.
- Later badge fall remains source-frame driven; its gloss clock now follows the observed settle-and-sweep interval.
- Settled and older badges return to flat red after the moving gloss passes instead of retaining a diagonal white stripe.
- Badge text sizing, shadow strength and two-line value layout were re-measured against the reference.
- WatchData-authored title line breaks are preserved, and title size is chosen by the measured line-width target instead of one fixed size.
- Description wrapping uses the narrower reference text measure so line breaks track the supplied video more closely.
- Credits typography and spacing were adjusted from rendered reference crops.

## WatchData-matched typography

The supplied video glyphs were visually matched to the Poppins family. 2.0.4 therefore bundles official SIL Open Font License Poppins files from the Google Fonts repository instead of depending on Android or Windows system fonts.

- Main badge values: Poppins Bold.
- Badge qualifiers such as `YEARS AGO`: Poppins SemiBold.
- Card titles: Poppins SemiBold.
- Descriptions/supporting text: Poppins Medium.

Builds fetch the official font files and verify their Git blob SHA-1 values before packaging. Font binaries are not committed to the CTS source repository. The SIL OFL license notice is bundled with the renderer.

## Cross-platform renderer contract

Windows and Android use byte-identical `ccengine` renderer source trees. Android still runs the same Python renderer through Chaquopy rather than using a second Kotlin animation implementation.

CI renders representative frames from the opening, settled opening-card hierarchy, continuous transition and later badge shine windows before packaging. It also rejects the release if the renderer differs from the reviewed 2.0.4 visual-audit baseline.

## Android fixes retained

- Play and Stop preview controls from 2.0.3 remain available.
- Preview playback remains clocked to real video time and may skip preview-only frames on slower devices; final export still renders every output frame.
- MegaPack import remains off the activity/Compose thread and processes artwork one card at a time.
- Background export remains a persistent `mediaProcessing` foreground service with wake lock, notification progress, cancellation, screen-off support and service recreation support.
- Default exported filename is `Cubical-Compare-2.0.4.mp4`.

## Windows

The rebuilt native Windows studio remains the desktop shell. It uses the same final 2.0.4 renderer and bundled Poppins assets as Android.

## Android signing

The release pipeline uses a permanent Android release identity whenever one is configured through private repository secrets and verifies its expected certificate fingerprint. When those private secrets are absent, CI signs the APK with its installable fallback identity. The certificate SHA-256 fingerprint and signing mode are shipped beside the APK as `Cubical-Compare-2.0.4-Android.signing.txt`; no private key is committed or published.

## Release gates

CI rejects the release if:

- `engine/ccengine` differs from the reviewed 2.0.4 renderer baseline.
- the Android renderer copy differs byte-for-byte from the desktop `ccengine` tree.
- the required Poppins font assets cannot be fetched or fail hash verification.
- the required Poppins font assets are missing from the built package.
- the WatchData renderer source does not compile.
- renderer regression tests fail.
- the Windows private renderer or native-shell self-test fails.
- Android unit tests or release assembly fail.
- the Android APK is not cryptographically signed.
- a configured permanent Android release identity has the wrong certificate fingerprint.
