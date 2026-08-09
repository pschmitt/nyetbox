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

zenfone_serial := env_var_or_default("ZENFONE_SERIAL", "R6AIB700W850L7G")

mipad_host := env_var_or_default("MIPAD_HOST", "mi-pad-4.lan")
mipad_ssh_port := env_var_or_default("MIPAD_SSH_PORT", "8022")
mipad_adb_port := env_var_or_default("MIPAD_ADB_PORT", "5555")

px5_host := env_var_or_default("PX5_HOST", "px5.lan")

# List all available recipes
default:
    @just --list

# --- Remote build (rofl-13 / rofl-14) -------------------------------------

# Sync the working tree to the remote build host (excludes .git/build/.gradle). The .git exclude
# has no trailing slash so it matches both a real .git/ directory (the main checkout) and a plain
# .git file (a linked worktree's gitlink, which points at a local-only .git/worktrees/... path
# that doesn't exist on the remote host and breaks `nix develop` there if it gets copied over).
sync host=remote_host:
    rsync -az --delete \
        --exclude='.git' --exclude='**/build/' \
        --exclude='.gradle/' --exclude='**/.gradle/' \
        ./ {{host}}:{{remote_path}}/

# Run one or more Gradle tasks on the remote host (syncs first)
gradle host=remote_host *tasks: (sync host)
    ssh {{host}} 'cd {{remote_path}} && nix develop --command ./gradlew {{tasks}}'

