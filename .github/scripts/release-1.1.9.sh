#!/usr/bin/env bash
set -euo pipefail

repo=LernardDranrel/LerNET
tag=v1.1.9
directory="uploads/$tag"
manifest=SHA256SUMS-1.1.9.txt
apk=LerNET-1.1.9-android.apk
assets=(LerNET-1.1.9-install.exe LerNET-1.1.9-windows-portable.zip)
phase=$(< release-phase.txt)
fail() { echo "$*" >&2; exit 1; }

case "$phase" in
    prepare|draft|publish) ;;
    *) fail "Expected prepare, draft or publish in release-phase.txt" ;;
esac
if [[ -f "$directory/$apk" ]]; then
    assets+=("$apk")
elif [[ "$phase" == publish ]]; then
    fail "Public v1.1.9 requires the verified Android APK"
fi

tmp=$(mktemp -d)
trap 'rm -rf -- "$tmp"' EXIT
printf '%s\n' "${assets[@]}" | LC_ALL=C sort > "$tmp/package-names"
awk 'length($1) != 64 || $1 !~ /^[0-9a-f]+$/ || NF != 2 {exit 1} {print $2}' "$directory/$manifest" \
    | LC_ALL=C sort > "$tmp/manifest-names"
diff -u "$tmp/package-names" "$tmp/manifest-names"
(cd "$directory" && sha256sum --strict -c "$manifest")
[[ -s "$directory/RELEASE_NOTES.md" ]] || fail "Release notes are empty"
if [[ "$phase" == prepare ]]; then
    echo "Local packages verified for $tag."
    exit 0
fi

[[ "${GITHUB_REPOSITORY:-}" == "$repo" ]] || fail "Unexpected release repository"
commit=$(< release-commit.txt)
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || fail "Provide the full release source commit in release-commit.txt"
verify_tag() {
    local refs resolved
    refs=$(git ls-remote --exit-code origin "refs/tags/$tag" "refs/tags/$tag^{}")
    resolved=$(awk -v ref="refs/tags/$tag" '$2 == ref {base=$1} $2 == ref "^{}" {peeled=$1} END {print (peeled ? peeled : base)}' <<< "$refs")
    [[ "$resolved" == "$commit" ]] || fail "$tag resolves to $resolved; expected source commit $commit"
}
release_id() {
    gh api --paginate "repos/$repo/releases?per_page=100" --jq '.[] | select(.tag_name == "v1.1.9") | .id'
}
is_draft() { gh api "repos/$repo/releases/$1" --jq '.draft'; }
verify_tag
id=$(release_id)
if [[ -z "$id" ]]; then
    [[ "$phase" == draft ]] || fail "The draft must be staged before publication"
    gh release create "$tag" --repo "$repo" --verify-tag --target "$commit" --draft \
        --title "LerNET 1.1.9" --notes-file "$directory/RELEASE_NOTES.md"
    id=$(release_id)
fi
[[ "$id" =~ ^[0-9]+$ ]] || fail "Could not identify exactly one release for $tag"

if [[ "$phase" == draft ]]; then
    [[ "$(is_draft "$id")" == true ]] || fail "Public release already exists for $tag"
    gh release edit "$tag" --repo "$repo" --title "LerNET 1.1.9" --notes-file "$directory/RELEASE_NOTES.md"
    files=()
    for name in "${assets[@]}" "$manifest"; do files+=("$directory/$name"); done
    gh release upload "$tag" "${files[@]}" --repo "$repo" --clobber
fi

printf '%s\n' "${assets[@]}" "$manifest" | LC_ALL=C sort > "$tmp/expected-assets"
gh api --paginate "repos/$repo/releases/$id/assets?per_page=100" --jq '.[].name' | LC_ALL=C sort > "$tmp/remote-assets"
diff -u "$tmp/expected-assets" "$tmp/remote-assets"
notes=$(gh api "repos/$repo/releases/$id" --jq '.body')
[[ "${notes//$'\r'/}" == "$(< "$directory/RELEASE_NOTES.md")" ]] || fail "Release notes differ from the reviewed text"
mkdir "$tmp/download"
gh release download "$tag" --repo "$repo" --dir "$tmp/download" --pattern '*'
cmp "$directory/$manifest" "$tmp/download/$manifest"
(cd "$tmp/download" && sha256sum --strict -c "$manifest")

if [[ "$phase" == publish ]]; then
    status=$(is_draft "$id")
    case "$status" in
        true)
            verify_tag
            gh release edit "$tag" --repo "$repo" --draft=false ;;
        false) ;; # An identical published release is a successful retry.
        *) fail "Unknown release state for $tag" ;;
    esac
    [[ "$(is_draft "$id")" == false ]] || fail "Release is still a draft"
fi
url=$(gh api "repos/$repo/releases/$id" --jq '.html_url')
echo "$phase: $url"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    printf '[LerNET %s](%s) · %s\n' "$tag" "$url" "$phase" >> "$GITHUB_STEP_SUMMARY"
fi
