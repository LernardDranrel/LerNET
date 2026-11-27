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
    gh api graphql -f owner="${repo%/*}" -f repository="${repo#*/}" -f tag="$tag" \
        -f query='query($owner:String!,$repository:String!,$tag:String!) { repository(owner:$owner,name:$repository) { release(tagName:$tag) { databaseId } } }' \
        --jq '.data.repository.release.databaseId // empty'
}
is_draft() { gh api "repos/$repo/releases/$1?check=$RANDOM" --jq '.draft'; }
verify_tag
id=$(release_id)
if [[ -z "$id" ]]; then
    [[ "$phase" == draft ]] || fail "The draft must be staged before publication"
    id=$(gh api --method POST "repos/$repo/releases" -f tag_name="$tag" -f target_commitish="$commit" \
        -f name="LerNET 1.1.9" -F "body=@$directory/RELEASE_NOTES.md" -F draft=true --jq '.id')
fi
[[ "$id" =~ ^[0-9]+$ ]] || fail "Could not identify exactly one release for $tag"
endpoint="repos/$repo/releases/$id"

if [[ "$phase" == draft ]]; then
    [[ "$(is_draft "$id")" == true ]] || fail "Public release already exists for $tag"
    gh api --method PATCH "$endpoint" -f tag_name="$tag" -f target_commitish="$commit" \
        -f name="LerNET 1.1.9" -F "body=@$directory/RELEASE_NOTES.md" --silent
    gh api "$endpoint/assets?per_page=100&check=$RANDOM" > "$tmp/existing-assets"
    for name in "${assets[@]}" "$manifest"; do
        previous=$(jq -r --arg name "$name" '.[] | select(.name == $name) | .digest' "$tmp/existing-assets")
        if [[ -n "$previous" ]]; then
            expected=$(sha256sum "$directory/$name" | cut -d' ' -f1)
            [[ "$previous" == "sha256:$expected" ]] || fail "Draft already contains a different $name"
            continue
        fi
        curl --fail-with-body --silent --show-error --output "$tmp/upload" \
            --header "Authorization: Bearer $GH_TOKEN" --header 'Content-Type: application/octet-stream' \
            --data-binary "@$directory/$name" "https://uploads.github.com/repos/$repo/releases/$id/assets?name=$name"
        jq -e --arg name "$name" '.state == "uploaded" and .name == $name' "$tmp/upload" > /dev/null
    done
fi

printf '%s\n' "${assets[@]}" "$manifest" | LC_ALL=C sort > "$tmp/expected-assets"
gh api "$endpoint/assets?per_page=100&check=$RANDOM" > "$tmp/asset-list"
jq -r '.[].name' "$tmp/asset-list" | LC_ALL=C sort > "$tmp/remote-assets"
diff -u "$tmp/expected-assets" "$tmp/remote-assets"
notes=$(gh api "$endpoint?check=$RANDOM" --jq '.body')
[[ "${notes//$'\r'/}" == "$(< "$directory/RELEASE_NOTES.md")" ]] || fail "Release notes differ from the reviewed text"
mkdir "$tmp/download"
while IFS=$'\t' read -r asset name; do
    gh api -H 'Accept: application/octet-stream' "repos/$repo/releases/assets/$asset" > "$tmp/download/$name"
done < <(jq -r '.[] | [.id, .name] | @tsv' "$tmp/asset-list")
cmp "$directory/$manifest" "$tmp/download/$manifest"
(cd "$tmp/download" && sha256sum --strict -c "$manifest")

if [[ "$phase" == publish ]]; then
    status=$(is_draft "$id")
    case "$status" in
        true)
            verify_tag
            gh api --method PATCH "$endpoint" -f tag_name="$tag" -F draft=false -F prerelease=false \
                -f make_latest=true --jq '.html_url' ;;
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
