"""Build the pinned native expert artifacts without starting any network engine.

Requires the workspace portable Go 1.27.1, gomobile v0.1.13, JDK 21, Android
SDK/NDK r30 and pinned sing-box-lx checkout. No installer/system changes occur.
"""

import argparse
import hashlib
import os
import shutil
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
STAGE = ROOT / "build/v1.1.0-native"
SOURCE = STAGE / "sing-box-lx"
TAGS = "with_gvisor,with_quic,with_wireguard,with_utls,with_naive_outbound,badlinkname,tfogo_checklinkname0,with_xhttp,with_awg,with_lx_command,with_lx_chain,with_lx_idle_suspend,with_openvpn,with_openconnect,with_tailscale,ts_omit_logtail,ts_omit_ssh,ts_omit_drive,ts_omit_taildrop,ts_omit_webclient,ts_omit_doctor,ts_omit_capture,ts_omit_kube,ts_omit_aws,ts_omit_synology,ts_omit_bird"
LINK = "-s -w -checklinkname=0 -X github.com/sagernet/sing-box/constant.Version=1.14.1-lx.8-lernet1"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", choices=("windows", "android", "both"))
    parser.add_argument("--no-prepare", action="store_true", help="Skip overlay publication while another build reads the tree")
    args = parser.parse_args()
    go_root = STAGE / "toolchain/go"
    gopath = STAGE / "gopath"
    jdk = ROOT / "jdk/jdk-21.0.12.1+1"
    sdk = ROOT / "android-sdk"
    temp = STAGE / "tmp"
    temp.mkdir(parents=True, exist_ok=True)
    # Canonical names avoid duplicate case variants in Windows child environments.
    env = {key.upper(): value for key, value in os.environ.items()}
    env.update(
        GOROOT=str(go_root), GOPATH=str(gopath), GOCACHE=str(STAGE / "gocache"),
        GOTMPDIR=str(temp), TEMP=str(temp), TMP=str(temp), GOTOOLCHAIN="local",
        GOMAXPROCS="2", GOFLAGS="-p=2", JAVA_HOME=str(jdk),
        ANDROID_HOME=str(sdk), ANDROID_NDK_HOME=str(sdk / "ndk/30.0.16248370"),
    )
    env["PATH"] = os.pathsep.join(map(str, [go_root / "bin", gopath / "bin", jdk / "bin"])) + os.pathsep + env["PATH"]
    if not args.no_prepare:
        subprocess.run([sys.executable, str(ROOT / "tools/native/apply-overlay.py"), str(SOURCE)], check=True, env=env)
        subprocess.run([str(go_root / "bin/gofmt.exe"), "-w", str(SOURCE / "lernet/expert"), str(SOURCE / "experimental/libbox/lernet_expert.go"), str(SOURCE / "cmd/sing-box/cmd_lernet_expert.go")], check=True, env=env)
    if args.target in ("windows", "both"):
        env["CGO_ENABLED"] = "0"
        output = STAGE / "lernet-core.exe"
        subprocess.run([str(go_root / "bin/go.exe"), "build", "-p", "2", "-trimpath", "-buildvcs=false", "-ldflags", LINK, "-tags", TAGS + ",with_purego,with_clash_api", "-o", str(output), "./cmd/sing-box"], cwd=SOURCE, env=env, check=True)
        (output.with_suffix(".exe.sha256")).write_text(hashlib.sha256(output.read_bytes()).hexdigest() + "\n", encoding="ascii")
        guard = STAGE / "lernet-protection-service.exe"
        subprocess.run([str(go_root / "bin/go.exe"), "build", "-p", "2", "-trimpath", "-buildvcs=false", "-ldflags", "-s -w -H windowsgui", "-o", str(guard), "."], cwd=ROOT / "native/windows-protection", env=env, check=True)
        guard.with_suffix(".exe.sha256").write_text(hashlib.sha256(guard.read_bytes()).hexdigest() + "\n", encoding="ascii")
    if args.target in ("android", "both"):
        env.pop("CGO_ENABLED", None)
        # NDK Windows target wrappers are .cmd files with an 8191-character
        # shell limit. Link libbox through the real clang.exe directly. This
        # surgical build-tool overlay leaves the module cache unchanged.
        mobile_env = gopath / "pkg/mod/github.com/sagernet/gomobile@v0.1.13/cmd/gomobile/env.go"
        original = mobile_env.read_text(encoding="utf-8")
        anchor = '\t\t\tandroidEnv[arch] = []string{'
        if original.count(anchor) != 1:
            raise RuntimeError("Pinned gomobile Android environment anchor changed")
        replacement = '\t\t\tif goos == "windows" { compilerDir:=filepath.Dir(clang); clang=filepath.Join(compilerDir,"clang.exe")+" --target="+toolchain.ClangPrefix(); clangpp=filepath.Join(compilerDir,"clang++.exe")+" --target="+toolchain.ClangPrefix() }\n' + anchor
        mobile_source = STAGE / "gomobile-source"
        if not mobile_source.exists():
            shutil.copytree(mobile_env.parents[2], mobile_source)
        patched = mobile_source / "cmd/gomobile/env.go"
        patched.chmod(0o666)
        patched.write_text(original.replace(anchor, replacement), encoding="utf-8", newline="\n")
        mobile_binary = gopath / "bin/gomobile-lernet.exe"
        subprocess.run([str(go_root / "bin/go.exe"), "build", "-p", "2", "-o", str(mobile_binary), "./cmd/gomobile"], cwd=mobile_source, env=env, check=True)
        output = STAGE / "libbox-expert.aar"
        subprocess.run([str(mobile_binary), "bind", "-o", str(output), "-target", "android/arm64,android/amd64", "-androidapi", "24", "-javapkg=io.nekohasekai", "-libname=box", "-trimpath", "-buildvcs=false", "-ldflags", LINK, "-tags", TAGS, "./experimental/libbox"], cwd=SOURCE, env=env, check=True)
        output.with_suffix(".aar.sha256").write_text(hashlib.sha256(output.read_bytes()).hexdigest() + "\n", encoding="ascii")


if __name__ == "__main__":
    main()
