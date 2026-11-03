"""Exercise the actual POSIX installer with local release fixtures; no network or sudo."""
import hashlib
import io
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest

INSTALLER = Path(__file__).resolve().with_name("install.sh")


class InstallerTest(unittest.TestCase):
    def install(self, system="Linux", arch="x86_64", bad_checksum=False, existing=False):
        with tempfile.TemporaryDirectory(prefix="rer-installer-test-") as scratch:
            root = Path(scratch)
            name = f"rer-1.1.0-{'darwin' if system == 'Darwin' else 'linux'}-{'arm64' if arch == 'aarch64' else arch}"
            archive = root / f"{name}.tar.gz"
            with tarfile.open(archive, "w:gz") as tar:
                entry = tarfile.TarInfo(f"{name}/rer")
                data = b"#!/bin/sh\necho 'rer 1.1.0'\n"
                entry.size, entry.mode = len(data), 0o755
                tar.addfile(entry, io.BytesIO(data))
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            (root / "SHA256SUMS").write_text(f"{'0' * 64 if bad_checksum else digest}  {name}.tar.gz\n")
            tools = root / "tools"
            tools.mkdir()
            (tools / "uname").write_text(f'#!/bin/sh\ncase "$1" in -s) echo {system} ;; -m) echo {arch} ;; esac\n')
            (tools / "curl").write_text("""#!/bin/sh
set -eu
out=
url=
while [ "$#" -gt 0 ]; do
 case "$1" in
  -o) out=$2; shift 2 ;;
  --proto|--tlsv1.2) if [ "$1" = --proto ]; then shift 2; else shift; fi ;;
  -w) shift 2 ;;
  https://*) url=$1; shift ;;
  *) shift ;;
 esac
done
case "$url" in
 */releases/latest) printf 'https://github.com/test/repo/releases/tag/v1.1.0' ;;
 *) cp "$FIXTURE/${url##*/}" "$out" ;;
esac
""")
            for tool in tools.iterdir():
                tool.chmod(0o755)
            bindir = root / "bin"
            bindir.mkdir()
            if existing:
                (bindir / "rer").write_text("keep me")
            env = dict(os.environ, PATH=f"{tools}:{os.environ['PATH']}", HOME=str(root / "home"),
                       RER_BIN_DIR=str(bindir), RER_INSTALL_DIR=str(root / "versions"), FIXTURE=str(root))
            env.pop("RER_VERSION", None)
            result = subprocess.run(["sh", str(INSTALLER)], env=env, capture_output=True, text=True)
            if bad_checksum:
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("checksum mismatch", result.stderr)
                self.assertFalse((bindir / "rer").exists())
            elif existing:
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual((bindir / "rer").read_text(), "keep me")
            else:
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertTrue((bindir / "rer").is_symlink())
                self.assertEqual(subprocess.check_output([str(bindir / "rer")], text=True).strip(), "rer 1.1.0")

    def test_linux_x64(self): self.install()
    def test_linux_arm64(self): self.install(arch="aarch64")
    def test_macos_intel(self): self.install(system="Darwin")
    def test_macos_arm64(self): self.install(system="Darwin", arch="arm64")
    def test_checksum_rejected(self): self.install(bad_checksum=True)
    def test_existing_command_preserved(self): self.install(existing=True)


if __name__ == "__main__":
    unittest.main()
