#!/usr/bin/env bash
set -euo pipefail

: "${GH_TOKEN:?GitHub token is required}"
: "${GITHUB_REPOSITORY:?Repository is required}"
: "${GITHUB_SHA:?Build commit is required}"
: "${GITHUB_REF:?Build branch is required}"
: "${GITHUB_RUN_ID:?Workflow run ID is required}"
: "${GITHUB_RUN_NUMBER:?Workflow run number is required}"
: "${GITHUB_STEP_SUMMARY:?Workflow summary path is required}"
: "${APK_VERSION:?APK version is required}"
: "${APK_VERSION_CODE:?APK version code is required}"

if [ "$GITHUB_REPOSITORY" != 'xbghc/rikkahub-gpt-live' ] || [ "$GITHUB_REF" != 'refs/heads/gpt-live' ]; then
  echo '::error::GPT Live updates can only be published from xbghc/rikkahub-gpt-live:gpt-live.'
  exit 1
fi

if [[ ! "$GITHUB_SHA" =~ ^[0-9a-f]{40}$ || ! "$APK_VERSION" =~ ^[a-zA-Z0-9._+-]+$ || ! "$APK_VERSION_CODE" =~ ^[1-9][0-9]*$ ]]; then
  echo '::error::Invalid APK version metadata.'
  exit 1
fi

notes_file="$PWD/docs/releases/$APK_VERSION.md"
cd release
tag="gpt-live-debug-$GITHUB_RUN_ID"
asset="rikkahub-$APK_VERSION-arm64-v8a-debug.apk"
mv app-github-arm64-v8a-debug.apk "$asset"
sha256sum "$asset" > SHA256SUMS
release_url="https://github.com/$GITHUB_REPOSITORY/releases/tag/$tag"

if [ -f "$notes_file" ]; then
  cat "$notes_file" > release-notes.md
  printf '\n\n' >> release-notes.md
else
  : > release-notes.md
fi

cat >> release-notes.md <<EOF
GPT Live 自动预发布，适用于 arm64 Android 手机。

- 版本：$APK_VERSION（versionCode $APK_VERSION_CODE）
- 源码提交：$GITHUB_SHA
- 构建与验证：https://github.com/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID
- 格式、单元测试、语音界面测试及 APK 签名检查均已通过。
- 沿用已发布 Debug APK 的签名，可覆盖安装；包名为 me.rerere.rikkahub.debug。

下载附件中的 APK 即可安装；SHA256SUMS 用于校验下载文件。API Key 在 App 内配置。
EOF

# The app reads this manifest instead of the upstream update service or SemVer tags.
# Publish it in the same draft as its verified APK, so a partially uploaded release
# can never be offered as an in-app update.
jq -n \
  --arg branch 'gpt-live' \
  --arg releaseTag "$tag" \
  --arg sourceCommit "$GITHUB_SHA" \
  --arg version "$APK_VERSION" \
  --argjson versionCode "$APK_VERSION_CODE" \
  --arg applicationId 'me.rerere.rikkahub.debug' \
  --arg signingCertificateSha256 '6c2d6dfdac13e1901cbec04feba5f10271747230ce21010f5d4e4608658b9c07' \
  --rawfile changelog release-notes.md \
  --arg name "$asset" \
  --arg url "https://github.com/$GITHUB_REPOSITORY/releases/download/$tag/$asset" \
  --arg sha256 "$(awk '{print $1}' SHA256SUMS)" \
  --argjson size "$(stat -c '%s' "$asset")" \
  '{schemaVersion: 1, $branch, $releaseTag, $sourceCommit, $version, $versionCode,
    $applicationId, $signingCertificateSha256, $changelog,
    downloads: [{abi: "arm64-v8a", $name, $url, $sha256, $size}]}' > update.json

# Keep a failed upload private as a draft. Retries can finish that same draft,
# while an already published release and its assets remain unchanged.
if gh release view "$tag" --repo "$GITHUB_REPOSITORY" \
  --json isDraft,isPrerelease,targetCommitish,assets > existing-release.json; then
  jq -e --arg sha "$GITHUB_SHA" \
    '.targetCommitish == $sha and .isPrerelease' existing-release.json > /dev/null
  if jq -e '.isDraft == false' existing-release.json > /dev/null; then
    jq -e --arg asset "$asset" \
      '([.assets[].name] | index($asset) != null and index("SHA256SUMS") != null and index("update.json") != null)' \
      existing-release.json > /dev/null
    echo "Published prerelease already exists: $release_url"
    echo "[Download GPT Live APK]($release_url)" >> "$GITHUB_STEP_SUMMARY"
    exit 0
  fi
else
  create_release=true
fi

# All new publications must advance Android's versionCode. Comparing the newest
# published channel manifest keeps this invariant across retries and future builds.
gh api --paginate --slurp "repos/$GITHUB_REPOSITORY/releases?per_page=100" > published-releases.json
previous_tag="$(jq -er --arg tag "$tag" '
  [.[][] | select(.draft == false and .tag_name != $tag)
    | select(.tag_name | test("^gpt-live-debug-[0-9]+$"))
    | select(any(.assets[]; .name == "update.json" and .state == "uploaded"))]
  | sort_by(.published_at) | last | .tag_name // ""' published-releases.json)"
if [ -n "$previous_tag" ]; then
  gh release download "$previous_tag" --repo "$GITHUB_REPOSITORY" \
    --pattern update.json --output previous-update.json
  jq -e --argjson next "$APK_VERSION_CODE" '
    .schemaVersion == 1 and .branch == "gpt-live" and
    (.versionCode | type == "number") and .versionCode > 0 and .versionCode < $next' \
    previous-update.json > /dev/null || {
      echo '::error::The APK versionCode must exceed the latest published GPT Live update.'
      exit 1
    }
fi

if [ "${create_release:-false}" = 'true' ]; then
  gh release create "$tag" --repo "$GITHUB_REPOSITORY" \
    --target "$GITHUB_SHA" --title "$APK_VERSION · build $GITHUB_RUN_NUMBER" \
    --notes-file release-notes.md --draft --prerelease --latest=false
fi

gh release upload "$tag" "$asset" SHA256SUMS update.json --repo "$GITHUB_REPOSITORY" --clobber
gh release edit "$tag" --repo "$GITHUB_REPOSITORY" --draft=false --prerelease --latest=false
echo "[Download GPT Live APK]($release_url)" >> "$GITHUB_STEP_SUMMARY"
