# Sourced by the build scripts: where the NDK and vcpkg are, and the NDK's
# prebuilt toolchain for this host.  Override with ANDROID_NDK_HOME and
# VCPKG_ROOT; the defaults are a macOS Android Studio install.

NDK_VERSION=29.0.14206865
NDK_PATH="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/$NDK_VERSION}"
VCPKG_ROOT="${VCPKG_ROOT:-$HOME/projects/vcpkg}"

case "$(uname -s)" in
    Darwin) NDK_HOST=darwin-x86_64 ;;   # universal binaries, Apple silicon too
    Linux)  NDK_HOST=linux-x86_64 ;;
    *) echo "ndk-env.sh: unsupported host $(uname -s)" >&2; return 1 ;;
esac

TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/$NDK_HOST/bin"
LLVM_AR="$TOOLCHAIN/llvm-ar"
export NDK_PATH VCPKG_ROOT NDK_HOST TOOLCHAIN LLVM_AR
export ANDROID_NDK_HOME="$NDK_PATH" ANDROID_NDK_ROOT="$NDK_PATH"
