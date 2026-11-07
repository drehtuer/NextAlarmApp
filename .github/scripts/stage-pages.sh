#!/usr/bin/env bash
# Puts the documentation site's sources into one directory for Jekyll: README.md becomes the start page,
# docs/*.md sit next to it, and links that leave docs/ point to the file on GitHub at the given ref.
#
# Usage: stage-pages.sh <output directory> <repository URL> <ref>
# Example: stage-pages.sh _pages https://github.com/drehtuer/NextAlarmApp v1.0.0
set -euo pipefail

out=$1
blob="$2/blob/$3"
root=$(cd "$(dirname "$0")/../.." && pwd)

rm -rf "$out"
mkdir -p "$out"
cp -r "$root/.github/pages/." "$out/"
cp "$root"/docs/*.md "$out/"
cp "$root/README.md" "$out/index.md"

# In README.md: a relative link to a top-level file (LICENSE) leaves the site, and docs/x.md is now next to it.
# This order matters: the first pattern skips links with a "/", so it leaves docs/x.md alone.
sed -i -E -e "s#\]\(([^):/\#]+)\)#](${blob}/\1)#g" -e 's#\]\(docs/#](#g' "$out/index.md"
# In docs/: ../README.md is now index.md, and every other ../ link leaves the site.
for page in "$root"/docs/*.md; do
    sed -i -e 's#](\.\./README\.md#](index.md#g' -e "s#](\.\./#](${blob}/#g" "$out/$(basename "$page")"
done
