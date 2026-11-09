#!/bin/bash
# Makes sure the Gradle distribution the wrapper needs is installed. services.gradle.org redirects to a
# GitHub release download, which has had outages (504 for many minutes); after a few tries the same zip
# is fetched from public mirrors and only used if its SHA-256 matches the official checksum.
set -u
version=8.14.3
sha256=bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531
# Folder the wrapper uses for services.gradle.org/distributions/gradle-8.14.3-bin.zip
dist="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/gradle-$version-bin/cv11ve7ro1n3o1j4so8xd9n66"

for i in 1 2 3; do
  ./gradlew --version && exit 0
  echo "Gradle download failed (attempt $i)"
  [ "$i" -lt 3 ] && sleep $((i * 30))
done

for url in \
  "https://mirrors.cloud.tencent.com/gradle/gradle-$version-bin.zip" \
  "https://mirrors.aliyun.com/gradle/distributions/v$version/gradle-$version-bin.zip" \
  "https://services.gradle.org/distributions/gradle-$version-bin.zip"; do
  echo "Trying $url"
  rm -f /tmp/gradle.zip
  curl -fsSL --retry 3 --max-time 600 -o /tmp/gradle.zip "$url" || continue
  if ! echo "$sha256  /tmp/gradle.zip" | sha256sum -c -; then
    echo "Checksum mismatch, ignoring this copy"
    continue
  fi
  rm -rf "$dist" && mkdir -p "$dist"
  unzip -q /tmp/gradle.zip -d "$dist" && touch "$dist/gradle-$version-bin.zip.ok"
  ./gradlew --version && exit 0
done
echo "Could not get Gradle $version from any source"
exit 1
