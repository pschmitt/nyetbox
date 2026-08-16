# Nyetbox task runner.
#
# Gradle must never run on this machine directly - every build/test/lint recipe here shells out to
# a remote host (rofl-13.brkn.lol or rofl-14.brkn.lol) over SSH instead. See AGENTS.md.

set shell := ["bash", "-euo", "pipefail", "-c"]

application_id := "dev.pschmitt.nyetbox"

remote_host := env_var_or_default("NBC_REMOTE_HOST", "rofl-13.brkn.lol")

# Empty for the main checkout; "-<worktree-dirname>" when run from a linked git worktree (e.g. one
# of Claude's isolated agent worktrees under .claude/worktrees/). Keeps parallel worktree agents
# from clobbering each other's remote sync directory mid-build.
worktree_suffix := `gd=$(git rev-parse --git-dir); gcd=$(git rev-parse --git-common-dir); if [ "$gd" != "$gcd" ]; then basename "$(git rev-parse --show-toplevel)" | sed 's/^/-/'; fi`

remote_path := env_var_or_default("NBC_REMOTE_PATH", "~/build/nyetbox" + worktree_suffix)
local_dist := env_var_or_default("NBC_DIST_DIR", "./dist")

default_abi := env_var_or_default("NBC_ABI", "arm64-v8a")
gradle_extra_props := ""

zenfone_serial := env_var_or_default("ZENFONE_SERIAL", "R6AIB700W850L7G")

mipad_host := env_var_or_default("MIPAD_HOST", "mi-pad-4.lan")
mipad_ssh_port := env_var_or_default("MIPAD_SSH_PORT", "8022")
mipad_adb_port := env_var_or_default("MIPAD_ADB_PORT", "5555")

px5_host := env_var_or_default("PX5_HOST", "px5.lan")

# Release builds are signed with the persistent CI keystore, fetched from this rbw entry and
# staged on the build host only for the duration of the build. Without CI_KEYSTORE_*, Gradle
# silently signs with the host's throwaway ~/.android/debug.keystore, and devices carrying
# CI-signed installs (GitHub releases / Obtainium) reject the APK with
# INSTALL_FAILED_UPDATE_INCOMPATIBLE.
enable_release_signing := "true"
rbw_keystore_entry := "NetBox and Chill CI Signing Keystore"
keystore_jks_attachment := "netboxandchill-ci.jks"
keystore_env_attachment := "netboxandchill-ci-keystore.env"
ci_tmp_dir_name := ".nyetbox-ci-tmp"

# List all available recipes. Must stay the first recipe in this file (not just the first line
# overall) - `just` only considers recipes written directly here, not ones pulled in via the
# import below, when deciding what a bare `just` invocation runs.
default:
    @just --list

# Recipes shared across the app fleet: format/nix-fmt/nix-lint/screenshots-upload (common.just, all
# 4 apps) and the remote sync/build/deploy pipeline - sync/gradle/build/fetch/build-fetch/clean/
# lint/test plus the zenfone-*/mipad-*/px5-*/deploy-all device recipes (single-module.just, the 3
# single-Gradle-module apps). See pschmitt/android-app-ci's just/ for the source of truth.
# Pulled in via a git submodule at .just/android-app-ci (tracking that repo's main branch);
# `just update-common` (defined at the bottom of this file) refreshes it. The devShell's shellHook
# auto-runs `git submodule update --init` on every `nix develop` entry, so a fresh git worktree
# (this fleet creates plenty - .claude/worktrees/, .codex/worktrees/, ...) never needs a manual
# `--init` step - `git worktree add` alone doesn't check out submodule content.
import '.just/android-app-ci/just/common.just'
import '.just/android-app-ci/just/single-module.just'

# --- Play Store screenshots (disposable NetBox + local emulator) -----------
#
# Never touches the production NetBox instance: it drives the app against the same disposable
# docker-compose fixture used by .github/workflows/android-e2e.yaml (see ci/netbox/), so captured
# screenshots only ever show throwaway seeded demo records. The fixture and its volumes are always
# torn down at the end of `just screenshots`, success or failure.

