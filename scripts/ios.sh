#!/bin/sh
# OKAY-REMOTE FOR AN iPHONE (specs/ios.md): `scripts/ios.sh [lib|app|ipa]`
#   lib  the phone's Scala linked twice (Scala Native: the simulator, a device), into ios/lib/OkayRemote.xcframework
#   app  lib, then the Xcode project (xcodegen) and the app built for the simulator
#   ipa  lib, then the app archived for a device UNSIGNED, as dist/okay-remote.ipa — SideStore signs it with your
#        Apple ID when it installs it (a free one will do)
# Needs Xcode, xcodegen, and okay-faces checked out beside this repository (../okay-faces: its SwiftUI shell).
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
what=${1:-ipa}
cd "$here"
work=$(mktemp -d)
for target in simulator device; do
  # a target's objects its own: Scala Native would take the other's from its last link, `ar` add to its archive
  find phone -path "*/.native/target/*/native" -type d -prune -exec rm -rf {} +
  find phone -path "*/.native/target/*" -name "lib*.a" -delete
  OKAY_IOS_TARGET=$target sbt -batch -error "phoneNative/nativeLink" | grep -v emit-static-lib || true
  lib=$(ls -t $(find phone -path "*/.native/target/*" -name "lib*.a") | head -1)
  mkdir -p "$work/$target" && cp "$lib" "$work/$target/libOkayRemote.a"
done
mkdir -p ios/lib && rm -rf ios/lib/OkayRemote.xcframework
xcodebuild -create-xcframework -library "$work/simulator/libOkayRemote.a" -library "$work/device/libOkayRemote.a" \
  -output ios/lib/OkayRemote.xcframework >/dev/null
rm -rf "$work"
echo "ios/lib/OkayRemote.xcframework"
[ "$what" = lib ] && exit 0
(cd ios && xcodegen -q)
if [ "$what" = app ]; then
  xcodebuild -project ios/OkayRemote.xcodeproj -scheme OkayRemote -sdk iphonesimulator \
    -destination 'generic/platform=iOS Simulator' -derivedDataPath ios/build build -quiet
  echo "ios/build/Build/Products/Debug-iphonesimulator/OkayRemote.app"
  exit 0
fi
rm -rf ios/build/OkayRemote.xcarchive
xcodebuild -project ios/OkayRemote.xcodeproj -scheme OkayRemote -sdk iphoneos -configuration Release \
  -destination 'generic/platform=iOS' -archivePath ios/build/OkayRemote.xcarchive archive \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" -quiet
pack=$(mktemp -d)
mkdir -p "$pack/Payload" dist
cp -R ios/build/OkayRemote.xcarchive/Products/Applications/OkayRemote.app "$pack/Payload/"
rm -f dist/okay-remote.ipa
(cd "$pack" && zip -qry "$here/dist/okay-remote.ipa" Payload)
rm -rf "$pack"
echo "dist/okay-remote.ipa"
