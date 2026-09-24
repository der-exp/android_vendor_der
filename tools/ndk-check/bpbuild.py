#!/usr/bin/env python3
"""Build Android.bp cc modules with the NDK, outside the platform tree.

This is a check, not a replacement for Soong: it reads the same Android.bp
files the platform build reads and compiles exactly the sources, include dirs
and cflags written there, for aarch64-linux-android at a given API level. The
goal is to know, without a 300 GB checkout, that the code compiles and links
against bionic before the first real `m nft steer`.

Understood module types: cc_defaults, cc_library_static, cc_binary,
cc_library_headers, filegroup. Other modules (license, package, prebuilt_etc,
...) are parsed and ignored.
Understood properties: name, defaults, srcs (with globs and ":filegroup"
references), exclude_srcs, cflags, c_std, local_include_dirs,
export_include_dirs, header_libs, export_header_lib_headers, static_libs,
whole_static_libs, shared_libs (ignored: bionic's libc/libm/libdl are
implicit), system_ext_specific, relative_install_path and stem (reported only).

On top of the module's own cflags the compiler gets SOONG_CFLAGS below: an
approximation of the global flags Soong adds for arm64 device code (see
build/soong/cc/config/global.go and arm64_device.go in android16). They are
there so that code which only builds because a warning is not an error on the
host fails here too; they are not guaranteed to be a byte-for-byte copy.

Usage:
  bpbuild.py --out DIR [--api 34] [--static] [--no-werror] [--std gnu23]
             [--external DIR ...] DIR... -- TARGET...
Each DIR is a directory with an Android.bp; TARGET is a module name to build.
"""

import argparse
import glob
import os
import re
import shlex
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

SOONG_CFLAGS = [
    # commonGlobalCflags
    "-DANDROID", "-fmessage-length=0", "-W", "-Wall", "-Wno-unused",
    "-Winit-self", "-Wpointer-arith", "-Wunguarded-availability",
    "-Werror=date-time", "-Werror=int-conversion", "-Werror=pragma-pack",
    "-Werror=pragma-pack-suspicious-include", "-Werror=sizeof-array-div",
    "-Werror=string-plus-int", "-Werror=unreachable-code-loop-increment",
    "-Wno-error=deprecated-declarations",
    "-D__compiler_offsetof=__builtin_offsetof", "-faddrsig",
    "-fdebug-default-version=5", "-ffp-contract=off",
    "-fno-exceptions", "-Wno-multichar", "-ftrivial-auto-var-init=zero",
    # deviceGlobalCflags
    "-ffunction-sections", "-fdata-sections", "-fno-short-enums",
    "-funwind-tables", "-fstack-protector-strong", "-Wa,--noexecstack",
    "-D_FORTIFY_SOURCE=2", "-Wstrict-aliasing=2",
    "-Werror=return-type", "-Werror=address", "-Werror=sequence-point",
    "-Werror=format-security",
    # noOverrideGlobalCflags
    "-Werror=bool-operation", "-Werror=format-insufficient-args",
    "-Werror=implicit-int-float-conversion", "-Werror=int-in-bool-context",
    "-Werror=int-to-pointer-cast", "-Werror=pointer-to-int-cast",
    "-Werror=xor-used-as-pow", "-Wno-void-pointer-to-enum-cast",
    "-Wno-void-pointer-to-int-cast", "-Wno-pointer-to-int-cast",
    "-Werror=fortify-source", "-Werror=address-of-temporary",
    "-Werror=incompatible-function-pointer-types", "-Werror=null-dereference",
    "-Wno-tautological-constant-compare", "-Wno-tautological-type-limit-compare",
    "-Wno-implicit-int-float-conversion", "-Wno-enum-float-conversion",
    "-Wno-pessimizing-move", "-Wno-non-c-typedef-for-linkage",
    "-Wno-string-concatenation", "-Wno-bitwise-instead-of-logical",
    "-Wno-unused-but-set-variable", "-Wno-unused-but-set-parameter",
    "-Wno-array-parameter", "-Wno-gnu-offsetof-extensions",
    "-Werror=implicit-function-declaration",
    # arm64
    "-march=armv8-a", "-O2", "-fPIE",
]

# Added for modules under external/ (Soong: config.ExternalCflags).
SOONG_EXTERNAL_CFLAGS = [
    "-Wno-enum-compare", "-Wno-enum-compare-switch",
    "-Wno-null-pointer-arithmetic", "-Wno-null-dereference",
    "-Wno-pointer-compare", "-Wno-final-dtor-non-final-class", "-Wno-psabi",
    "-Wno-null-pointer-subtraction", "-Wno-deprecated-non-prototype",
    "-Wno-unused",
]

SOONG_LDFLAGS = [
    "-Wl,-z,noexecstack", "-Wl,-z,relro", "-Wl,-z,now", "-Wl,--build-id=md5",
    "-Wl,--fatal-warnings", "-Wl,--no-undefined-version", "-Wl,--gc-sections",
    "-Wl,--hash-style=gnu", "-pie",
]


