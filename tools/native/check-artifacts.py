"""Verify native output hashes, Android ABIs, and the actual generated Java API."""

from pathlib import Path
import hashlib
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
STAGE = ROOT / "build/v1.1.0-native"

for name in ("lernet-core.exe", "lernet-protection-service.exe", "libbox-expert.aar"):
    path = STAGE / name
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    expected = Path(str(path) + ".sha256").read_text(encoding="ascii").strip()
    if actual != expected:
        raise SystemExit(f"Artifact hash mismatch: {name}")
    print(f"{name}: {actual}")

with zipfile.ZipFile(STAGE / "libbox-expert.aar") as archive:
    for abi in ("arm64-v8a", "x86_64"):
        if f"jni/{abi}/libbox.so" not in archive.namelist():
            raise SystemExit(f"Missing Android ABI {abi}")
    classes = STAGE / "expert-classes.jar"
    classes.write_bytes(archive.read("classes.jar"))

javap = ROOT / "jdk/jdk-21.0.12.1+1/bin/javap.exe"
session = subprocess.check_output([str(javap), "-classpath", str(classes), "io.nekohasekai.libbox.ExpertSession"], text=True)
libbox = subprocess.check_output([str(javap), "-classpath", str(classes), "io.nekohasekai.libbox.Libbox"], text=True)
session_signatures = (
    "java.lang.String start(long, java.lang.String, java.lang.String)",
    "java.lang.String apply(java.lang.String, java.lang.String, long, long, java.lang.String, java.lang.String)",
    "void close()", "java.lang.String status()", "void networkChanged()",
    "void wakeExitAt(java.lang.String, java.lang.String, long, java.lang.String)",
    "void sleepExitAt(java.lang.String, java.lang.String, long, java.lang.String)",
    "void recoverExitAt(java.lang.String, java.lang.String, long, java.lang.String)",
    "java.lang.String probeExitAt(java.lang.String, java.lang.String, long, java.lang.String, java.lang.String, long)",
)
for signature in session_signatures:
    if signature not in session:
        raise SystemExit(f"Missing generated ExpertSession signature {signature}")
for signature in (
    "java.lang.String expertCapabilities()",
    "io.nekohasekai.libbox.ExpertSession newExpertSession(java.lang.String, io.nekohasekai.libbox.PlatformInterface)",
):
    if signature not in libbox:
        raise SystemExit(f"Missing generated Libbox signature {signature}")
(STAGE / "expert-api.javap.txt").write_text(session + "\n" + libbox, encoding="utf-8", newline="\n")
print("Android arm64-v8a + x86_64 and all fenced native Java APIs verified")
