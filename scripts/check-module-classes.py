#!/usr/bin/env python3
"""Checks that every IntelliJ class CodeLanes' jars use can be loaded by the jar's classloader in a given IDE.

Usage: scripts/check-module-classes.py <plugin zip> <ide dir>[+<extra plugin dir>...] ...

The tests run on one flat classpath, so they can't catch this (0.2.0 crashed with NoClassDefFoundError on a class
of the Java plugin's `lib/modules/intellij.java.indexing.jar`). At runtime a jar of the plugin sees:
  - the IDE core: `lib/*.jar` (not `lib/modules/*.jar`),
  - the main jars (`plugins/<x>/lib/*.jar`, not `plugins/<x>/lib/modules/`) of the plugins its descriptor depends on,
  - the jars of the content modules its descriptor depends on (`<module name="..."/>`).
For each jar of the plugin this prints `ok`, or the classes it uses that only exist in a `lib/modules/` jar it
doesn't depend on (exit 1 if any). A jar whose plugin dependency isn't in the IDE (e.g. the Java module in PhpStorm)
is skipped: the IDE never loads it.

`<ide dir>+<plugin dir>` adds a plugin that the IDE doesn't bundle, e.g. the Marketplace PythonCore for IntelliJ IDEA:
  idea-2026.1-aarch64+PythonCore-261.22158.277/python-ce

Stdlib only; uses `javap` from $JAVA_HOME (or the PATH).
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

PREFIXES = ("com/intellij/", "com/jetbrains/", "org/jetbrains/")
# `#25 = Class   #24   // com/intellij/openapi/vfs/VirtualFile`; arrays read `// "[Lcom/...;"`.
CLASS_ENTRY = re.compile(r"= Class\s+#\d+\s+//\s*(\S+)")
DESCRIPTOR_TYPE = re.compile(r"L((?:com|org)/(?:intellij|jetbrains)/[\w/$]+);")


def javap() -> str:
    home = os.environ.get("JAVA_HOME")
    if home and os.path.exists(os.path.join(home, "bin", "javap")):
        return os.path.join(home, "bin", "javap")
    found = shutil.which("javap")
    if not found:
        sys.exit("javap not found: set JAVA_HOME")
    return found


def referenced_classes(jar: str) -> set:
    """Every com.intellij/com.jetbrains/org.jetbrains class the jar's classes name (Class entries and descriptors)."""
    with zipfile.ZipFile(jar) as z:
        classes = [n[:-6] for n in z.namelist() if n.endswith(".class") and not n.endswith("module-info.class")]
    refs = set()
    for start in range(0, len(classes), 200):
        run = subprocess.run([javap(), "-v", "-cp", jar] + [c.replace("/", ".") for c in classes[start:start + 200]],
                             capture_output=True, text=True)
        if run.returncode != 0:
            sys.exit(f"javap failed on {os.path.basename(jar)} (is JAVA_HOME a JDK?): {run.stderr.strip()[:500]}")
        out = run.stdout
        for line in out.splitlines():
            match = CLASS_ENTRY.search(line)
            if match:
                name = match.group(1).strip('"')
                name = name.lstrip("[")
                if name.startswith("L") and name.endswith(";"):
                    name = name[1:-1]
                refs.add(name)
            if "= Utf8" in line:
                refs.update(DESCRIPTOR_TYPE.findall(line))
    return {r for r in refs if r.startswith(PREFIXES)}


def descriptor_dependencies(text: str):
    """(required plugin ids, optional plugin ids, module names) a descriptor depends on."""
    root = ET.fromstring(text)
    required = {d.text.strip() for d in root.findall("depends") if d.text and d.get("optional") != "true"}
    optional = {d.text.strip() for d in root.findall("depends") if d.text and d.get("optional") == "true"}
    modules = set()
    for deps in root.findall("dependencies"):
        required.update(p.get("id") for p in deps.findall("plugin"))
        modules.update(m.get("name") for m in deps.findall("module"))
    return required, optional, modules


class Ide:
    """Where each class lives in an IDE (plus extra plugin dirs) and which plugin ids it has."""

    def __init__(self, spec: str):
        parts = spec.split("+")
        home = parts[0]
        if os.path.isdir(os.path.join(home, "Contents", "lib")):
            home = os.path.join(home, "Contents")
        self.name = os.path.basename(parts[0].rstrip("/")) + "".join("+" + os.path.basename(p.rstrip("/")) for p in parts[1:])
        self.locations = {}  # class → [(kind, owner)], kind: core | core-module | plugin | plugin-module
        self.plugin_ids = {"com.intellij"}
        self.plugin_dirs = {}  # plugin id → the plugins/ dir holding its main jars
        self.module_names = set()  # content modules: lib/modules jars and modules declared in plugins' <content>
        self.unreadable = []
        self._index_dir(os.path.join(home, "lib"), "core", None, home)
        self._index_dir(os.path.join(home, "lib", "modules"), "core-module", None, home)
        plugin_roots = [os.path.join(home, "plugins", p) for p in sorted(os.listdir(os.path.join(home, "plugins")))]
        for plugin in plugin_roots + parts[1:]:
            self._index_plugin(plugin)

    def _index_dir(self, directory: str, kind: str, owner, base: str):
        if not os.path.isdir(directory):
            return
        for jar in sorted(os.listdir(directory)):
            if not jar.endswith(".jar"):
                continue
            path = os.path.join(directory, jar)
            try:
                with zipfile.ZipFile(path) as z:
                    names = z.namelist()
                    if kind == "plugin" and "META-INF/plugin.xml" in names:
                        # A plugin is known by its <id> and by its aliases (<module value="..."/>).
                        text = z.read("META-INF/plugin.xml").decode("utf-8", "replace")
                        ids = re.findall(r"<id>\s*([^<\s]+)\s*</id>", text)[:1] + re.findall(r'<module\s+value="([^"]+)"', text)
                        for plugin_id in ids:
                            self.plugin_ids.add(plugin_id)
                            self.plugin_dirs.setdefault(plugin_id, owner)
                        # Embedded modules (e.g. PythonCore 2025.2) have no jar of their own: read <content>.
                        for content in re.findall(r"<content\b.*?</content>", re.sub(r"<!\[CDATA\[.*?\]\]>", "", text, flags=re.S), re.S):
                            self.module_names.update(re.findall(r'<module\s+name="([^"]+)"', content))
            except (zipfile.BadZipFile, OSError) as error:
                self.unreadable.append(f"{os.path.relpath(path, base)}: {error}")
                continue
            module = jar[:-4] if kind.endswith("module") else None
            if module:
                self.module_names.add(module)
            for n in names:
                if n.endswith(".class"):
                    self.locations.setdefault(n[:-6], []).append((kind, owner, module, os.path.relpath(path, base)))

    def _index_plugin(self, plugin: str):
        name = os.path.basename(plugin.rstrip("/"))
        base = os.path.dirname(os.path.dirname(plugin.rstrip("/")))  # paths read plugins/<name>/lib/...
        self._index_dir(os.path.join(plugin, "lib"), "plugin", name, base)
        self._index_dir(os.path.join(plugin, "lib", "modules"), "plugin-module", name, base)

    def has_plugin(self, plugin_id: str) -> bool:
        return plugin_id in self.plugin_ids or plugin_id.startswith("com.intellij.modules.")


