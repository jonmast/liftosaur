{
  description = "Liftosaur Android/Wear dev shell";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/release-25.11";
  };

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };

      buildToolsVersion = "36.0.0";
      ndkVersion = "27.1.12297006";
      cmakeVersion = "3.22.1";
      pinnedNdkVersion = "27.0.12077973";

      androidComposition = pkgs.androidenv.composeAndroidPackages {
        # 35: several autolinked RN modules compile against platform 35.
        platformVersions = [ "36" "35" ];
        # 35.0.0: some autolinked RN modules (e.g. react-native-apple-authentication)
        # pin buildToolsVersion 35 and AGP cannot install into the read-only store SDK.
        buildToolsVersions = [ buildToolsVersion "35.0.0" ];
        cmakeVersions = [ cmakeVersion ];
        ndkVersions = [ ndkVersion ];
        includeNDK = true;
        includeEmulator = false;
        includeSystemImages = false;
        systemImageTypes = [ ];
        abiVersions = [ ];
      };

      androidSdk = androidComposition.androidsdk;
      sdkRoot = "${androidSdk}/libexec/android-sdk";
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          androidSdk
          pkgs.jdk17
          pkgs.android-tools
          pkgs.gradle
        ];

        ANDROID_HOME = sdkRoot;
        ANDROID_SDK_ROOT = sdkRoot;
        JAVA_HOME = "${pkgs.jdk17}";

        shellHook = ''
          nixSdk="${sdkRoot}"

          # NixOS: the nix store SDK is read-only, but AGP insists sdk.dir be writable
          # (it tries to "install" the NDK revision an autolinked RN module pins).
          # Build a writable symlink farm over the store SDK instead.
          #
          # Keyed by the store path's hash so a changed composition gets a fresh farm, and
          # built idempotently: a plain `rm -rf` here would let a second `nix develop` wipe
          # the SDK out from under a Gradle build already running in the first one, which
          # surfaces as a bogus mergeDebugResources "No such file or directory" failure.
          sdkHash="$(echo "$nixSdk" | sed -n 's|^/nix/store/\([a-z0-9]\{8\}\).*|\1|p')"
          mutSdk="$HOME/.cache/liftosaur-android-sdk-''${sdkHash:-default}"
          mkdir -p "$mutSdk/ndk"
          for entry in "$nixSdk"/*; do
            base="$(basename "$entry")"
            [ "$base" = "ndk" ] && continue
            ln -sfn "$entry" "$mutSdk/$base"
          done
          ln -sfn "$nixSdk/ndk/${ndkVersion}" "$mutSdk/ndk/${ndkVersion}"

          # react-native-live-markdown pins android.ndkVersion ${pinnedNdkVersion}, which
          # nixpkgs does not package. Alias it onto the NDK we do have, with a patched
          # source.properties so AGP's revision check passes. No native code is compiled
          # for :wear, so the exact NDK revision is irrelevant here.
          aliasNdk="$mutSdk/ndk/${pinnedNdkVersion}"
          mkdir -p "$aliasNdk"
          for entry in "$nixSdk/ndk/${ndkVersion}"/*; do
            ln -sfn "$entry" "$aliasNdk/$(basename "$entry")"
          done
          rm -f "$aliasNdk/source.properties"
          sed "s/^Pkg.Revision=.*/Pkg.Revision=${pinnedNdkVersion}/" \
            "$nixSdk/ndk/${ndkVersion}/source.properties" > "$aliasNdk/source.properties"

          export ANDROID_HOME="$mutSdk"
          export ANDROID_SDK_ROOT="$mutSdk"
          export JAVA_HOME="${pkgs.jdk17}"

          NODE_BIN="$(command -v node || true)"
          if [ -z "$NODE_BIN" ]; then
            echo "warning: node not found on PATH"
            NODE_BIN="node"
          fi

          repoRoot="$(git rev-parse --show-toplevel 2>/dev/null || echo "$PWD")"
          cat > "$repoRoot/android/local.properties" <<EOF
sdk.dir=$mutSdk
cmake.dir=$mutSdk/cmake/${cmakeVersion}
EOF

          # NixOS: AGP's Maven-downloaded aapt2 is a glibc binary that cannot exec here;
          # nixpkgs' build-tools copy is autoPatchelf'd, so force AGP to use it.
          export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=$mutSdk/build-tools/${buildToolsVersion}/aapt2 -Dorg.gradle.project.nodeExecutableAndArgs=$NODE_BIN $GRADLE_OPTS"

          echo "Android SDK: $ANDROID_HOME"
          echo "JDK: $(java -version 2>&1 | head -1)"
        '';
      };
    };
}
