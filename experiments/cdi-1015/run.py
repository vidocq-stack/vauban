#!/usr/bin/env python3
"""Build CDI #1015 fixtures and run each scenario in a fresh, ordinary module-path JVM."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time
import zipfile


HERE = Path(__file__).resolve().parent
VAUBAN = HERE.parent.parent
TARGET = HERE / "target"
MARKER = ".cdi-1015-generated"
VERSION = "0.4.0-SNAPSHOT"
HEISENBERG_COMMIT = "74a98a7515ab84106d958793dc99a9af353250af"
# SHA256 of the released binary, independently checked against Central's published SHA1.
CDI_ORIGINAL_SHA256 = "c42c808f17925129a0800f618febe050d966e181a4c7384c8a5e7a0283d68699"
CDI_UPSTREAM_SHA1 = "fed9518709d33252bfe0817fe61ad4dfd1b2e848"
CDI_CHECKSUM_URL = (
    "https://repo.maven.apache.org/maven2/jakarta/enterprise/jakarta.enterprise.cdi-api/"
    "4.1.0/jakarta.enterprise.cdi-api-4.1.0.jar.sha1"
)
SCENARIOS = {
    "donation": "donation",
    "authorized": "relay",
    "unauthorized": "relay",
    "wrong-recipient": "relay",
    "missing-opening": "closed",
    "extension": "relay",
    "unauthorized-extension": "relay",
    "shared-module": "relay",
    "spoofed-provider": "relay",
}
PROXY_SCENARIOS = ("proxy-with-reads", "proxy-without-reads", "proxy-interface-before-reads")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def java_sources(directory):
    files = sorted(directory.rglob("*.java"))
    if not files:
        raise RuntimeError(f"No Java sources in {directory}")
    return files


def jar_inventory(directory):
    return [
        {"file": jar.name, "sha256": sha256(jar), "bytes": jar.stat().st_size}
        for jar in sorted(directory.glob("*.jar"))
    ]


class Experiment:
    def __init__(self, args):
        self.args = args
        self.env = os.environ.copy()
        removed = []
        # An inherited launcher setting must not silently grant the access under examination.
        for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"):
            if self.env.pop(key, None):
                removed.append(key)
        self.env["JAVA_HOME"] = str(args.java_home)
        self.env["PATH"] = str(args.java_home / "bin") + os.pathsep + self.env.get("PATH", "")
        self.java = args.java_home / "bin" / "java"
        self.javac = args.java_home / "bin" / "javac"
        self.jar = args.java_home / "bin" / "jar"
        self.maven = shlex.split(args.maven)
        if not self.maven:
            raise RuntimeError("--maven must name a Maven command")
        self.maven += ["-B", "-ntp"]
        if args.offline:
            self.maven.append("--offline")
        if args.maven_repo:
            self.maven.append(f"-Dmaven.repo.local={args.maven_repo}")
        self.deps = TARGET / "runtime-deps"
        self.logs = TARGET / "logs"
        self.manifest = {
            "state": "running",
            "started_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "python": sys.version,
            "java_home": str(args.java_home),
            "removed_environment_keys": removed,
            "heisenberg_dependency_mode": (
                "rebuilt-pinned-source" if args.heisenberg_source else "resolved-snapshots"
            ),
            "commands": [],
            "source_artifacts": [],
            "scenarios": [],
        }

    def command(self, label, command, cwd=VAUBAN, check=True):
        command = [str(arg) for arg in command]
        log = self.logs / f"{label}.log"
        started = time.monotonic()
        print(f"[{label}]", flush=True)
        with log.open("w", encoding="utf-8") as output:
            output.write("$ " + shlex.join(command) + "\n\n")
            output.flush()
            process = subprocess.run(
                command, cwd=cwd, env=self.env, stdout=output,
                stderr=subprocess.STDOUT, check=False,
            )
        self.manifest["commands"].append({
            "label": label,
            "argv": command,
            "cwd": str(cwd),
            "exit_code": process.returncode,
            "seconds": round(time.monotonic() - started, 3),
            "log": str(log.relative_to(TARGET)),
        })
        if check and process.returncode:
            raise RuntimeError(f"{label} failed with exit {process.returncode}; see {log}")
        return process.returncode, log

    def record_source(self, label, directory):
        _, revision_log = self.command(
            f"{label}-revision", ["git", "-C", directory, "rev-parse", "HEAD"])
        revision = revision_log.read_text(encoding="utf-8").strip().splitlines()[-1]
        _, status_log = self.command(
            f"{label}-status", ["git", "-C", directory, "status", "--porcelain",
                               "--untracked-files=normal"])
        status = status_log.read_text(encoding="utf-8").split("\n\n", 1)[1].strip()
        self.manifest[label] = {
            "directory": str(directory), "commit": revision, "status": status,
        }

    def check_java(self):
        for tool in (self.java, self.javac, self.jar):
            if not tool.is_file():
                raise RuntimeError(f"Missing JDK tool: {tool}")
        _, log = self.command("java-version", [self.java, "--version"])
        version = log.read_text(encoding="utf-8").split("\n\n", 1)[1].strip()
        if not re.match(r"(?:openjdk|java)\s+25(?:[.\s-]|$)", version):
            raise RuntimeError(f"This experiment requires JDK 25; found {version.splitlines()[0]}")
        self.manifest["java_version"] = version
        self.command("maven-version", self.maven + ["--version"])

    def build_dependencies(self):
        self.record_source("vauban", VAUBAN)
        self.command("build-vauban", self.maven + [
            "-pl", "vauban-core,vauban-processor", "-am", "-Dmaven.test.skip=true",
            "clean", "package",
        ])
        if self.args.heisenberg_source:
            source = self.args.heisenberg_source
            self.record_source("heisenberg", source)
            if self.manifest["heisenberg"]["commit"] != HEISENBERG_COMMIT:
                raise RuntimeError(
                    f"Heisenberg must be checked out at baseline {HEISENBERG_COMMIT}; "
                    f"found {self.manifest['heisenberg']['commit']}")
            if self.manifest["heisenberg"]["status"]:
                raise RuntimeError(
                    "The pinned Heisenberg checkout must be clean, including untracked files; "
                    "see logs/heisenberg-status.log")
            self.command("build-heisenberg", self.maven + [
                "-pl", "heisenberg-cdi-vauban", "-am", "-Dmaven.test.skip=true",
                "clean", "package",
            ], cwd=source)
        self.deps.mkdir()
        self.command("resolve-dependencies", self.maven + [
            "-f", HERE / "dependencies" / "pom.xml", "dependency:copy-dependencies",
            f"-DoutputDirectory={self.deps}",
        ])
        if not list(self.deps.glob("*.jar")):
            raise RuntimeError("Maven resolved no dependency JARs")
        self.manifest["resolved_dependencies"] = jar_inventory(self.deps)
        self.replace_source_artifacts("io.vidocq.vauban", VAUBAN)
        if self.args.heisenberg_source:
            self.replace_source_artifacts("io.vidocq.heisenberg", self.args.heisenberg_source)

    def replace_source_artifacts(self, group, source):
        resolved = sorted(self.deps.glob(f"{group}.*-{VERSION}.jar"))
        if not resolved:
            raise RuntimeError(f"No resolved {group} artifacts to replace from {source}")
        for destination in resolved:
            artifact = destination.name.removeprefix(group + ".").removesuffix(f"-{VERSION}.jar")
            built = source / artifact / "target" / f"{artifact}-{VERSION}.jar"
            if not built.is_file():
                raise RuntimeError(f"Missing reactor artifact {built}; refusing a stale snapshot")
            shutil.copy2(built, destination)
            self.manifest["source_artifacts"].append({
                "coordinates": f"{group}:{artifact}:{VERSION}",
                "source": str(built),
                "sha256": sha256(built),
            })

    def patch_jar(self, label, module_name, jar_name, source_roots):
        artifact = self.deps / jar_name
        if not artifact.is_file():
            raise RuntimeError(f"Expected dependency missing: {artifact}")
        sources = [source for root in source_roots for source in java_sources(root)]
        with zipfile.ZipFile(artifact) as archive:
            signatures = [
                name for name in archive.namelist()
                if re.match(r"META-INF/[^/]+\.(SF|RSA|DSA|EC)$", name, re.IGNORECASE)
            ]
            if len(set(archive.namelist())) != len(archive.namelist()):
                raise RuntimeError(f"Duplicate ZIP entries in {artifact.name}")
            original_entries = {
                name: hashlib.sha256(archive.read(name)).hexdigest()
                for name in archive.namelist()
            }
        if signatures:
            raise RuntimeError(f"Cannot extend signed JAR {artifact.name}: {signatures}")
        original = sha256(artifact)
        if module_name == "jakarta.cdi":
            if original != CDI_ORIGINAL_SHA256:
                raise RuntimeError(
                    f"CDI 4.1.0 input is not the verified release binary: {original}")
            self.manifest["cdi_release_verification"] = {
                "sha256": CDI_ORIGINAL_SHA256,
                "published_sha1": CDI_UPSTREAM_SHA1,
                "published_sha1_url": CDI_CHECKSUM_URL,
            }
        output = TARGET / "patch-classes" / label
        output.mkdir(parents=True)
        self.command(f"compile-{label}", [
            self.javac, "--release", "25", "-proc:none", "--module-path", self.deps,
            "--patch-module", module_name + "=" + os.pathsep.join(map(str, source_roots)),
            "--add-modules", module_name, "-d", output, *sources,
        ])
        self.command(f"package-{label}", [
            self.jar, "--update", "--file", artifact, "-C", output, ".",
        ])
        with zipfile.ZipFile(artifact) as archive:
            if len(set(archive.namelist())) != len(archive.namelist()):
                raise RuntimeError(f"Duplicate ZIP entries after extending {artifact.name}")
            patched_entries = {
                name: hashlib.sha256(archive.read(name)).hexdigest()
                for name in archive.namelist()
            }
        changed = [
            name for name, digest in original_entries.items()
            if patched_entries.get(name) != digest
        ]
        additions = sorted(patched_entries.keys() - original_entries.keys())
        if changed:
            raise RuntimeError(
                f"Extending {artifact.name} changed or removed original entries: {changed}")
        if any(not name.endswith((".class", "/")) for name in additions):
            raise RuntimeError(f"Unexpected additions while extending {artifact.name}: {additions}")
        self.manifest.setdefault("experimental_jar_changes", []).append({
            "module": module_name, "jar": artifact.name,
            "original_sha256": original, "experimental_sha256": sha256(artifact),
            "source_roots": [str(root.relative_to(HERE)) for root in source_roots],
            "original_entries_preserved": True,
            "original_entry_count": len(original_entries),
            "added_class_entries": [name for name in additions if name.endswith(".class")],
        })

    def compile_fixtures(self):
        self.patch_jar(
            "api-patch", "jakarta.cdi",
            "jakarta.enterprise.jakarta.enterprise.cdi-api-4.1.0.jar",
            [HERE / "api-patch"],
        )
        core_roots = [HERE / "core-patch"]
        proxy_core = HERE / "proxy-fixtures" / "core-patch"
        if proxy_core.is_dir():
            core_roots.append(proxy_core)
        self.patch_jar(
            "core-patch", "io.vidocq.vauban.core",
            f"io.vidocq.vauban.vauban-core-{VERSION}.jar", core_roots,
        )
        self.manifest["runtime_dependencies"] = jar_inventory(self.deps)
        processor_path = os.pathsep.join(str(path) for path in sorted(self.deps.glob("*.jar")))
        beans = HERE / "fixtures" / "beans"
        bean_sources = [
            path for path in java_sources(beans / "src" / "main" / "java")
            if path.name != "module-info.java"
        ]
        for variant in ("donation", "relay", "closed"):
            output = TARGET / "modules" / f"beans-{variant}"
            generated = TARGET / "generated" / f"beans-{variant}"
            output.mkdir(parents=True)
            generated.mkdir(parents=True)
            self.command(f"compile-beans-{variant}", [
                self.javac, "--release", "25", "--module-path", self.deps,
                "--processor-path", processor_path,
                "-processor", "io.vidocq.vauban.processor.VaubanProcessor",
                "-Avauban.validation=false", "-s", generated, "-d", output,
                beans / "descriptors" / variant / "module-info.java", *bean_sources,
            ])
        extension = TARGET / "modules" / "extension"
        extension.mkdir()
        self.command("compile-extension", [
            self.javac, "--release", "25", "-proc:none", "-d", extension,
            *java_sources(HERE / "fixtures" / "extension" / "src" / "main" / "java"),
        ])
        app = TARGET / "modules" / "app"
        app.mkdir()
        self.command("compile-app", [
            self.javac, "--release", "25", "-proc:none", "--module-path",
            os.pathsep.join(map(str, [
                self.deps, TARGET / "modules" / "beans-relay", extension,
            ])), "-d", app,
            *java_sources(HERE / "fixtures" / "app" / "src" / "main" / "java"),
        ])
        proxy_beans = TARGET / "modules" / "proxy-beans"
        proxy_beans.mkdir()
        self.command("compile-proxy-beans", [
            self.javac, "--release", "25", "-proc:none", "--module-path", self.deps,
            "-d", proxy_beans, *java_sources(HERE / "proxy-fixtures" / "beans"),
        ])

    def run_scenarios(self):
        failures = []
        for scenario in self.args.scenario or [*SCENARIOS, *PROXY_SCENARIOS]:
            if scenario in PROXY_SCENARIOS:
                variant = "proxy"
                module_path = os.pathsep.join(map(str, [
                    self.deps, TARGET / "modules" / "proxy-beans",
                ]))
                launch = [
                    "--add-modules", "experiment.proxy.beans", "--module",
                    "io.vidocq.vauban.core/"
                    "io.vidocq.vauban.core.container.ExperimentalProxyProbe",
                    scenario.removeprefix("proxy-"),
                ]
            else:
                variant = SCENARIOS[scenario]
                module_path = os.pathsep.join(map(str, [
                    self.deps, TARGET / "modules" / f"beans-{variant}",
                    TARGET / "modules" / "extension", TARGET / "modules" / "app",
                ]))
                launch = [
                    "--module", "experiment.app/io.vidocq.vauban.experiment.app.Main", scenario,
                ]
            code, log = self.command(f"scenario-{scenario}", [
                self.java, "-ea", "-XX:+DisableAttachMechanism", "--module-path", module_path,
                *launch,
            ], cwd=TARGET, check=False)
            output = log.read_text(encoding="utf-8")
            sentinel = re.search(r"^PASS " + re.escape(scenario) + r"(?:\s|:|$)",
                                 output, re.MULTILINE) is not None
            passed = code == 0 and sentinel
            result = {
                "scenario": scenario, "bean_variant": variant,
                "passed": passed, "exit_code": code, "pass_sentinel": sentinel,
                "log": str(log.relative_to(TARGET)),
            }
            self.manifest["scenarios"].append(result)
            print(f"  {'PASS' if passed else 'FAIL'} {scenario}", flush=True)
            if not passed:
                failures.append(scenario)
        if failures:
            raise RuntimeError("Failed scenarios: " + ", ".join(failures))

    def save_report(self):
        self.manifest["finished_utc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        self.manifest["experiment_sources"] = [
            {"file": str(path.relative_to(HERE)), "sha256": sha256(path)}
            for path in sorted(HERE.rglob("*"))
            if path.is_file() and TARGET not in path.parents and "__pycache__" not in path.parts
        ]
        (TARGET / "manifest.json").write_text(
            json.dumps(self.manifest, indent=2) + "\n", encoding="utf-8")
        (TARGET / "results.json").write_text(
            json.dumps(self.manifest["scenarios"], indent=2) + "\n", encoding="utf-8")


def prepare_target():
    if TARGET.is_symlink():
        raise RuntimeError(f"Refusing to clear symlink {TARGET}")
    if TARGET.exists():
        if not (TARGET / MARKER).is_file():
            raise RuntimeError(f"Refusing to clear unrecognized directory {TARGET}")
        shutil.rmtree(TARGET)
    (TARGET / "logs").mkdir(parents=True)
    (TARGET / MARKER).write_text("Generated by experiments/cdi-1015/run.py\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"),
                        help="JDK 25 installation (default: JAVA_HOME)")
    parser.add_argument("--maven", default="mvn", help="Maven command, e.g. 'mvn' or '/path/to/mvn'")
    parser.add_argument("--maven-repo", type=Path, help="Optional Maven local repository")
    parser.add_argument("--offline", action="store_true", help="Use cached Maven artifacts only")
    parser.add_argument("--heisenberg-source", type=Path,
                        help="Also package Heisenberg source and use its reactor artifacts")
    parser.add_argument("--scenario", action="append", choices=[*SCENARIOS, *PROXY_SCENARIOS],
                        help="Run selected scenario(s); default: all scenarios")
    args = parser.parse_args()
    if not args.java_home:
        parser.error("Set JAVA_HOME to JDK 25 or pass --java-home")
    args.java_home = args.java_home.expanduser().resolve()
    if args.maven_repo:
        args.maven_repo = args.maven_repo.expanduser().resolve()
    if args.heisenberg_source:
        args.heisenberg_source = args.heisenberg_source.expanduser().resolve()
        if not (args.heisenberg_source / "heisenberg-cdi-vauban" / "pom.xml").is_file():
            parser.error("--heisenberg-source must name the Heisenberg reactor root")
    experiment = None
    try:
        prepare_target()
        experiment = Experiment(args)
        experiment.check_java()
        experiment.build_dependencies()
        experiment.compile_fixtures()
        experiment.run_scenarios()
        experiment.manifest["state"] = "passed"
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        if experiment:
            experiment.manifest["state"] = "failed"
            experiment.manifest["error"] = str(error)
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    finally:
        if experiment:
            experiment.save_report()
            print(f"Evidence: {TARGET / 'manifest.json'}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
