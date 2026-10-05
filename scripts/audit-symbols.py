#!/usr/bin/env python3
"""
audit-symbols.py v2 — M3 fix #2 audit (correct provider model).

Provider set for a guest ELF undefined symbol:
  1. bionic exports           (AOSP libc/libm/libdl map.txt)          [GO dlsym(self)]
  2. libbox64.so exports      (current .dynsym)                       [GOM dlsym(box64lib)]
  3. GOM/GOWM impl names      (my_* exported post --export-dynamic)   [GOM post-fix]
  4. exports of real guest libs loaded in-process (libavutil_sos, libavcodec_sos,
     libfreetype_sos, native libSDL2_sos_native)                      [inter-guest]
Symbols registered as GO in wrapped maps are NOT providers by themselves: the
GO path resolves via dlsym(dlopen(NULL)) = bionic-only on Android. This is the
exact KB-002 failure class.
Weak undefined symbols are ignored (resolved to 0 harmlessly).

Exit 0 only if every non-weak undefined symbol of every guest ELF is covered.
"""
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

APKLIBS = Path("/home/z/my-project/build/apklibs")
BOX64_SRC = Path("/home/z/my-project/build/box64-src")
GUEST_ELFS = ["libanyhost64.so", "libavcodec_sos.so", "libavutil_sos.so", "libfreetype_sos.so"]
GUEST_PROVIDERS = ["libavutil_sos.so", "libavcodec_sos.so", "libfreetype_sos.so", "libSDL2_sos_native.so"]


def dynsyms(elf: str):
    """-> {name: is_weak} for defined+undefined symbols."""
    out = subprocess.run(
        ["readelf", "-W", "--dyn-syms", str(APKLIBS / elf)],
        capture_output=True, text=True, check=True).stdout
    syms = {}
    for line in out.splitlines():
        cols = line.split()
        if len(cols) >= 8 and cols[6] in ("UND", "GLOBAL", "WEAK") or len(cols) >= 8:
            name = cols[7].split("@")[0]
            if not name or name == "Name":
                continue
            binding = cols[4] if len(cols) >= 7 else ""
            ndx = cols[6]
            if ndx == "UND":
                syms.setdefault(name, (True, binding == "WEAK"))
            else:
                syms.setdefault(name, (False, binding == "WEAK"))
    return syms


def elf_exports(elf: str) -> set[str]:
    return {n for n, (und, _w) in dynsyms(elf).items() if not und}


def undefined_nonweak(elf: str) -> set[str]:
    return {n for n, (und, weak) in dynsyms(elf).items() if und and not weak}


def bionic_exports() -> set[str]:
    names = set()
    urls = [
        "https://raw.githubusercontent.com/aosp-mirror/platform_bionic/master/libc/libc.map.txt",
        "https://raw.githubusercontent.com/aosp-mirror/platform_bionic/master/libm/libm.map.txt",
        "https://raw.githubusercontent.com/aosp-mirror/platform_bionic/master/libdl/libdl.map.txt",
    ]
    for url in urls:
        try:
            with urllib.request.urlopen(url, timeout=30) as r:
                text = r.read().decode()
        except Exception as e:
            print(f"WARN: failed to fetch {url.rsplit('/',1)[-1]}: {e}")
            continue
        for m in re.finditer(r"^\s+([A-Za-z_][A-Za-z0-9_]*);", text, re.M):
            names.add(m.group(1))
    print(f"bionic exports parsed: {len(names)}")
    return names


def wrapped_maps() -> tuple[set[str], set[str]]:
    """-> (all GO/GOM/etc names, GOM-only names = post-fix my_* providers)."""
    allnames, gomnames = set(), set()
    pat_all = re.compile(r"^(?:GOM|GOWM|GO|GOW|GO2|GOW2|DATA|DATAV|DATAB|DATAM)\(([A-Za-z0-9_]+),")
    pat_gom = re.compile(r"^(?:GOM|GOWM|DATAM)\(([A-Za-z0-9_]+),")
    for header in BOX64_SRC.glob("src/wrapped/wrapped*_private.h"):
        for line in header.read_text(errors="ignore").splitlines():
            m = pat_all.match(line.strip())
            if m:
                allnames.add(m.group(1))
            m2 = pat_gom.match(line.strip())
            if m2:
                gomnames.add(m2.group(1))
    print(f"box64 wrapped names: {len(allnames)} total, {len(gomnames)} GOM (my_* providers post-fix)")
    return allnames, gomnames


def main() -> int:
    bionic = bionic_exports()
    _, gom = wrapped_maps()
    box64exp = elf_exports("libbox64.so")
    print(f"libbox64.so current exports: {len(box64exp)}")
    guestprov: set[str] = set()
    for g in GUEST_PROVIDERS:
        guestprov |= elf_exports(g)
    print(f"guest-lib cross exports: {len(guestprov)}")

    provided = bionic | box64exp | gom | guestprov
    rc = 0
    for elf in GUEST_ELFS:
        undef = undefined_nonweak(elf)
        need = re.findall(r"Shared library: \[(.*?)\]", subprocess.run(
            ["readelf", "-d", str(APKLIBS / elf)],
            capture_output=True, text=True, check=True).stdout)
        uncovered = sorted(s for s in undef if s not in provided)
        print(f"\n=== {elf} ===")
        print(f"  DT_NEEDED: {need}")
        print(f"  undefined(non-weak): {len(undef)}, UNCOVERED on bionic: {len(uncovered)}")
        if uncovered:
            rc = 1
            for s in uncovered:
                in_wrapped = s
                print(f"    UNCOVERED: {s}")
    print("\n=== SUMMARY ===")
    print("provider model: bionic ∪ box64 exports ∪ GOM(my_*, post --export-dynamic) ∪ guest-lib exports")
    if rc:
        print("STATUS: GAPS FOUND — these need shims in the box64 patch (GO names w/o bionic backing)")
    else:
        print("STATUS: FULL COVERAGE")
    return rc


if __name__ == "__main__":
    sys.exit(main())
