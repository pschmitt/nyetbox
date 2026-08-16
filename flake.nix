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
    android-app-ci = {
      url = "github:pschmitt/android-app-ci";
      flake = false;
    };
  };

  outputs =
    {
      self,
      nixpkgs,
      android-nixpkgs,
      git-hooks,
      android-app-ci,
    }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config.allowUnfree = true;
      };

      nyetboxSetup = pkgs.writeShellApplication {
        name = "nyetbox-setup";
        runtimeInputs = [
          pkgs.python3
          pkgs.qrencode
        ];
        text = builtins.readFile ./bin/nyetbox-setup;
      };

      androidEnv = import "${android-app-ci}/nix/devshells.nix" {
        inherit pkgs android-nixpkgs system;
        appName = "Nyetbox";
        buildToolsVersion = "37.0.0";
        platformVersion = "37-0";
        gitHooksLib = git-hooks.lib;
        preCommitExtra = {
          # `//`-merged over the defaults, which replaces this whole entry - re-include enable=true
          # alongside excludes, or the hook silently ends up disabled.
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
        };
        extraPackages = [ nyetboxSetup ];
        screenshotsSystemImage = "system-images-android-34-google-apis-x86-64";
        quickStart = ''
          echo "  just deploy-zenfone           # ...and install it on the Zenfone 10"
          echo "  just deploy-mipad             # ...and install it on the Mi Pad 4"
        '';
      };
    in
    {
      packages.${system}.nyetbox-setup = nyetboxSetup;
      apps.${system}.nyetbox-setup = {
        type = "app";
        program = "${nyetboxSetup}/bin/nyetbox-setup";
      };

      devShells.${system} = androidEnv.devShells;
      checks.${system} = androidEnv.checks;
    };
}
