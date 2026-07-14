# CTS Flatpak packaging

The repository-root manifest is:

```text
io.github.retrofrost.CTS.yml
```

It packages CTS with the KDE 6.10 runtime, the PySide BaseApp, offline Python dependency sources, and the FFmpeg full extension.

## Build and install locally

Run these commands from the repository root:

```bash
flatpak install -y flathub org.flatpak.Builder

flatpak run org.flatpak.Builder \
  --force-clean \
  --sandbox \
  --user \
  --install \
  --install-deps-from=flathub \
  --ccache \
  --repo=repo \
  builddir \
  io.github.retrofrost.CTS.yml
```

Launch the installed build:

```bash
flatpak run io.github.retrofrost.CTS
```

## Lint before submission

```bash
flatpak run --command=flatpak-builder-lint \
  org.flatpak.Builder \
  manifest io.github.retrofrost.CTS.yml

flatpak run --command=flatpak-builder-lint \
  org.flatpak.Builder \
  repo repo
```

## Updating for a CTS release

The manifest pins the CTS source to a commit SHA. For every stable release:

1. Update the `commit` value in the `cts` source entry.
2. Update the newest release entry in the AppStream metadata.
3. Rebuild, run CTS, and lint both the manifest and repository.
4. Update dependency source URLs and hashes when dependency versions change.

## Flathub submission

For the initial app submission, copy the manifest and its referenced `packaging/flatpak` files into a branch based on the Flathub submission repository's `new-pr` branch, then open the submission pull request against `new-pr`.
