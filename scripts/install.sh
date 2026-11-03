#!/bin/sh
# Usage: curl -fsSL https://raw.githubusercontent.com/itishrishikesh/repository-context-packer/main/scripts/install.sh | sh
set -eu
repo=itishrishikesh/repository-context-packer
fail() { echo "rer: $*" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || fail 'curl is required'
command -v tar >/dev/null 2>&1 || fail 'tar is required'
case "$(uname -s)" in Linux) os=linux ;; Darwin) os=darwin ;; *) fail 'Supported systems: Linux and macOS' ;; esac
case "$(uname -m)" in x86_64|amd64) arch=x86_64 ;; arm64|aarch64) arch=arm64 ;; *) fail 'Supported architectures: x86_64 and arm64' ;; esac
if [ -n "${RER_VERSION:-}" ]; then
  version=${RER_VERSION#v}
else
  latest=$(curl --proto '=https' --tlsv1.2 -fsSL -o /dev/null -w '%{url_effective}' "https://github.com/$repo/releases/latest")
  version=${latest##*/v}
fi
case "$version" in ''|*[!0-9.]*|.*|*..*|*.) fail 'Could not resolve release version; set RER_VERSION to a published version' ;; esac
name="rer-$version-$os-$arch"
base="https://github.com/$repo/releases/download/v$version"
temporary=$(mktemp -d "${TMPDIR:-/tmp}/rer-install.XXXXXX")
trap 'rm -rf "$temporary"' EXIT HUP INT TERM
curl --proto '=https' --tlsv1.2 -fsSL "$base/$name.tar.gz" -o "$temporary/$name.tar.gz"
curl --proto '=https' --tlsv1.2 -fsSL "$base/SHA256SUMS" -o "$temporary/SHA256SUMS"
expected=$(awk -v file="$name.tar.gz" '$2 == file { print $1 }' "$temporary/SHA256SUMS")
[ "${#expected}" -eq 64 ] || fail 'Missing or invalid release checksum'
if command -v sha256sum >/dev/null 2>&1; then
  actual=$(sha256sum "$temporary/$name.tar.gz" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
  actual=$(shasum -a 256 "$temporary/$name.tar.gz" | awk '{print $1}')
else
  fail 'sha256sum or shasum is required'
fi
[ "$actual" = "$expected" ] || fail 'Release checksum mismatch'
tar -xzf "$temporary/$name.tar.gz" -C "$temporary"
"$temporary/$name/rer" --version
install_root=${RER_INSTALL_DIR:-$HOME/.local/share/rer/versions}
bin_dir=${RER_BIN_DIR:-$HOME/.local/bin}
mkdir -p "$install_root" "$bin_dir"
install_root=$(CDPATH= cd -- "$install_root" && pwd)
destination="$install_root/$name"
[ ! -e "$destination" ] || fail "Version already installed at $destination; remove that directory to reinstall"
mv "$temporary/$name" "$destination"
if [ -e "$bin_dir/rer" ] && [ ! -L "$bin_dir/rer" ]; then
  fail "Refusing to replace an existing regular file: $bin_dir/rer"
fi
ln -sfn "$destination/rer" "$bin_dir/rer"
echo "Installed rer $version to $bin_dir/rer"
case ":$PATH:" in
  *":$bin_dir:"*) echo 'Run: rer --help' ;;
  *) echo "Add $bin_dir to PATH. For bash/zsh, add this line to your shell profile:"; echo "export PATH=\"$bin_dir:\$PATH\"" ;;
esac
