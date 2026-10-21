#!/usr/bin/env bash
set -euo pipefail

: "${GH_TOKEN:?GitHub token is required}"
: "${GITHUB_REPOSITORY:?Repository is required}"
: "${GITHUB_SHA:?Build commit is required}"
: "${GITHUB_RUN_ID:?Workflow run ID is required}"
: "${GITHUB_RUN_NUMBER:?Workflow run number is required}"
: "${GITHUB_STEP_SUMMARY:?Workflow summary path is required}"
: "${APK_VERSION:?APK version is required}"
: "${APK_VERSION_CODE:?APK version code is required}"

if [[ ! "$APK_VERSION" =~ ^[a-zA-Z0-9._+-]+$ || ! "$APK_VERSION_CODE" =~ ^[0-9]+$ ]]; then
  echo '::error::Invalid APK version metadata.'
  exit 1
fi

cd release
tag="gpt-live-debug-$GITHUB_RUN_ID"
asset="rikkahub-$APK_VERSION-arm64-v8a-debug.apk"
mv app-github-arm64-v8a-debug.apk "$asset"
sha256sum "$asset" > SHA256SUMS
release_url="https://github.com/$GITHUB_REPOSITORY/releases/tag/$tag"

cat > release-notes.md <<EOF
GPT Live 自动预发布，适用于 arm64 Android 手机。

- 版本：$APK_VERSION（versionCode $APK_VERSION_CODE）
- 源码提交：$GITHUB_SHA
- 构建与验证：https://github.com/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID
- 格式、单元测试、语音界面测试及 APK 签名检查均已通过。
- 沿用已发布 Debug APK 的签名，可覆盖安装；包名为 me.rerere.rikkahub.debug。

下载附件中的 APK 即可安装；SHA256SUMS 用于校验下载文件。API Key 在 App 内配置。
EOF

# Keep a failed upload private as a draft. Retries can finish that same draft,
# while an already published release and its assets remain unchanged.
if gh release view "$tag" --repo "$GITHUB_REPOSITORY" \
  --json isDraft,isPrerelease,targetCommitish,assets > existing-release.json; then
  jq -e --arg sha "$GITHUB_SHA" \
    '.targetCommitish == $sha and .isPrerelease' existing-release.json > /dev/null
  if jq -e '.isDraft == false' existing-release.json > /dev/null; then
    jq -e --arg asset "$asset" \
      '([.assets[].name] | index($asset) != null and index("SHA256SUMS") != null)' \
      existing-release.json > /dev/null
    echo "Published prerelease already exists: $release_url"
    echo "[Download GPT Live APK]($release_url)" >> "$GITHUB_STEP_SUMMARY"
    exit 0
  fi
else
  gh release create "$tag" --repo "$GITHUB_REPOSITORY" \
    --target "$GITHUB_SHA" --title "$APK_VERSION · build $GITHUB_RUN_NUMBER" \
    --notes-file release-notes.md --draft --prerelease --latest=false
fi

gh release upload "$tag" "$asset" SHA256SUMS --repo "$GITHUB_REPOSITORY" --clobber
gh release edit "$tag" --repo "$GITHUB_REPOSITORY" --draft=false --prerelease --latest=false
echo "[Download GPT Live APK]($release_url)" >> "$GITHUB_STEP_SUMMARY"
