#!/bin/sh
# Build on the target OS/architecture; the archive includes a private Java runtime.
set -eu
cd "$(dirname "$0")/.."
version=$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' pom.xml | head -n 1)
case "$(uname -s)" in Linux) os=linux ;; Darwin) os=darwin ;; *) echo 'Unsupported OS' >&2; exit 1 ;; esac
case "$(uname -m)" in x86_64|amd64) arch=x86_64 ;; arm64|aarch64) arch=arm64 ;; *) echo 'Unsupported architecture' >&2; exit 1 ;; esac
name="rer-$version-$os-$arch"
mkdir -p build/distributions
stage=$(mktemp -d "${TMPDIR:-/tmp}/rer-package.XXXXXX")
trap 'rm -rf "$stage"' EXIT HUP INT TERM
mkdir -p "$stage/$name/lib"
cp target/rer.jar "$stage/$name/lib/rer.jar"
cp LICENSE "$stage/$name/LICENSE"
# java.se avoids reflection-driven missing-module surprises; no host Java is needed.
"${JAVA_HOME:+$JAVA_HOME/bin/}jlink" --add-modules java.se,jdk.unsupported,jdk.crypto.ec --strip-debug --no-header-files --no-man-pages --output "$stage/$name/runtime"
cat > "$stage/$name/rer" <<'SH'
#!/bin/sh
set -eu
# Resolve the installer's symlink without relying on GNU readlink -f (absent on macOS).
launcher=$0
while [ -L "$launcher" ]; do
  parent=$(CDPATH= cd -- "$(dirname -- "$launcher")" && pwd)
  link=$(readlink "$launcher")
  case "$link" in /*) launcher=$link ;; *) launcher=$parent/$link ;; esac
done
RER_INSTALL_HOME=$(CDPATH= cd -- "$(dirname -- "$launcher")" && pwd)
exec "$RER_INSTALL_HOME/runtime/bin/java" -jar "$RER_INSTALL_HOME/lib/rer.jar" "$@"
SH
chmod +x "$stage/$name/rer"
"$stage/$name/rer" --version
tar -czf "build/distributions/$name.tar.gz" -C "$stage" "$name"
echo "build/distributions/$name.tar.gz"
