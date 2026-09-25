"""Create a local signing key on first release build. Never commit .release/."""

from pathlib import Path
import os
import secrets
import shutil
import subprocess


ROOT = Path(__file__).resolve().parents[2]
PRIVATE = ROOT / ".release"
KEY = PRIVATE / "lernet-release.p12"
PROPERTIES = PRIVATE / "signing.properties"


def keytool_path() -> str:
    for java_home in (os.environ.get("JAVA_HOME"), str(ROOT / "jdk" / "jdk-21.0.12.1+1")):
        if not java_home:
            continue
        candidate = Path(java_home) / "bin" / "keytool.exe"
        if candidate.is_file():
            return str(candidate)
    found = shutil.which("keytool")
    if not found:
        raise SystemExit("keytool is missing; install JDK 17+ and set JAVA_HOME")
    return found


def main() -> None:
    if KEY.is_file() and PROPERTIES.is_file():
        print("Existing local LerNET release key found.")
        return
    if KEY.exists() or PROPERTIES.exists():
        raise SystemExit("Incomplete .release directory; recover the original key and signing.properties")
    PRIVATE.mkdir(parents=True, exist_ok=True)
    password = secrets.token_urlsafe(32)
    subprocess.run(
        [
            keytool_path(), "-genkeypair", "-noprompt", "-storetype", "PKCS12",
            "-keystore", str(KEY), "-storepass", password, "-keypass", password,
            "-alias", "lernet", "-keyalg", "RSA", "-keysize", "3072",
            "-validity", "10000", "-dname", "CN=LerNET, OU=Release, O=LerNET",
        ],
        check=True,
        stdout=subprocess.DEVNULL,
    )
    PROPERTIES.write_text(
        "storeFile=.release/lernet-release.p12\n"
        f"storePassword={password}\n"
        "keyAlias=lernet\n"
        f"keyPassword={password}\n",
        encoding="utf-8",
    )
    print("Created local signing key in .release/. Back it up securely for future updates.")


if __name__ == "__main__":
    main()
