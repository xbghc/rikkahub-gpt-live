#!/usr/bin/env bash
set -euo pipefail
umask 077

: "${SIGNING_KEY_MODE:?Signing mode is required}"
: "${RUNNER_TEMP:?Runner temporary directory is required}"
: "${GITHUB_ENV:?GitHub environment file is required}"

signing_directory="$RUNNER_TEMP/rikkahub-signing"
mkdir -p "$signing_directory"
signing_key="$signing_directory/debug.keystore"

case "$SIGNING_KEY_MODE" in
  published)
    : "${PUBLISHED_DEBUG_SIGNING_CERT_SHA256:?Published signing certificate is required}"
    if [[ -z "${RIKKAHUB_DEBUG_KEYSTORE_BASE64:-}" ]]; then
      echo '::error::The original Debug signing key secret is missing. Restore the verified original key; do not generate a replacement.'
      exit 1
    fi
    printf '%s' "$RIKKAHUB_DEBUG_KEYSTORE_BASE64" | base64 --decode > "$signing_key"
    ;;
  pull-request)
    # This throwaway key validates PR builds without exposing the installed app's key.
    keytool -genkeypair -noprompt -keystore "$signing_key" \
      -storepass android -keypass android -alias androiddebugkey \
      -dname 'CN=Pull Request, O=RikkaHub, C=US' -keyalg RSA -keysize 2048 -validity 2
    ;;
  *)
    echo '::error::Unknown Debug signing key mode.'
    exit 1
    ;;
esac
chmod 600 "$signing_key"

certificate_sha256="$(keytool -exportcert -keystore "$signing_key" \
  -storepass android -alias androiddebugkey | sha256sum | awk '{print $1}')"
if [[ "$SIGNING_KEY_MODE" == 'published' && "$certificate_sha256" != "$PUBLISHED_DEBUG_SIGNING_CERT_SHA256" ]]; then
  rm -f "$signing_key"
  echo '::error::The Debug signing key secret does not match the published APK certificate.'
  exit 1
fi

{
  echo "RIKKAHUB_DEBUG_KEYSTORE=$signing_key"
  echo "DEBUG_SIGNING_CERT_SHA256=$certificate_sha256"
} >> "$GITHUB_ENV"
echo "Verified $SIGNING_KEY_MODE Debug signing certificate: $certificate_sha256"
