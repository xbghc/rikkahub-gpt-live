#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_HOME:?Android SDK is required}"
: "${DEBUG_SIGNING_CERT_SHA256:?Expected signing certificate is required}"
: "${RIKKAHUB_DEBUG_KEYSTORE:?Explicit CI signing key is required}"
keystore_sha256="$(keytool -exportcert -keystore "$RIKKAHUB_DEBUG_KEYSTORE" \
  -storepass android -alias androiddebugkey | sha256sum | awk '{print $1}')"
if [[ "$keystore_sha256" != "$DEBUG_SIGNING_CERT_SHA256" ]]; then
  echo "::error::The configured CI Debug signing key no longer matches the published certificate."
  exit 1
fi
shopt -s nullglob
apks=(app/build/outputs/apk/github/debug/*-debug.apk)
if [[ "${#apks[@]}" -eq 0 ]]; then
  echo '::error::No Debug APKs found for signing verification.'
  exit 1
fi
for apk in "${apks[@]}"; do
  verification_output="$("$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --verbose --print-certs "$apk")"
  # Build Tools 37 labels signers by signature scheme; older tools use numbers or SDK ranges.
  certificate_sha256="$(printf '%s\n' "$verification_output" |
    awk '/^(Signer|V[0-9.]+ Signer).* certificate SHA-256 digest:/ {print tolower($NF)}' | sort -u)"
  if [[ "$certificate_sha256" != "$DEBUG_SIGNING_CERT_SHA256" ]]; then
    printf '%s\n' "$verification_output"
    echo "::error::APK signing certificate mismatch for $apk: expected $DEBUG_SIGNING_CERT_SHA256, found $certificate_sha256."
    exit 1
  fi
  echo "Verified $apk: $certificate_sha256"
done