netbox_compose_file := "ci/netbox/docker-compose.yml"
screenshots_netbox_compose_file := "ci/netbox/docker-compose.screenshots.yml"
screenshots_avd := env_var_or_default("NBC_SCREENSHOTS_AVD", "nyetbox-screenshots")
play_package := "dev.pschmitt.nyetbox"
# Disposable screenshot-fixture credential; not a real secret.
screenshots_token := "nbt_CiE2eKey001X.0123456789abcdef0123456789abcdef01234567"

# Start the disposable NetBox fixture used for Play Store screenshots (and CI E2E).
netbox-up:
    docker compose -f {{netbox_compose_file}} up --detach --wait --wait-timeout 600

# Seed a small realistic-looking rack of demo devices into the disposable NetBox fixture. Uses
# seed_screenshots.py, not the android-e2e.yaml workflow's seed.py - that one's exact-match
# assertions ("CI E2E Device") aren't meant to look good in a store listing.
netbox-seed:
    python3 ci/netbox/seed_screenshots.py --base-url http://127.0.0.1:8000 --token {{screenshots_token}}

# Tear down the disposable NetBox fixture and its volumes.
netbox-down:
    docker compose -f {{netbox_compose_file}} down --volumes --remove-orphans

# Build the disposable NetBox image with the plugins used by the screenshot capture. Keep this a
# separate docker build so the recipe also works with hosts whose Compose buildx is older than the
# version required by recent Docker Compose releases.
screenshots-netbox-build:
    docker build --tag local/nyetbox-netbox-screenshots:4.5-plugins --file ci/netbox/Dockerfile-screenshots ci/netbox

# Start the disposable NetBox fixture with the plugins used by the screenshot capture.
screenshots-netbox-up:
    just screenshots-netbox-build
    docker compose -f {{netbox_compose_file}} -f {{screenshots_netbox_compose_file}} up --no-build --detach --wait --wait-timeout 600

# Tear down the plugin-enabled screenshot fixture and its volumes.
screenshots-netbox-down:
    docker compose -f {{netbox_compose_file}} -f {{screenshots_netbox_compose_file}} down --volumes --remove-orphans

# Regenerate a vendored CI NetBox fixture (ci/netbox/fixtures/<name>.dump) from a fresh instance:
# full migrations + the real seed script, then a pg_dump of the result. postgres restores this dump
# automatically on its next empty-volume start (see restore-fixture.sh), which is what lets
# android-e2e.yaml/screenshots.yaml skip both the migration wait and the seed script's API calls on
# every normal run. Re-run this whenever seed.py/seed_screenshots.py changes, or the NetBox
# image/plugin versions in docker-compose.yml or Dockerfile-screenshots bump - then review and
# commit the resulting dump like any other change.
netbox-fixture-regen name:
    #!/usr/bin/env bash
    set -euo pipefail
    case "{{name}}" in
      e2e)
        compose_args=(-f {{netbox_compose_file}})
        seed_script=ci/netbox/seed.py
        ;;
      screenshots)
        just screenshots-netbox-build
        compose_args=(-f {{netbox_compose_file}} -f {{screenshots_netbox_compose_file}})
        seed_script=ci/netbox/seed_screenshots.py
        ;;
      *)
        echo "Unknown fixture '{{name}}' - expected 'e2e' or 'screenshots'" >&2
        exit 1
        ;;
    esac
    # A stale dump for this fixture would otherwise get restored instead of starting empty.
    rm -f "ci/netbox/fixtures/{{name}}.dump"
    docker compose "${compose_args[@]}" down --volumes --remove-orphans
    docker compose "${compose_args[@]}" up --no-build --detach --wait --wait-timeout 600
    python3 "$seed_script" --base-url http://127.0.0.1:8000 --token {{screenshots_token}}
    docker compose "${compose_args[@]}" exec -T postgres \
        pg_dump -Fc --no-owner -U netbox -d netbox > "ci/netbox/fixtures/{{name}}.dump"
    docker compose "${compose_args[@]}" down --volumes --remove-orphans
    echo "Wrote ci/netbox/fixtures/{{name}}.dump - review (git diff --stat) and commit it."