# --- a small Blueprint reader ------------------------------------------------

TOKEN = re.compile(r'''
    (?P<ws>\s+|//[^\n]*|/\*.*?\*/) |
    (?P<str>"(?:\\.|[^"\\])*"|`[^`]*`) |
    (?P<num>-?\d+) |
    (?P<id>[A-Za-z_][A-Za-z0-9_]*) |
    (?P<p>[{}\[\]:,=+()])
''', re.S | re.X)


def tokenize(text, path):
    pos, out = 0, []
    while pos < len(text):
        m = TOKEN.match(text, pos)
        if not m:
            raise SystemExit(f"{path}: cannot parse at {text[pos:pos+40]!r}")
        pos = m.end()
        kind = m.lastgroup
        if kind == "ws":
            continue
        val = m.group(kind)
        if kind == "str":
            val = bytes(val[1:-1], "utf-8").decode("unicode_escape") \
                if val[0] == '"' else val[1:-1]
        out.append((kind, val))
    return out


class Parser:
    def __init__(self, toks, path):
        self.t, self.i, self.path, self.vars = toks, 0, path, {}

    def peek(self):
        return self.t[self.i] if self.i < len(self.t) else (None, None)

    def take(self, want=None):
        tok = self.peek()
        if want and tok[1] != want:
            raise SystemExit(f"{self.path}: expected {want!r}, got {tok[1]!r}")
        self.i += 1
        return tok

    def value(self):
        kind, val = self.take()
        if kind == "str":
            v = val
        elif kind == "num":
            v = int(val)
        elif kind == "id":
            v = {"true": True, "false": False}.get(val, None)
            if v is None:
                v = self.vars[val]
        elif val == "[":
            v = []
            while self.peek()[1] != "]":
                v.append(self.value())
                if self.peek()[1] == ",":
                    self.take()
            self.take("]")
        elif val == "{":
            v = self.props("}")
        else:
            raise SystemExit(f"{self.path}: unexpected {val!r}")
        while self.peek()[1] == "+":
            self.take()
            v = v + self.value()
        return v

    def props(self, end):
        d = {}
        while self.peek()[1] != end:
            _, key = self.take()
            self.take(":")
            d[key] = self.value()
            if self.peek()[1] == ",":
                self.take()
        self.take(end)
        return d

    def modules(self):
        mods = []
        while self.i < len(self.t):
            _, name = self.take()
            if self.peek()[1] == "=":
                self.take()
                self.vars[name] = self.value()
                continue
            self.take("{")
            mods.append((name, self.props("}")))
        return mods


# --- the build ---------------------------------------------------------------

