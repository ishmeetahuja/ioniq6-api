#!/usr/bin/env bash
# Build the public iOS app and upload the IPA to TestFlight.
# Reads signing material and the App Store Connect API key from the environment only.
# Refuses a provisioning profile whose bundle id is not config/public.properties.
#
# Required:
#   ASC_KEY_ID ASC_ISSUER_ID ASC_KEY_P8_BASE64
#   APPLE_TEAM_ID IOS_DIST_CERT_P12_BASE64 IOS_DIST_CERT_PASSWORD IOS_PROFILE_BASE64
# Optional:
#   IOS_PROJECT  (default ios/WalkawayLock.xcodeproj)
#   IOS_SCHEME   (default iosScheme in config/public.properties)

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

prop() {
  local key=$1
  grep "^${key}=" config/public.properties | cut -d= -f2- | tr -d '[:space:]'
}

BUNDLE_ID=$(prop applicationId)
SCHEME=${IOS_SCHEME:-$(prop iosScheme)}
PROJECT=${IOS_PROJECT:-ios/WalkawayLock.xcodeproj}

[ -n "$BUNDLE_ID" ] || { echo "applicationId missing from config/public.properties"; exit 1; }
[ -n "$SCHEME" ] || { echo "iosScheme missing from config/public.properties"; exit 1; }
[ -d "$PROJECT" ] || { echo "No Xcode project at $PROJECT"; exit 1; }

: "${ASC_KEY_ID:?ASC_KEY_ID is required}"
: "${ASC_ISSUER_ID:?ASC_ISSUER_ID is required}"
: "${ASC_KEY_P8_BASE64:?ASC_KEY_P8_BASE64 is required}"
: "${APPLE_TEAM_ID:?APPLE_TEAM_ID is required}"
: "${IOS_DIST_CERT_P12_BASE64:?IOS_DIST_CERT_P12_BASE64 is required}"
: "${IOS_DIST_CERT_PASSWORD:?IOS_DIST_CERT_PASSWORD is required}"
: "${IOS_PROFILE_BASE64:?IOS_PROFILE_BASE64 is required}"

WORKDIR=$(mktemp -d)
cleanup() { rm -rf "$WORKDIR"; }
trap cleanup EXIT

# GNU coreutils accepts --decode. The macOS base64 used by GitHub's macOS runners accepts -D.
b64dec() {
  if base64 --help >/dev/null 2>&1; then
    base64 --decode
  else
    base64 -D
  fi
}

b64dec > "$WORKDIR/AuthKey.p8" <<<"$ASC_KEY_P8_BASE64"
b64dec > "$WORKDIR/dist.p12" <<<"$IOS_DIST_CERT_P12_BASE64"
b64dec > "$WORKDIR/profile.mobileprovision" <<<"$IOS_PROFILE_BASE64"

security cms -D -i "$WORKDIR/profile.mobileprovision" > "$WORKDIR/profile.plist"
PROFILE_NAME=$(/usr/libexec/PlistBuddy -c 'Print Name' "$WORKDIR/profile.plist")
PROFILE_UUID=$(/usr/libexec/PlistBuddy -c 'Print UUID' "$WORKDIR/profile.plist")
APP_ID=$(/usr/libexec/PlistBuddy -c 'Print Entitlements:application-identifier' "$WORKDIR/profile.plist")
case "$APP_ID" in
  *."$BUNDLE_ID") ;;
  *)
    echo "Provisioning profile is for ${APP_ID}, which is not the public id ${BUNDLE_ID}."
    echo "Create the App Store profile for ${BUNDLE_ID}. Do not reuse a personal app's profile."
    exit 1
    ;;
esac

mkdir -p "$HOME/Library/MobileDevice/Provisioning Profiles"
cp "$WORKDIR/profile.mobileprovision" "$HOME/Library/MobileDevice/Provisioning Profiles/${PROFILE_UUID}.mobileprovision"

KEYCHAIN="$WORKDIR/build.keychain-db"
KEYCHAIN_PASSWORD=$(openssl rand -hex 16)
security create-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN"
security set-keychain-settings -lut 21600 "$KEYCHAIN"
security unlock-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN"
security import "$WORKDIR/dist.p12" -k "$KEYCHAIN" -P "$IOS_DIST_CERT_PASSWORD" -T /usr/bin/codesign -T /usr/bin/security
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KEYCHAIN_PASSWORD" "$KEYCHAIN" >/dev/null
security list-keychains -d user -s "$KEYCHAIN"

ARCHIVE="$WORKDIR/WalkawayLock.xcarchive"
xcodebuild \
  -project "$PROJECT" \
  -scheme "$SCHEME" \
  -configuration Release \
  -destination "generic/platform=iOS" \
  -archivePath "$ARCHIVE" \
  archive \
  CODE_SIGN_STYLE=Manual \
  DEVELOPMENT_TEAM="$APPLE_TEAM_ID" \
  PRODUCT_BUNDLE_IDENTIFIER="$BUNDLE_ID" \
  PROVISIONING_PROFILE_SPECIFIER="$PROFILE_NAME"

cat > "$WORKDIR/ExportOptions.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key>
  <string>app-store-connect</string>
  <key>teamID</key>
  <string>${APPLE_TEAM_ID}</string>
  <key>signingStyle</key>
  <string>manual</string>
  <key>provisioningProfiles</key>
  <dict>
    <key>${BUNDLE_ID}</key>
    <string>${PROFILE_NAME}</string>
  </dict>
  <key>uploadSymbols</key>
  <true/>
</dict>
</plist>
EOF

xcodebuild -exportArchive \
  -archivePath "$ARCHIVE" \
  -exportPath "$WORKDIR/export" \
  -exportOptionsPlist "$WORKDIR/ExportOptions.plist"

shopt -s nullglob
ipas=("$WORKDIR/export/"*.ipa)
[ ${#ipas[@]} -eq 1 ] || { echo "Expected one IPA, found ${#ipas[@]}"; exit 1; }

export ASC_KEY_PATH="$WORKDIR/AuthKey.p8"
export IPA_PATH="${ipas[0]}"
gem install fastlane --no-document
fastlane ios upload