# Build an APK remotely. variant: debug (default) or release. Release builds are signed with the
# persistent CI keystore (fetched from the rbw entry "NetBox and Chill CI Signing Keystore" and
# staged on the build host only for the duration of the build). Without CI_KEYSTORE_*, Gradle
# silently signs with the host's throwaway ~/.android/debug.keystore and devices carrying
# CI-signed installs (GitHub releases / Obtainium) reject the APK with
# INSTALL_FAILED_UPDATE_INCOMPATIBLE.
build variant="debug" host=remote_host:
    #!/usr/bin/env bash
    set -euo pipefail
    git_revision=$(git describe --always --abbrev=12 --dirty)
    build_date=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    if [[ "{{variant}}" != "release" ]]; then
      just sync "{{host}}"
      ssh "{{host}}" "
        export GIT_REVISION='$git_revision'
        export BUILD_DATE='$build_date'
        cd {{remote_path}} && nix develop --command ./gradlew ':app:assembleDebug'
      "
      exit 0
    fi
    if ! rbw unlocked >/dev/null 2>&1; then
      printf 'rbw is locked - run "rbw unlock" first (needed for the CI signing keystore)\n' >&2
      exit 2
    fi
    tmpdir=$(mktemp -d)
    trap 'rm -rf "$tmpdir"' EXIT
    rbw attachment get "NetBox and Chill CI Signing Keystore" --attachment netboxandchill-ci.jks --output "$tmpdir/nyetbox-ci.jks"
    rbw attachment get "NetBox and Chill CI Signing Keystore" --attachment netboxandchill-ci-keystore.env --output "$tmpdir/nyetbox-ci-keystore.env"
    just sync "{{host}}"
    ssh "{{host}}" 'mkdir -p ~/.nyetbox-ci-tmp && chmod 700 ~/.nyetbox-ci-tmp'
    scp -q "$tmpdir/nyetbox-ci.jks" "$tmpdir/nyetbox-ci-keystore.env" "{{host}}:.nyetbox-ci-tmp/"
    # The keystore is shredded on the host whether or not the build succeeds.
    ssh "{{host}}" "
      artifact={{remote_path}}/app/build/outputs/apk/release/app-{{default_abi}}-release.apk
      previous_mtime=0
      [[ -f \"\$artifact\" ]] && previous_mtime=\$(stat -c %Y \"\$artifact\")
      set -a
      . ~/.nyetbox-ci-tmp/nyetbox-ci-keystore.env
      set +a
      export CI_KEYSTORE_PATH=\$HOME/.nyetbox-ci-tmp/nyetbox-ci.jks
      export GIT_REVISION='$git_revision'
      export BUILD_DATE='$build_date'
      cd {{remote_path}} && nix develop --command ./gradlew ':app:assembleRelease' --rerun-tasks 2>&1 | tee ~/nyetbox-release-build.log
      rc=\$?
      if [[ \$rc -eq 0 && (! -f \"\$artifact\" || \$(stat -c %Y \"\$artifact\") -le \$previous_mtime) ]]; then
        echo 'release build did not refresh its APK artifact' >&2
        rc=1
      fi
      shred -u ~/.nyetbox-ci-tmp/* 2>/dev/null || true
      rmdir ~/.nyetbox-ci-tmp 2>/dev/null || true
      exit \$rc
    "

# Copy a built APK split back to ./dist locally. variant/host same as `build`, plus abi=<abi>
fetch variant="debug" host=remote_host abi=default_abi:
    #!/usr/bin/env bash
    set -euo pipefail
    mkdir -p {{local_dist}}
    scp "{{host}}:{{remote_path}}/app/build/outputs/apk/{{variant}}/app-{{abi}}-{{variant}}.apk" {{local_dist}}/

# Build an APK remotely and copy it back to ./dist. Same args as `build`.
build-fetch variant="debug" host=remote_host:
    just build {{variant}} {{host}}
    just fetch {{variant}} {{host}}

# ktfmt check via Gradle, remotely (mirrors .github/workflows/lint.yaml)
lint host=remote_host: (gradle host "ktfmtCheck")

# Run the unit test suite remotely
test host=remote_host: (gradle host ":app:testDebugUnitTest")

# Remote `./gradlew clean`
clean host=remote_host: (gradle host "clean")

# --- Zenfone 10 (USB, directly attached to this machine) -------------------

# Install an APK on the Zenfone 10 over adb (USB)
zenfone-install apk:
    adb -s {{zenfone_serial}} install -r {{apk}}

# Uninstall a package from the Zenfone 10. WARNING: wipes that app's local data (Room DB, saved
# credentials).
zenfone-uninstall pkg=application_id:
    adb -s {{zenfone_serial}} uninstall {{pkg}}

# Tail logcat from the Zenfone 10, optionally filtered by a grep pattern
zenfone-logcat filter="":
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -n "{{filter}}" ]; then
        adb -s {{zenfone_serial}} logcat | grep -i --line-buffered "{{filter}}"
    else
        adb -s {{zenfone_serial}} logcat
    fi

# Build an APK remotely, fetch it, and install it on the Zenfone 10. variant: debug (default) or release.
deploy-zenfone variant="debug":
    just build-fetch {{variant}}
    just zenfone-install "{{local_dist}}/app-{{default_abi}}-{{variant}}.apk"

# --- Mi Pad 4 (rooted, Termux SSH on port 8022) -----------------------------

# Run an arbitrary command on the Mi Pad 4 over SSH
mipad-ssh +cmd:
    ssh -p {{mipad_ssh_port}} {{mipad_host}} "{{cmd}}"

# Interactive shell on the Mi Pad 4
mipad-shell:
    ssh -p {{mipad_ssh_port}} {{mipad_host}}

# Find the port adbd is actually listening on (via `ss -ltnp` over root SSH), starting it as a
# fallback if it isn't running at all, then `adb connect` to it. Prints the resulting "host:port"
# adb target on stdout so other recipes can capture it - status/progress goes to stderr.
mipad-connect:
    #!/usr/bin/env bash
    set -euo pipefail
    port=$(ssh -p {{mipad_ssh_port}} {{mipad_host}} "su -c 'ss -ltnp'" 2>/dev/null \
        | awk '/adbd/ { n = split($4, a, ":"); print a[n]; exit }')
    if [ -z "$port" ]; then
        echo "adbd not listening - starting it via root shell" >&2
        ssh -p {{mipad_ssh_port}} {{mipad_host}} \
            "su -c 'setprop service.adb.tcp.port {{mipad_adb_port}} && stop adbd && start adbd'" >&2
        sleep 1
        port={{mipad_adb_port}}
    fi
    target="{{mipad_host}}:$port"
    adb connect "$target" >&2
    echo "$target"

# Install an APK on the Mi Pad 4 over adb (network, via mipad-connect). Simpler and more reliable
# than scp + `pm install`: adb push/install runs as adbd, which doesn't hit the SELinux/FUSE
# permission issues a plain scp into /sdcard runs into when system_server tries to read the file
# back.
mipad-install apk:
    #!/usr/bin/env bash
    set -euo pipefail
    target=$(just mipad-connect)
    adb -s "$target" install -r {{apk}}

# Uninstall a package from the Mi Pad 4. WARNING: wipes that app's local data.
mipad-uninstall pkg=application_id:
    #!/usr/bin/env bash
    set -euo pipefail
    target=$(just mipad-connect)
    adb -s "$target" uninstall {{pkg}}

# Tail logcat from the Mi Pad 4, optionally filtered by a grep pattern
mipad-logcat filter="":
    #!/usr/bin/env bash
    set -euo pipefail
    target=$(just mipad-connect)
    if [ -n "{{filter}}" ]; then
        adb -s "$target" logcat | grep -i --line-buffered "{{filter}}"
    else
        adb -s "$target" logcat
    fi

# Build an APK remotely, fetch it, and install it on the Mi Pad 4. variant: debug (default) or release.
deploy-mipad variant="debug":
    just build-fetch {{variant}}
    just mipad-install "{{local_dist}}/app-{{default_abi}}-{{variant}}.apk"

# --- Pixel 5 (px5.lan, wireless adb enabled on demand via Home Assistant/Tasker) -------------

# Enable wireless adb on the Pixel 5 (via `zhj adb::connect`, which triggers it through Home
# Assistant/Tasker) and connect. The port is dynamic (assigned fresh each time wireless debugging
# is (re)enabled), so this always re-discovers it from `adb devices` rather than assuming a fixed
# one - prints the resulting "host:port" target on stdout, status goes to stderr.
px5-connect:
    #!/usr/bin/env bash
    set -euo pipefail
    zhj adb::connect {{px5_host}} >&2
    target=$(adb devices | awk -v h="{{px5_host}}" '$1 ~ h { print $1; exit }')
    if [ -z "$target" ]; then
        echo "px5 (host {{px5_host}}) not found in \`adb devices\` after connecting" >&2
        exit 1
    fi
    echo "$target"

# Install an APK on the Pixel 5 over adb (wireless, via px5-connect)
px5-install apk:
    #!/usr/bin/env bash
    set -euo pipefail
    target=$(just px5-connect)
    adb -s "$target" install -r {{apk}}

# Uninstall a package from the Pixel 5. WARNING: wipes that app's local data.
px5-uninstall pkg=application_id:
    #!/usr/bin/env bash
    set -euo pipefail
    target=$(just px5-connect)
    adb -s "$target" uninstall {{pkg}}

# Tail logcat from the Pixel 5, optionally filtered by a grep pattern
px5-logcat filter="":
    #!/usr/bin/env bash
    set -euo pipefail
    target=$(just px5-connect)
    if [ -n "{{filter}}" ]; then
        adb -s "$target" logcat | grep -i --line-buffered "{{filter}}"
    else
        adb -s "$target" logcat
    fi

# Build an APK remotely, fetch it, and install it on the Pixel 5. variant: debug (default) or release.
deploy-px5 variant="debug":
    just build-fetch {{variant}}
    just px5-install "{{local_dist}}/app-{{default_abi}}-{{variant}}.apk"

# --- All devices -------------------------------------------------------------

# Build once, fetch once, install on every connected test device (Zenfone 10, Mi Pad 4, Pixel 5).
# The default target device for iterating on changes - see AGENTS.md.
deploy-all variant="debug":
    just build-fetch {{variant}}
    just zenfone-install "{{local_dist}}/app-{{default_abi}}-{{variant}}.apk"
    just mipad-install "{{local_dist}}/app-{{default_abi}}-{{variant}}.apk"
    just px5-install "{{local_dist}}/app-{{default_abi}}-{{variant}}.apk"

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

# Upload the generated screenshots to the release application's Play Console listing. This is
# deliberately separate from `screenshots`: capture uses the debug application, while Play Console
# metadata belongs to the release package and publishing is an explicit external side effect.
screenshots-upload:
    #!/usr/bin/env bash
    set -euo pipefail
    image_dir="fastlane/metadata/android"
    shopt -s nullglob
    image_types=(phoneScreenshots sevenInchScreenshots tenInchScreenshots)
    found_images=0
    for image_type in "${image_types[@]}"
    do
      image_glob=("$image_dir"/en-US/images/"$image_type"/*)
      if [[ ${#image_glob[@]} -gt 0 ]]
      then
        found_images=1
      fi
    done
    if [[ "$found_images" -eq 0 ]]
    then
      printf 'No generated screenshots found under %s\n' "$image_dir" >&2
      printf 'Run `just screenshots` first.\n' >&2
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
    for image_type in "${image_types[@]}"
    do
      image_glob=("$image_dir"/en-US/images/"$image_type"/*)
      [[ ${#image_glob[@]} -gt 0 ]] || continue
      # StoreScreenshotTest captures every screen in both dark and light mode (5 screens x 2 = 10
      # per device type), but Play rejects more than 8 screenshots per language outright
      # (confirmed live: "This app has more than 8 screenshots for language en-US."). Sort and cap
      # at 8 rather than fail the whole upload: "NN_name" sorts immediately before its
      # "NN_name_light" counterpart for each screen, so a plain sort interleaves dark/light pairs
      # per screen (01, 01_light, 02, 02_light, ...) - taking the first 8 keeps both modes of the
      # first 4 screens and drops the 5th (currently Settings) in both modes, rather than dropping
      # one mode's worth arbitrarily. All 10 stay committed in git either way; only the Play
      # Console upload is capped.
      if [[ ${#image_glob[@]} -gt 8 ]]
      then
        IFS=$'\n' image_glob=($(sort <<< "${image_glob[*]}"))
        unset IFS
        image_glob=("${image_glob[@]:0:8}")
      fi
      # Delete existing images of this type first: gpc's upload only ever appends, so re-running
      # this against a bucket that already has images (a prior manual upload, or just re-running
      # after a fresh capture) silently piles up duplicates instead of replacing them - confirmed
      # live, twice, once as literal duplicate screenshots and once by exceeding Play's 8-per-
      # language screenshot cap outright. The locally generated set is always the authoritative
      # "current" one, so start from empty every time instead.
      gpc --package {{play_package}} images delete-all --locale en-US --type "$image_type" --confirm
      for image in "${image_glob[@]}"
      do
        printf 'Uploading %s\n' "$image"
        gpc --package {{play_package}} images upload \
          --locale en-US \
          --type "$image_type" \
          --file "$image"
      done
    done

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

# --- Formatting / hooks ----------------------------------------------------

# Format Kotlin sources locally with ktfmt (lightweight - not a Gradle build, safe to run on this
# machine). CAUTION: this is nixpkgs' standalone ktfmt, which may be a newer version than the one
# CI actually uses (see gradle/libs.versions.toml) - treat this as an advisory quick pass, not a
# substitute for `just lint`.
format:
    ktfmt --kotlinlang-style $(git ls-files '*.kt' '*.kts')

# Nix formatting/lint for this repo's flake.nix (per global AI context rules)
nix-fmt:
    nixfmt flake.nix

nix-lint:
    nix develop --command statix check