def check(refs: set, own: set, plugins: set, modules: set, ide: Ide):
    """(classes the jar uses that its classloader can't see, grouped by where they do live; classes not in the IDE)."""
    dep_dirs = {ide.plugin_dirs.get(p) for p in plugins}
    problems = {}
    missing = []
    for ref in sorted(refs - own):
        places = ide.locations.get(ref)
        if not places:
            missing.append(ref)
            continue
        visible = any(kind == "core"
                      or (kind == "plugin" and owner in dep_dirs)
                      or (module is not None and module in modules)
                      for kind, owner, module, _ in places)
        if visible:
            continue
        module_jars = [where for kind, _, _, where in places if kind.endswith("module")]
        if module_jars:
            problems.setdefault("only in " + ", ".join(sorted(set(module_jars))), []).append(ref)
        else:
            problems.setdefault("in a plugin it doesn't depend on: " + ", ".join(sorted({o for _, o, _, _ in places})), []).append(ref)
    return problems, missing


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    plugin_zip, ide_specs = sys.argv[1], sys.argv[2:]
    for part in (p for spec in ide_specs for p in spec.split("+")):
        if not os.path.isdir(os.path.join(part, "lib")):
            sys.exit(f"not an IDE or plugin directory (no lib/): {part}")
    work = tempfile.mkdtemp(prefix="check-module-classes-")
    jars = []  # (label, refs, plugin deps, module deps)
    try:
        with zipfile.ZipFile(plugin_zip) as z:
            z.extractall(work)
        own = set()
        paths = []
        for root, _, files in os.walk(work):
            for f in sorted(files):
                if f.endswith(".jar"):
                    path = os.path.join(root, f)
                    paths.append(path)
                    with zipfile.ZipFile(path) as z:
                        own.update(n[:-6] for n in z.namelist() if n.endswith(".class"))
        for path in sorted(paths):
            label = os.path.relpath(path, work)
            with zipfile.ZipFile(path) as z:
                names = z.namelist()
                module_descriptor = os.path.basename(path)[:-4] + ".xml"
                if "META-INF/plugin.xml" in names:
                    deps = descriptor_dependencies(z.read("META-INF/plugin.xml"))
                elif module_descriptor in names:
                    deps = descriptor_dependencies(z.read(module_descriptor))
                else:
                    deps = set(), set(), set()  # a library jar of the plugin: loaded by the main classloader
            jars.append((label, referenced_classes(path)) + deps)
    finally:
        shutil.rmtree(work, ignore_errors=True)

    failed = False
    for spec in ide_specs:
        ide = Ide(spec)
        print(ide.name)
        for jar in ide.unreadable:
            print(f"  warning: unreadable jar, not indexed: {jar}")
        for label, refs, required, optional, modules in jars:
            absent = sorted(p for p in required if not ide.has_plugin(p))
            if absent:
                print(f"  {label}: skipped ({', '.join(absent)} not in this IDE)")
                continue
            problems, missing = check(refs, own, required | optional, modules, ide)
            absent_modules = sorted(m for m in modules if m not in ide.module_names)
            if absent_modules:
                # The IDE won't load this content module at all: no crash, but the language silently goes missing.
                problems["module dependencies not in this IDE (the module won't load): " + ", ".join(absent_modules)] = []
            print(f"  {label}: " + ("ok" if not problems else "FAIL") + f" ({len(refs)} classes)")
            failed = failed or bool(problems)
            for where, classes in problems.items():
                print(f"    {where}:")
                for c in classes:
                    print(f"      {c.replace('/', '.')}")
            if missing:
                # Not this script's concern (verifyPlugin checks the API): e.g. an optional dependency's classes.
                absent_optional = sorted(p for p in optional if not ide.has_plugin(p))
                note = f" (optional {', '.join(absent_optional)} not in this IDE)" if absent_optional else ""
                print(f"    note: {len(missing)} classes not in this IDE{note}: " + ", ".join(c.replace('/', '.') for c in missing[:5]) + (" ..." if len(missing) > 5 else ""))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
