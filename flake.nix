{
  description = "Nyetbox Android development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    android-nixpkgs = {
      url = "github:tadfisher/android-nixpkgs";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    git-hooks = {
      url = "github:cachix/git-hooks.nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    {
      self,
      nixpkgs,
      android-nixpkgs,
      git-hooks,
    }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config.allowUnfree = true;
      };

      android-composition = android-nixpkgs.sdk.${system} (
        sdkPkgs: with sdkPkgs; [
          cmdline-tools-latest
          build-tools-37-0-0
          platform-tools
          platforms-android-37-0
        ]
      );

      # Local-only Android SDK selection for capturing Play Store screenshots: adds the emulator
      # and an API 34 google_apis x86_64 system image (matching .github/workflows/android-e2e.yaml's
      # profile) on top of the same base tools. Kept out of the default `android-composition` above
      # so the ordinary Gradle/lint dev shell doesn't pull in the (large) emulator + system image.
      android-composition-screenshots = android-nixpkgs.sdk.${system} (
        sdkPkgs: with sdkPkgs; [
          cmdline-tools-latest
          platform-tools
          emulator
          system-images-android-34-google-apis-x86-64
        ]
      );

      pre-commit-check = git-hooks.lib.${system}.run {
        src = ./.;
        hooks = {
          trim-trailing-whitespace.enable = true;
          end-of-file-fixer.enable = true;
          check-merge-conflicts.enable = true;
          check-added-large-files = {
            enable = true;
            excludes = [
              # Vendored, pre-migrated/pre-seeded Postgres dumps for the disposable CI NetBox
              # fixtures (see justfile's netbox-fixture-regen) - deliberately committed binaries,
              # not an accidental large-file slip.
              "^ci/netbox/fixtures/.*\\.dump$"
              # Generated Baseline Profile (NBC-426, see .github/workflows/baseline-profile.yaml) -
              # a large but deliberately committed text file, regenerated occasionally, not an
              # accidental large-file slip either.
              "^app/src/release/generated/baselineProfiles/.*\\.txt$"
            ];
          };
          check-yaml.enable = true;
          nixfmt.enable = true;
          statix.enable = true;

          # No ktfmt pre-commit hook: nixpkgs only ships a recent standalone ktfmt, but the
          # project's Gradle plugin pins an older ktfmt (see gradle/libs.versions.toml), and the
          # two format some constructs differently. A hook running the wrong version could "fix" a
          # file into a state that then fails CI's real `./gradlew ktfmtCheck`. Use `just lint`
          # (remote, runs the pinned Gradle plugin) as the authoritative check instead - `just
          # format` is still available for a quick local pass, but treat its output as advisory,
          # not final.
        };
      };

      nyetboxSetup = pkgs.writeShellApplication {
        name = "nyetbox-setup";
        runtimeInputs = [
          pkgs.python3
          pkgs.qrencode
        ];
        text = builtins.readFile ./bin/nyetbox-setup;
      };

    in
    {
      checks.${system}.pre-commit-check = pre-commit-check;

      packages.${system}.nyetbox-setup = nyetboxSetup;
      apps.${system}.nyetbox-setup = {
        type = "app";
        program = "${nyetboxSetup}/bin/nyetbox-setup";
      };

      devShells.${system} = {
        screenshots = pkgs.mkShell {
          packages = with pkgs; [
            fastlane
            android-composition-screenshots
          ];

          shellHook = ''
            export ANDROID_SDK_ROOT=${android-composition-screenshots}/share/android-sdk
            export ANDROID_HOME=$ANDROID_SDK_ROOT
            export PATH=$PATH:$ANDROID_SDK_ROOT/platform-tools
            export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin
            export PATH=$PATH:$ANDROID_SDK_ROOT/emulator

            # avdmanager (XDG-aware) and emulator (not) disagree on the default AVD home when
            # unset - one lands in ~/.config/.android, the other looks in ~/.android. Pin it
            # explicitly so both tools agree.
            export ANDROID_AVD_HOME="$HOME/.android/avd"
            mkdir -p "$ANDROID_AVD_HOME"
          '';
        };

        default = pkgs.mkShell {
          buildInputs =
            with pkgs;
            [
              jdk21
              android-composition
              just
              ktfmt
            ]
            ++ [ nyetboxSetup ];

          shellHook = pre-commit-check.shellHook + ''
            echo "🥂 Nyetbox development environment"

            # Set JAVA_HOME for Gradle
            export JAVA_HOME=${pkgs.jdk21}/lib/openjdk

            # Set Android SDK path
            export ANDROID_SDK_ROOT=${android-composition}/share/android-sdk
            export ANDROID_HOME=$ANDROID_SDK_ROOT

            # Add Android tools to PATH
            export PATH=$PATH:$ANDROID_SDK_ROOT/platform-tools
            export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin
            export PATH=$PATH:$ANDROID_SDK_ROOT/build-tools/37.0.0

            # Gradle configuration
            export GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_SDK_ROOT/build-tools/37.0.0/aapt2"

            echo "Java version: $(java -version 2>&1 | head -n1)"

            # Remove sdk.dir from local.properties if present, otherwise aidl
            # will fail to run
            if [ -f local.properties ]; then
              sed -i -E '/^sdk\.dir=/d' local.properties
            fi

            echo "✅ Environment ready!"
            echo "• JAVA_HOME: $JAVA_HOME"
            echo "• ANDROID_SDK_ROOT: $ANDROID_SDK_ROOT"
            echo "• Available commands: ./gradlew, adb, aapt2, just, ktfmt"
            echo ""
            if [ "$(hostname)" != "rofl-13" ] && [ "$(hostname)" != "rofl-14" ]; then
              echo "⚠️  Don't run ./gradlew directly on this machine - see AGENTS.md."
              echo "   Use the 'just' recipes below, which build on rofl-13/rofl-14 instead:"
              echo ""
              echo "🚀 Quick start:"
              echo "  just build                    # Build debug APK on rofl-13"
              echo "  just build-fetch              # ...and copy it back to ./dist"
              echo "  just deploy-zenfone           # ...and install it on the Zenfone 10"
              echo "  just deploy-mipad             # ...and install it on the Mi Pad 4"
              echo "  just --list                   # See all available recipes"
            else
              echo "🚀 Quick start (on a remote build host):"
              echo "  ./gradlew :app:assembleDebug   # Build debug APK"
              echo "  ./gradlew :app:installDebug    # Install to connected device"
              echo "  ./gradlew test"
            fi
          '';
        };
      };
    };
}