# Create the local screenshot-capture AVD once (API 34, google_apis, x86_64 - matches the
# android-e2e.yaml workflow's emulator profile). Safe to re-run; skips if it already exists.
screenshots-avd-create:
    #!/usr/bin/env bash
    set -euo pipefail
    nix develop .#screenshots --command bash -euo pipefail -c '
      if avdmanager list avd | grep -q "Name: {{screenshots_avd}}$"; then
        echo "AVD {{screenshots_avd}} already exists"
        exit 0
      fi
      echo "no" | avdmanager create avd \
        --name {{screenshots_avd}} \
        --package "system-images;android-34;google_apis;x86_64" \
        --device "pixel_2"
    '

# Start the screenshot-capture emulator in the background (hardware-accelerated via /dev/kvm) and
# wait for it to finish booting. Prints its adb serial on stdout.
screenshots-emulator-start:
    #!/usr/bin/env bash
    set -euo pipefail
    serial=$(adb devices | awk '/^emulator-/ { print $1; exit }')
    if [ -n "$serial" ]; then
      echo "$serial"
      exit 0
    fi
    nix develop .#screenshots --command bash -c '
      nohup emulator -avd {{screenshots_avd}} -no-window -no-snapshot -no-audio -no-boot-anim \
        -gpu swiftshader_indirect >/tmp/nyetbox-screenshots-emulator.log 2>&1 &
      disown
    '
    for _ in $(seq 1 60); do
      serial=$(adb devices | awk '/^emulator-/ { print $1; exit }')
      [ -n "$serial" ] && break
      sleep 2
    done
    [ -n "$serial" ] || { echo "emulator did not register with adb" >&2; exit 1; }
    adb -s "$serial" wait-for-device
    until [ "$(adb -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do
      sleep 2
    done
    echo "$serial"

# Stop whichever screenshot-capture emulator is currently running, if any.
screenshots-emulator-stop:
    #!/usr/bin/env bash
    set -euo pipefail
    serial=$(adb devices | awk '/^emulator-/ { print $1; exit }')
    [ -n "$serial" ] && adb -s "$serial" emu kill || true

# Build the debug app (x86_64, for the emulator) and its instrumentation APK remotely, then fetch
# both locally for screengrab.
screenshots-build host=remote_host: (gradle host "assembleDebug assembleDebugAndroidTest")
    #!/usr/bin/env bash
    set -euo pipefail
    just fetch debug {{host}} x86_64
    scp "{{host}}:{{remote_path}}/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" {{local_dist}}/

# Capture Play Store screenshots (en-US) end to end: starts the disposable NetBox fixture and the
# screenshot emulator, builds+fetches the APKs, runs fastlane screengrab, then always tears the
# NetBox fixture back down. See docs/screenshots.md.
screenshots host=remote_host:
    #!/usr/bin/env bash
    set -euo pipefail
    trap 'just screenshots-netbox-down' EXIT
    just screenshots-netbox-up
    just netbox-seed
    just screenshots-avd-create
    serial=$(just screenshots-emulator-start)
    adb -s "$serial" reverse tcp:8000 tcp:8000
    just screenshots-build "{{host}}"
    adb -s "$serial" install -r -t "{{local_dist}}/app-x86_64-debug.apk"
    # Wipe any state left by a previous run on this emulator (e.g. already onboarded from a prior
    # capture) so the test always starts from the onboarding screen, then re-grant the
    # notification permission MainActivity requests at startup on API 33+ - a permission dialog
    # mid-journey would interrupt the Compose test. reinstall_app is false in Screengrabfile so
    # fastlane's own `install -r` below reuses this install and preserves the grant.
    adb -s "$serial" shell pm clear dev.pschmitt.nyetbox.debug
    adb -s "$serial" shell pm grant dev.pschmitt.nyetbox.debug android.permission.POST_NOTIFICATIONS || true
    E2E_TOKEN={{screenshots_token}} SCREENGRAB_SPECIFIC_DEVICE="$serial" \
      nix develop .#screenshots --command fastlane screenshots

# Flatten and upload the app icon used by the launcher and README. Keep this separate from the
# screenshot upload because the Play Console icon is not locale-scoped.
play-icon-upload:
    #!/usr/bin/env bash
    set -euo pipefail
    source_icon="docs/images/nyetbox-icon.svg"
    if [[ ! -f "$source_icon" ]]
    then
      printf 'Icon source not found: %s\n' "$source_icon" >&2
      exit 1
    fi
    if ! command -v magick >/dev/null
    then
      printf 'ImageMagick `magick` is required to flatten the SVG icon\n' >&2
      exit 1
    fi
    if ! command -v gpc >/dev/null
    then
      printf 'gpc (playconsole-cli) is required for Play Console uploads\n' >&2
      exit 1
    fi
    if ! gpc apps list --output json | rg -q '"package_name":"{{play_package}}"'
    then
      printf 'Play Console package %s was not found via `gpc apps list`\n' "{{play_package}}" >&2
      exit 1
    fi
    temp_dir=$(mktemp -d)
    trap 'rm -rf "$temp_dir"' EXIT
    magick -background none "$source_icon" -resize 512x512 "$temp_dir/nyetbox-icon.png"
    gpc --package {{play_package}} images upload \
      --locale en-US \
      --type icon \
      --file "$temp_dir/nyetbox-icon.png"

# Compose and upload the Play Console feature graphic (1024x500 - shown at the top of the store
# listing, not locale-scoped). The SVG icon is rasterized to a standalone PNG first, then
# composited onto the banner: compositing straight from the SVG in one `magick` pipeline drops its
# alpha channel and leaves a white square behind the icon (tested; a separate rasterize step avoids
# it). The tagline is a short banner-width phrase, not short_description.txt verbatim - that text
# is tuned for the store listing body and is much too long to fit at a readable size here. Re-run
# this whenever the wordmark, tagline, or icon colors change.
play-feature-graphic-upload:
    #!/usr/bin/env bash
    set -euo pipefail
    source_icon="docs/images/nyetbox-icon.svg"
    if [[ ! -f "$source_icon" ]]
    then
      printf 'Icon source not found: %s\n' "$source_icon" >&2
      exit 1
    fi
    if ! command -v magick >/dev/null
    then
      printf 'ImageMagick `magick` is required to compose the feature graphic\n' >&2
      exit 1
    fi
    if ! command -v gpc >/dev/null
    then
      printf 'gpc (playconsole-cli) is required for Play Console uploads\n' >&2
      exit 1
    fi
    if ! gpc apps list --output json | rg -q '"package_name":"{{play_package}}"'
    then
      printf 'Play Console package %s was not found via `gpc apps list`\n' "{{play_package}}" >&2
      exit 1
    fi
    temp_dir=$(mktemp -d)
    trap 'rm -rf "$temp_dir"' EXIT
    magick -background none "$source_icon" -resize 380x380 "$temp_dir/icon.png"
    magick -size 1024x500 xc:"#011226" "$temp_dir/icon.png" -gravity West -geometry +64+0 -compose over -composite \
      -gravity West -font Liberation-Sans-Bold -pointsize 100 -fill "#f4f7f8" -annotate +480-40 "Nyetbox" \
      -gravity West -font Liberation-Sans -pointsize 32 -fill "#00e5d6" -annotate +482+55 "Offline-first NetBox companion" \
      "$temp_dir/feature-graphic.png"
    gpc --package {{play_package}} images upload \
      --locale en-US \
      --type featureGraphic \
      --file "$temp_dir/feature-graphic.png"

# --- Shared recipes (pschmitt/android-app-ci) -------------------------------

# Advance the .just/android-app-ci submodule to the tip of its tracked branch (main) and stage the
# result - review the diff like any other dependency bump before committing it.
update-common:
    git submodule update --remote .just/android-app-ci
    git add .just/android-app-ci