def merge(base, over):
    out = dict(base)
    for k, v in over.items():
        if isinstance(v, list) and isinstance(out.get(k), list):
            out[k] = out[k] + v
        else:
            out[k] = v
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--ndk", default=os.environ.get("ANDROID_NDK", "/root/android-ndk"))
    ap.add_argument("--api", default="34")
    ap.add_argument("--std", default="gnu23",
                    help="Soong's default C standard (build/soong/cc/config/global.go)")
    ap.add_argument("--static", action="store_true",
                    help="link binaries -static (for qemu-user without linker64)")
    # Soong adds -Werror to every module outside its warning allowlist (cc/compiler.go), and
    # external/steer, external/der-mbedtls and external/nftables are not on it. The check is
    # only worth something if it fails where the real build fails, so -Werror is the default.
    ap.add_argument("--werror", action="store_true", default=True,
                    help="make every warning an error, as Soong does (default)")
    ap.add_argument("--no-werror", dest="werror", action="store_false",
                    help="report warnings without failing")
    ap.add_argument("--external", action="append", default=[],
                    help="treat this dir as external/ (adds ExternalCflags)")
    ap.add_argument("-j", type=int, default=os.cpu_count())
    ap.add_argument("-v", action="store_true")
    ap.add_argument("rest", nargs=argparse.REMAINDER)
    a = ap.parse_args()
    if "--" not in a.rest:
        ap.error("expected: DIR... -- TARGET...")
    k = a.rest.index("--")
    dirs, targets = a.rest[:k], a.rest[k + 1:]

    tc = os.path.join(a.ndk, "toolchains/llvm/prebuilt/linux-x86_64/bin")
    cc = os.path.join(tc, f"aarch64-linux-android{a.api}-clang")
    ar = os.path.join(tc, "llvm-ar")

    mods = {}
    for d in dirs:
        d = os.path.abspath(d)
        path = os.path.join(d, "Android.bp")
        p = Parser(tokenize(open(path).read(), path), path)
        for typ, props in p.modules():
            if "name" in props:
                mods[props["name"]] = (typ, props, d)

    def resolved(name, seen=()):
        typ, props, d = mods[name]
        out = {}
        for dn in props.get("defaults", []):
            out = merge(out, resolved(dn, seen + (name,))[1])
        return typ, merge(out, {k: v for k, v in props.items() if k != "defaults"}), d

    os.makedirs(a.out, exist_ok=True)
    built = {}

    def exported_includes(name):
        typ, props, d = resolved(name)
        inc = [os.path.join(d, x) for x in props.get("export_include_dirs", [])]
        for h in props.get("export_header_lib_headers", []):
            inc += exported_includes(h)
        return inc

    # srcs as Soong reads them: paths relative to the module directory, globs
    # ("library/*.c", "**" too), and ":name" references to a filegroup, whose
    # files stay relative to the filegroup's own directory. exclude_srcs is
    # applied after expansion, the same way.
    def expand(entries, d):
        out = []
        for e in entries:
            if e.startswith(":"):
                ftyp, fprops, fd = resolved(e[1:])
                if ftyp != "filegroup":
                    raise SystemExit(f"{e}: only filegroup references are handled")
                out += expand_srcs(fprops, fd)
            elif any(c in e for c in "*?["):
                out += sorted(glob.glob(os.path.join(d, e), recursive=True))
            else:
                out.append(os.path.join(d, e))
        return out

    def expand_srcs(props, d):
        excl = set(expand(props.get("exclude_srcs", []), d))
        return [x for x in expand(props.get("srcs", []), d) if x not in excl]

    def run(cmd):
        if a.v:
            print(" ".join(shlex.quote(c) for c in cmd))
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.stderr:
            sys.stderr.write(r.stderr)
        if r.returncode:
            raise SystemExit(f"FAILED: {' '.join(cmd[:3])} ... ({r.returncode})")

    def compile_module(name):
        typ, props, d = resolved(name)
        deps = props.get("static_libs", []) + props.get("whole_static_libs", [])
        flags = list(SOONG_CFLAGS)
        if any(os.path.abspath(e) == d for e in a.external):
            flags += SOONG_EXTERNAL_CFLAGS
        flags.append("-std=" + props.get("c_std", a.std))
        flags += ["-I" + os.path.join(d, x)
                  for x in props.get("local_include_dirs", []) +
                  props.get("export_include_dirs", [])]
        flags += ["-I" + d]  # Soong adds the module directory itself
        for dep in deps + props.get("header_libs", []):
            flags += ["-I" + x for x in exported_includes(dep)]
        flags += props.get("cflags", [])
        if a.werror:
            flags.append("-Werror")
        objdir = os.path.join(a.out, "obj", name)
        objs = []
        jobs = []
        for src in expand_srcs(props, d):
            rel = os.path.relpath(src, d)
            if rel.startswith(".."):          # a filegroup from another directory
                rel = os.path.join("_fg", src.lstrip("/"))
            o = os.path.join(objdir, re.sub(r"\.c$", ".o", rel))
            os.makedirs(os.path.dirname(o), exist_ok=True)
            objs.append(o)
            jobs.append([cc] + flags + ["-c", src, "-o", o])
        with ThreadPoolExecutor(a.j) as ex:
            for f in [ex.submit(run, j) for j in jobs]:
                f.result()
        return typ, props, objs

    def build(name):
        if name in built:
            return built[name]
        typ, props, objs = compile_module(name)
        whole = props.get("whole_static_libs", [])
        static = props.get("static_libs", [])
        for dep in whole + static:
            build(dep)
        if typ == "cc_library_static":
            lib = os.path.join(a.out, name + ".a")
            if os.path.exists(lib):
                os.remove(lib)
            members = objs + [o for w in whole for o in built[w]["objs"]]
            run([ar, "rcs", lib] + members)
            # Soong links a static library's own static deps into whatever
            # binary finally uses it; carry the list up.
            trans = []
            for dep in static:
                trans += [dep] + built[dep]["trans"]
            for w in whole:
                trans += built[w]["trans"]
            built[name] = {"objs": members, "lib": lib, "trans": trans}
        elif typ == "cc_binary":
            order = []
            for dep in static:
                for x in [dep] + built[dep]["trans"]:
                    if x not in order:
                        order.append(x)
            libs = [built[x]["lib"] for x in order]
            where = "system_ext" if props.get("system_ext_specific") else \
                    "vendor" if props.get("vendor") or props.get("soc_specific") else "system"
            exe = os.path.join(a.out, props.get("stem", name))
            ld = [cc] + objs + ["-Wl,--start-group"] + libs + ["-Wl,--end-group"] + \
                SOONG_LDFLAGS + (["-static"] if a.static else [])
            if a.static:
                ld.remove("-pie")
            run(ld + ["-o", exe])
            built[name] = {"objs": objs, "exe": exe}
            sub = props.get("relative_install_path")
            print(f"{name}: {exe}  (installs to /{where}/bin{'/' + sub if sub else ''}"
                  f"/{props.get('stem', name)})")
        else:
            raise SystemExit(f"{name}: module type {typ} is not handled")
        return built[name]

    for t in targets:
        build(t)


if __name__ == "__main__":
    main()
