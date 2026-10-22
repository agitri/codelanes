# Java Support (IntelliJ IDEA) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** CodeLanes opens Java classes in IntelliJ IDEA as a canvas of lanes, and AI flows accept Java references, released as 0.2.0.

**Architecture:** A `LanguageSupport` extension point (package `dev.codelanes.lang`) with one implementation per language, each registered from an optional plugin descriptor (`codelanes-php.xml` on `com.jetbrains.php`, `codelanes-java.xml` on `com.intellij.java`). The canvas, session, actions and `FlowResolver` call the extension point and never import PHP or Java PSI. `JavaBlockBuilder` mirrors `PhpBlockBuilder` with Java PSI.

**Tech Stack:** Kotlin 2.4.21, IntelliJ Platform Gradle Plugin 2.19.0, IntelliJ IDEA 2026.1 + bundled Java plugin + PHP plugin from the Marketplace, JUnit4, `BasePlatformTestCase` (PHP) and `LightJavaCodeInsightFixtureTestCase` (Java).

**Spec:** `docs/superpowers/specs/2026-10-22-java-support-design.md`

## Global Constraints

- Gradle needs `export JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home` in every shell that runs `./gradlew`.
- Nothing PHP users have today changes. All existing tests stay green without edits to what they assert (imports may change when `MethodRef`/`EntryPoint` move).
- PHP block ids stay exactly as they are (`class:\App\Foo`, `method:bar`, `callee:\App\Foo::bar`, …).
- Canvas, layout, slice editors, review, notes, working sets, flow files and MCP tools never import `com.jetbrains.php` or `com.intellij.psi.Psi*` Java classes (`PsiClass`, `PsiMethod`, `PsiJavaFile`, …).
- `sinceBuild` stays `252`. Version becomes `0.2.0` only in the release task.
- Java block ids: `method:<name>` when the name is unique among the class's own methods, `method:<name>(<Type>, <Type>)` (simple erased type names, `", "` separator) when overloaded. Callee keys `com.acme.Foo::<same method key>`.
- Library types without source: `Block.sourceless = true`, collapsed, summary only, no fold arrow, never a slice editor.
- Test fixtures have no JDK: `java.lang.String` and friends don't resolve. Stub any JDK class a test needs with `myFixture.addClass("package java.util; public interface List<E> {}")`.
- Commits end with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01EZuytWB9WWzUrFUKH7R1Ac
  ```
- Never push or merge; the user does that after testing.

## Review Focus

1. **Half-typed Java** (a method without a closing brace, an unresolved type, a missing name) must not throw from `JavaBlockBuilder.build`; the canvas keeps the last good blocks. Test: Task 5 `testHalfTypedMethodDoesNotThrow`.
2. **A generic project type injected** (`OrderRepository<Order> repo` in a constructor) must still show `OrderRepository` in the dependency lane. Test: Task 6 `testGenericProjectTypeIsADependency`.
3. **Nested classes and anonymous classes** must not produce method blocks of their own, and their methods must not be excluded from the class block. Test: Task 5 `testNestedClassMethodsStayInTheClassBlock`.
4. **Enums with constant bodies and records** must open and show only their own methods. Test: Task 5 `testEnumWithConstantBodiesAndRecordOpen`.
5. **Lombok fields that aren't injected** (`static final`, or `final` with an initializer) must stay out of the dependency lane. Test: Task 6 `testLombokSkipsStaticAndInitializedFields`.

---

## File Structure

| File | Responsibility |
|---|---|
| `build.gradle.kts` | build against IntelliJ IDEA + Java + PHP; verify on PhpStorm and IDEA |
| `src/main/kotlin/dev/codelanes/lang/LanguageSupport.kt` | the interface, `LanguageSupports` lookup, `MethodRef`, `EntryPoint` |
| `src/main/kotlin/dev/codelanes/lang/FlowTarget.kt` | `FlowTarget`, `FlowLookup` |
| `src/main/kotlin/dev/codelanes/php/PhpLanguageSupport.kt` | PHP implementation, delegates to existing PHP objects |
| `src/main/kotlin/dev/codelanes/php/PhpFlowLookup.kt` | PHP reference lookup and hop proofs (moved out of `FlowResolver`) |
| `src/main/kotlin/dev/codelanes/java/JavaBlockBuilder.kt` | Java file → `BlockModel` |
| `src/main/kotlin/dev/codelanes/java/JavaFlowLookup.kt` | Java reference lookup and hop proofs |
| `src/main/kotlin/dev/codelanes/java/JavaMethodFinder.kt` | Java methods for Add method… |
| `src/main/kotlin/dev/codelanes/java/JavaLanguageSupport.kt` | Java implementation |
| `src/main/resources/META-INF/codelanes-php.xml`, `codelanes-java.xml` | optional registrations |
| `src/test/kotlin/dev/codelanes/java/*` | Java tests |
| `playground/java-spring/` | small Spring-style app for manual testing |

---

### Task 1: Build against IntelliJ IDEA with Java and PHP

A feasibility run on 2026-10-22 showed all 287 tests pass with this setup (PHP plugin resolved to 261.22158.208).

**Files:**
- Modify: `build.gradle.kts`

**Interfaces:**
- Produces: a test classpath with `com.intellij.java`, `com.jetbrains.php`, `com.intellij.mcpServer` and the Java test framework (`LightJavaCodeInsightFixtureTestCase`).

- [ ] **Step 1: Switch the platform**

In `build.gradle.kts`, replace

```kotlin
        phpstorm("2026.1")
        bundledPlugin("com.jetbrains.php")
        bundledPlugin("com.intellij.mcpServer") // optional at runtime, see codelanes-mcp.xml
        testFramework(TestFrameworkType.Platform)
```

with

```kotlin
        // IntelliJ IDEA with the PHP plugin: one sandbox and one test run cover both languages.
        intellijIdea("2026.1")
        bundledPlugin("com.intellij.java") // optional at runtime, see codelanes-java.xml
        compatiblePlugin("com.jetbrains.php") // optional at runtime, see codelanes-php.xml
        bundledPlugin("com.intellij.mcpServer") // optional at runtime, see codelanes-mcp.xml
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
```

and the `pluginVerification` block with

```kotlin
    // `./gradlew verifyPlugin` checks API compatibility with the supported PhpStorm and IntelliJ IDEA versions.
    pluginVerification {
        ides {
            create(IntelliJPlatformType.PhpStorm, "2025.2")
            create(IntelliJPlatformType.PhpStorm, "2025.3")
            create(IntelliJPlatformType.PhpStorm, "2026.1")
            create(IntelliJPlatformType.PhpStorm, "2026.2")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.2") // the last split Ultimate/Community release
            create(IntelliJPlatformType.IntellijIdea, "2025.3")
            create(IntelliJPlatformType.IntellijIdea, "2026.1")
            create(IntelliJPlatformType.IntellijIdea, "2026.2")
        }
    }
```

- [ ] **Step 2: Run the whole suite**

Run: `./gradlew test > build/t1.log 2>&1; echo $?; tail -5 build/t1.log`
Expected: `0`, `BUILD SUCCESSFUL`. Count the results:

```bash
python3 - <<'EOF'
import glob,re
t=f=0
for p in glob.glob('build/test-results/test/*.xml'):
    m=re.search(r'tests="(\d+)" skipped="\d+" failures="(\d+)" errors="(\d+)"',open(p).read()); t+=int(m[1]); f+=int(m[2])+int(m[3])
print(t,f)
EOF
```
Expected: `287 0`.

- [ ] **Step 3: Verify** (slow: downloads 8 IDEs; run in the background)

Run: `./gradlew verifyPlugin > build/v1.log 2>&1; echo $?; grep -E "Plugin dev.codelanes|Compatible|problems" build/v1.log | head -20`
Expected at this point: PhpStorm versions compatible; IntelliJ IDEA versions report the plugin **incompatible** because `plugin.xml` still requires `com.jetbrains.php`. That's fixed in Task 2; note it and go on.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit -m "build: develop against IntelliJ IDEA with the Java and PHP plugins"
```

---

### Task 2: `LanguageSupport` and the PHP implementation

**Files:**
- Create: `src/main/kotlin/dev/codelanes/lang/LanguageSupport.kt`
- Create: `src/main/kotlin/dev/codelanes/lang/FlowTarget.kt`
- Create: `src/main/kotlin/dev/codelanes/php/PhpLanguageSupport.kt`
- Create: `src/main/resources/META-INF/codelanes-php.xml`
- Modify: `src/main/resources/META-INF/plugin.xml` (dependencies, extension point)
- Modify: `src/main/kotlin/dev/codelanes/php/MethodFinder.kt`, `php/RouteFinder.kt` (move `MethodRef`, `EntryPoint` out)
- Modify: `src/main/kotlin/dev/codelanes/ide/BlocksEditorProvider.kt`, `ide/BlocksUnavailableNotification.kt`, `ide/AddMethodToCanvasAction.kt`, `ide/FollowRequestAction.kt`, `canvas/BlocksSession.kt`
- Test: `src/test/kotlin/dev/codelanes/lang/LanguageSupportsTest.kt`

**Interfaces:**
- Produces (used by every later task):

```kotlin
package dev.codelanes.lang
interface LanguageSupport {
    fun handles(file: VirtualFile): Boolean
    fun unsupportedReason(file: PsiFile): String?
    fun build(file: PsiFile, revealed: Set<String>, followedCalls: Set<String>, added: Set<String>): BuildResult
    fun methods(project: Project): List<MethodRef>
    fun routes(project: Project): List<EntryPoint>
    fun findFlowTarget(project: Project, reference: String): FlowLookup?
    fun proves(kind: String, from: FlowTarget, to: FlowTarget): Boolean
    fun newMethodText(name: String, indent: String, inInterface: Boolean): String
}
object LanguageSupports { fun all(): List<LanguageSupport>; fun forFile(file: VirtualFile): LanguageSupport? }
data class MethodRef(val label: String, val classFqn: String, val method: String)
data class EntryPoint(val label: String, val filePath: String, val methodName: String)
data class FlowTarget(val classFqn: String, val declaringFqn: String, val methodKey: String?, val isConstructor: Boolean,
                      val file: PsiFile, val classElement: PsiElement, val methodElement: PsiElement?, val support: LanguageSupport)
sealed interface FlowLookup { data class Found(val target: FlowTarget) : FlowLookup; data class Missing(val why: String) : FlowLookup }
```

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/dev/codelanes/lang/LanguageSupportsTest.kt`:

```kotlin
package dev.codelanes.lang

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.codelanes.php.PhpLanguageSupport

class LanguageSupportsTest : BasePlatformTestCase() {
    fun testAPhpFileIsHandledByThePhpSupport() {
        val file = myFixture.configureByText("Foo.php", "<?php\nclass Foo {}\n").virtualFile
        assertTrue(LanguageSupports.forFile(file) is PhpLanguageSupport)
    }

    fun testAPlainTextFileHasNoSupport() {
        val file = myFixture.configureByText("notes.txt", "hello").virtualFile
        assertNull(LanguageSupports.forFile(file))
    }

    fun testThePhpSupportWritesAPhpMethod() {
        val php = LanguageSupports.all().single { it is PhpLanguageSupport }
        assertEquals("    public function go(): void\n    {\n    }\n", php.newMethodText("go", "    ", inInterface = false))
        assertEquals("    public function go(): void;\n", php.newMethodText("go", "    ", inInterface = true))
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew test --tests 'dev.codelanes.lang.*' > build/t2.log 2>&1; grep -E "error:|FAILED|BUILD" build/t2.log | head`
Expected: compilation fails, `Unresolved reference 'LanguageSupports'`.

- [ ] **Step 3: Create the interface and the lookup**

`src/main/kotlin/dev/codelanes/lang/LanguageSupport.kt`:

```kotlin
package dev.codelanes.lang

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import dev.codelanes.model.BuildResult

/**
 * Everything CodeLanes needs to know about one language. Registered from an optional descriptor
 * (`codelanes-php.xml`, `codelanes-java.xml`), so each one only loads in an IDE that has the language.
 */
interface LanguageSupport {
    /** Cheap check on the file type only, no PSI: is this file this language's? */
    fun handles(file: VirtualFile): Boolean

    /** Why [file] can't be shown as blocks, or null. Structure only, safe while indexing. */
    fun unsupportedReason(file: PsiFile): String?

    fun build(file: PsiFile, revealed: Set<String>, followedCalls: Set<String>, added: Set<String>): BuildResult

    /** Every method in the project's own code, for Add method… Needs a read action and indexes. */
    fun methods(project: Project): List<MethodRef>

    /** Routes found without AI, for Follow a Request. */
    fun routes(project: Project): List<EntryPoint>

    /** A flow reference (`Fqn::method`) → what it points at; null if it isn't written in this language's syntax. */
    fun findFlowTarget(project: Project, reference: String): FlowLookup?

    /** Whether the code proves a hop of [kind] between two targets this support found. */
    fun proves(kind: String, from: FlowTarget, to: FlowTarget): Boolean

    /** Text of an empty method called [name], indented by [indent], ending in a newline, for "+ method". */
    fun newMethodText(name: String, indent: String, inInterface: Boolean): String
}

object LanguageSupports {
    private val EP_NAME = ExtensionPointName.create<LanguageSupport>("dev.codelanes.languageSupport")

    fun all(): List<LanguageSupport> = EP_NAME.extensionList

    fun forFile(file: VirtualFile): LanguageSupport? = all().firstOrNull { it.handles(file) }
}

/** A method that can be dropped on a canvas, e.g. "OrderService::place(string $customer)". */
data class MethodRef(val label: String, val classFqn: String, val method: String)

/** A route found in the code: its label (e.g. "POST /api/orders") and the controller method it lands in. */
data class EntryPoint(val label: String, val filePath: String, val methodName: String)
```

`src/main/kotlin/dev/codelanes/lang/FlowTarget.kt`:

```kotlin
package dev.codelanes.lang

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * What a flow reference points at. [classFqn]: the class that was named, spelled as block ids spell it
 * (`\App\Foo`, `com.acme.Foo`). [declaringFqn]: the class that declares the method (differs for inherited ones).
 * [methodKey]: the method part of its block id, or null for the class as a whole. [file]: the declaring class's file.
 * [classElement] and [methodElement] are for [LanguageSupport.proves] of the same [support] only.
 */
data class FlowTarget(
    val classFqn: String,
    val declaringFqn: String,
    val methodKey: String?,
    val isConstructor: Boolean,
    val file: PsiFile,
    val classElement: PsiElement,
    val methodElement: PsiElement?,
    val support: LanguageSupport,
)

sealed interface FlowLookup {
    data class Found(val target: FlowTarget) : FlowLookup
    data class Missing(val why: String) : FlowLookup
}
```

- [ ] **Step 4: Move `MethodRef` and `EntryPoint`**

Delete the `data class MethodRef` line from `php/MethodFinder.kt` and the `data class EntryPoint` line from `php/RouteFinder.kt`. Add `import dev.codelanes.lang.MethodRef` / `import dev.codelanes.lang.EntryPoint` where the compiler asks (`php/*`, `ide/AddMethodToCanvasAction.kt`, `ide/FollowRequestAction.kt`, `php/RouteFiles.kt`, tests under `src/test/kotlin/dev/codelanes/php/`). Find them all with:

```bash
grep -rln "MethodRef\|EntryPoint" src/main/kotlin src/test/kotlin
```

- [ ] **Step 5: The PHP implementation** (flow methods are filled in Task 3; until then they return null/false)

`src/main/kotlin/dev/codelanes/php/PhpLanguageSupport.kt`:

```kotlin
package dev.codelanes.php

import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.jetbrains.php.lang.PhpFileType
import dev.codelanes.lang.EntryPoint
import dev.codelanes.lang.FlowLookup
import dev.codelanes.lang.FlowTarget
import dev.codelanes.lang.LanguageSupport
import dev.codelanes.lang.MethodRef
import dev.codelanes.model.BuildResult

class PhpLanguageSupport : LanguageSupport {
    override fun handles(file: VirtualFile): Boolean = FileTypeRegistry.getInstance().isFileOfType(file, PhpFileType.INSTANCE)

    override fun unsupportedReason(file: PsiFile): String? = PhpBlockBuilder.unsupportedReason(file)

    override fun build(file: PsiFile, revealed: Set<String>, followedCalls: Set<String>, added: Set<String>): BuildResult =
        PhpBlockBuilder.build(file, revealed, followedCalls, added)

    override fun methods(project: Project): List<MethodRef> = MethodFinder.find(project)

    override fun routes(project: Project): List<EntryPoint> = RouteFinder.find(project)

    override fun findFlowTarget(project: Project, reference: String): FlowLookup? = null

    override fun proves(kind: String, from: FlowTarget, to: FlowTarget): Boolean = false

    override fun newMethodText(name: String, indent: String, inInterface: Boolean): String =
        if (inInterface) "${indent}public function $name(): void;\n"
        else "${indent}public function $name(): void\n$indent{\n$indent}\n"
}
```

- [ ] **Step 6: Register it**

`src/main/resources/META-INF/codelanes-php.xml`:

```xml
<!-- Loaded only when the PHP plugin is there (PhpStorm, or IntelliJ IDEA with PHP). -->
<idea-plugin>
    <extensions defaultExtensionNs="dev.codelanes">
        <languageSupport implementation="dev.codelanes.php.PhpLanguageSupport"/>
    </extensions>
</idea-plugin>
```

In `plugin.xml` replace `<depends>com.jetbrains.php</depends>` with

```xml
    <depends optional="true" config-file="codelanes-php.xml">com.jetbrains.php</depends>
```

and add after the `<depends>` lines:

```xml
    <extensionPoints>
        <extensionPoint qualifiedName="dev.codelanes.languageSupport" interface="dev.codelanes.lang.LanguageSupport" dynamic="true"/>
    </extensionPoints>
```

- [ ] **Step 7: Switch the callers**

`ide/BlocksEditorProvider.kt` `accept`:

```kotlin
    override fun accept(project: Project, file: VirtualFile): Boolean {
        val language = LanguageSupports.forFile(file) ?: return false
        return ApplicationManager.getApplication().runReadAction<Boolean> {
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@runReadAction false
            language.unsupportedReason(psi) == null
        }
    }
```

`ide/BlocksUnavailableNotification.kt`:

```kotlin
        if (!BlocksSettings.instance.state.openAsBlocksByDefault) return null
        val language = LanguageSupports.forFile(file) ?: return null
        val reason = ApplicationManager.getApplication().runReadAction<String?> {
            PsiManager.getInstance(project).findFile(file)?.let(language::unsupportedReason)
        } ?: return null
```

`canvas/BlocksSession.kt` `computeUpdate`: replace `PhpBlockBuilder.build(psi, reveal, follow, extra)` with

```kotlin
        val language = LanguageSupports.forFile(file) ?: return null
        val built = language.build(psi, reveal, follow, extra).let { result ->
```

`canvas/BlocksSession.kt` `addMethod`: replace the two `val method = if (cls.title.startsWith("interface")) …` lines with

```kotlin
        val language = LanguageSupports.forFile(file) ?: return@withModelAccess
        val inInterface = cls.title.startsWith("interface") || cls.title.startsWith("@interface")
        val method = language.newMethodText(name, indent, inInterface)
```

`ide/AddMethodToCanvasAction.kt`: replace `MethodFinder.find(project)` with `LanguageSupports.forFile(canvas.file)?.methods(project).orEmpty()`.

`ide/FollowRequestAction.kt`: replace `RouteFinder.find(project)` with `LanguageSupports.all().flatMap { it.routes(project) }`, and the empty title with `"$TITLE (no routes or saved flows found)"`.

Remove the now unused `PhpFileType`/`PhpBlockBuilder`/`MethodFinder`/`RouteFinder` imports from those files.

- [ ] **Step 8: Run the new tests, then everything**

Run: `./gradlew test > build/t2.log 2>&1; echo $?` and the counting script from Task 1.
Expected: `0`, `290 0` (287 + 3 new).

Check no shared code imports PHP any more:

```bash
grep -rln "com.jetbrains.php" src/main/kotlin | grep -v "/php/"
```
Expected: only `src/main/kotlin/dev/codelanes/flow/FlowResolver.kt` (fixed in Task 3).

- [ ] **Step 9: Commit**

```bash
git add -A src/main build.gradle.kts src/test
git commit -m "refactor: a LanguageSupport extension point, with PHP as its first language"
```

---

### Task 3: A language-neutral `FlowResolver`

**Files:**
- Create: `src/main/kotlin/dev/codelanes/php/PhpFlowLookup.kt`
- Modify: `src/main/kotlin/dev/codelanes/flow/FlowResolver.kt`
- Modify: `src/main/kotlin/dev/codelanes/php/PhpLanguageSupport.kt`
- Test: `src/test/kotlin/dev/codelanes/flow/FlowResolverTest.kt` (unchanged; it is the safety net), plus one new test in it.

**Interfaces:**
- Consumes: `LanguageSupport`, `FlowTarget`, `FlowLookup` (Task 2).
- Produces: `FlowResolver.resolve(project, flow): ResolvedFlow` with the same result type and behaviour as today; `PhpFlowLookup.find(project, reference, support): FlowLookup?`, `PhpFlowLookup.proves(kind, from, to): Boolean`.

- [ ] **Step 1: Write the failing test**

Add to `FlowResolverTest`:

```kotlin
    fun testAReferenceNoLanguageCanReadIsUnknown() {
        val resolved = resolve(hop(store, "not a reference", "calls"))
        assertEquals(listOf("not a reference (not a Class::method reference)"), resolved.unknown)
    }
```

- [ ] **Step 2: Run it**

Run: `./gradlew test --tests 'dev.codelanes.flow.FlowResolverTest' > build/t3.log 2>&1; echo $?`
Expected: it may already pass (today's resolver has this check). If it passes, keep it: it pins behaviour the rewrite must keep. Ledger: "test pins existing behaviour".

- [ ] **Step 3: Move the PHP lookup out**

`src/main/kotlin/dev/codelanes/php/PhpFlowLookup.kt` (the code is today's `FlowResolver.find`/`proves`/`callsOrCreates`/`isSubtype`, now producing `FlowTarget`):

```kotlin
package dev.codelanes.php

import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.NewExpression
import com.jetbrains.php.lang.psi.elements.PhpClass
import dev.codelanes.lang.FlowLookup
import dev.codelanes.lang.FlowTarget
import dev.codelanes.lang.LanguageSupport

/** PHP flow references (`App\Foo::bar`, `\App\Foo::bar`, `App\Foo::class`) and the proofs for PHP hops. */
object PhpFlowLookup {
    /** Null when the class part is written with dots: that's Java's syntax, not PHP's. */
    fun find(project: Project, reference: String, support: LanguageSupport): FlowLookup? {
        val trimmed = reference.trim()
        val classPart = trimmed.substringBefore("::")
        if ('.' in classPart) return null
        val fqn = "\\" + classPart.trimStart('\\')
        val name = trimmed.substringAfter("::").removeSuffix("()")
        val shown = trimmed.trimStart('\\')
        val index = PhpIndex.getInstance(project)
        val owners = index.getClassesByFQN(fqn) + index.getInterfacesByFQN(fqn) + index.getTraitsByFQN(fqn)
        val named = owners.firstOrNull() ?: return FlowLookup.Missing("$shown (no such class)")
        if (name == "class") return FlowLookup.Found(target(named, null, support))
        if (name.equals("__construct", ignoreCase = true)) {
            // An inherited or missing constructor stands for the class that was named, not its parent.
            return FlowLookup.Found(target(named, named.ownConstructor, support))
        }
        val method = owners.firstNotNullOfOrNull { it.findMethodByName(name) } ?: return FlowLookup.Missing("$shown (no such method)")
        return FlowLookup.Found(target(named, method, support))
    }

    private fun target(named: PhpClass, method: Method?, support: LanguageSupport): FlowTarget {
        val declaring = method?.containingClass ?: named
        return FlowTarget(
            classFqn = named.fqn,
            declaringFqn = declaring.fqn,
            methodKey = method?.name,
            isConstructor = method?.name.equals("__construct", ignoreCase = true),
            file = declaring.containingFile,
            classElement = named,
            methodElement = method,
            support = support,
        )
    }

    fun proves(kind: String, from: FlowTarget, to: FlowTarget): Boolean {
        val fromMethod = from.methodElement as? Method
        val toMethod = to.methodElement as? Method
        return when (kind) {
            "calls", "dispatches", "middleware" -> fromMethod?.let { callsOrCreates(it, to.classElement as PhpClass, toMethod) } == true
            "binds" -> fromMethod != null && toMethod != null && toMethod.name.equals(fromMethod.name, ignoreCase = true) &&
                isSubtype(toMethod.containingClass, fromMethod.containingClass)
            "listens" -> toMethod?.parameters?.firstOrNull()?.declaredType?.types?.contains((from.classElement as PhpClass).fqn) == true
            else -> false // "routes": the route is the AI's (and the person's) word, nothing in a method proves it
        }
    }

    /** [from] calls [toMethod], or creates an instance of [toClass] (`event(new OrderPlaced())`). */
    private fun callsOrCreates(from: Method, toClass: PhpClass, toMethod: Method?): Boolean {
        val calls = toMethod != null && PsiTreeUtil.findChildrenOfType(from, MethodReference::class.java)
            .any { call -> call.multiResolve(false).any { it.element == toMethod } }
        if (calls) return true
        if (toMethod != null && !toMethod.name.equals("__construct", ignoreCase = true)) return false
        val target = toClass.fqn
        return PsiTreeUtil.findChildrenOfType(from, NewExpression::class.java).any { created ->
            when (val resolved = created.classReference?.resolve()) {
                is PhpClass -> resolved.fqn == target
                is Method -> created.classReference?.fqn == target || resolved.containingClass?.fqn == target
                else -> created.classReference?.fqn == target
            }
        }
    }

    private fun isSubtype(cls: PhpClass?, of: PhpClass?): Boolean {
        if (cls == null || of == null) return false
        val seen = mutableSetOf<String>()
        fun visit(c: PhpClass): Boolean {
            if (!seen.add(c.fqn)) return false
            if (c != cls && c.fqn == of.fqn) return true
            return (c.supers.toList() + c.implementedInterfaces.toList()).any(::visit)
        }
        return visit(cls)
    }
}
```

In `PhpLanguageSupport`:

```kotlin
    override fun findFlowTarget(project: Project, reference: String): FlowLookup? = PhpFlowLookup.find(project, reference, this)

    override fun proves(kind: String, from: FlowTarget, to: FlowTarget): Boolean = PhpFlowLookup.proves(kind, from, to)
```

- [ ] **Step 4: Rewrite `FlowResolver` on top of it**

Replace the body of `object FlowResolver` (keep `ResolvedHop` and `ResolvedFlow` as they are) and drop the PHP imports:

```kotlin
object FlowResolver {
    fun resolve(project: Project, flow: Flow): ResolvedFlow {
        val unknown = mutableListOf<String>()
        fun lookUp(reference: String): FlowTarget? = when (val found = find(project, reference)) {
            is FlowLookup.Found -> found.target
            is FlowLookup.Missing -> null.also { unknown += found.why }
        }
        val entry = when (val found = find(project, flow.entry.method)) {
            // A PHP entry on `__construct` keeps working: its methodKey is set and its id is the class block.
            is FlowLookup.Found -> found.target.takeIf { it.methodKey != null }
                ?: return ResolvedFlow(null, null, "${flow.entry.method.trim().trimStart('\\')} (not a method)", emptyList(), emptySet(), emptyList())
            is FlowLookup.Missing -> return ResolvedFlow(null, null, found.why, emptyList(), emptySet(), emptyList())
        }
        val entryFile = entry.file
        entry.support.unsupportedReason(entryFile)?.let { reason ->
            return ResolvedFlow(entryFile.virtualFile, null, "${entryFile.name} can't be shown as blocks: $reason", emptyList(), emptySet(), emptyList())
        }
        val openFqn = entry.declaringFqn
        val added = linkedSetOf<String>()
        fun idOf(target: FlowTarget): String {
            val key = target.methodKey
            if (key == null) {
                if (target.classFqn == openFqn) return "class:$openFqn"
                return "callee:${target.classFqn}::class".also { added += it.removePrefix("callee:") }
            }
            if (target.declaringFqn == openFqn) return if (target.isConstructor) "class:$openFqn" else "method:$key"
            return "callee:${target.declaringFqn}::$key".also { added += it.removePrefix("callee:") }
        }
        val hops = flow.hops.mapNotNull { hop ->
            val from = lookUp(hop.from)
            val to = lookUp(hop.to)
            if (from == null || to == null) return@mapNotNull null
            val proven = from.support === to.support && from.support.proves(hop.kind, from, to)
            ResolvedHop(idOf(from), idOf(to), hop.kind, proven)
        }
        return ResolvedFlow(entryFile.virtualFile, idOf(entry), null, hops.distinct(), added, unknown.distinct())
    }

    /** The first language that finds the reference wins; if none finds it, the first one's reason is reported. */
    private fun find(project: Project, reference: String): FlowLookup {
        val trimmed = reference.trim()
        if ("::" !in trimmed) return FlowLookup.Missing("$trimmed (not a Class::method reference)")
        val answers = LanguageSupports.all().mapNotNull { it.findFlowTarget(project, trimmed) }
        return answers.firstOrNull { it is FlowLookup.Found } ?: answers.firstOrNull()
            ?: FlowLookup.Missing("${trimmed.trimStart('\\')} (not a Class::method reference)")
    }
}
```

Note: PHP's `idOf` for a method used `method.containingClass` vs the open class; `declaringFqn` carries exactly that, and `named.ownConstructor == null` gives `methodKey == null`, so ids are unchanged.

- [ ] **Step 5: Run the flow, MCP and session tests, then everything**

Run: `./gradlew test > build/t3.log 2>&1; echo $?` and the counting script.
Expected: `0`, `291 0`. And:

```bash
grep -rln "com.jetbrains.php" src/main/kotlin | grep -v "/php/"
```
Expected: no output.

- [ ] **Step 6: Commit**

```bash
git add -A src/main src/test
git commit -m "refactor: FlowResolver asks the languages, PHP lookups move to PhpFlowLookup"
```

---

### Task 4: Blocks without source

**Files:**
- Modify: `src/main/kotlin/dev/codelanes/model/BlockModel.kt` (`Block`)
- Modify: `src/main/kotlin/dev/codelanes/canvas/BlockView.kt:146-152`
- Modify: `src/main/kotlin/dev/codelanes/canvas/BlocksSession.kt:280` and the toggle at `:385`
- Test: `src/test/kotlin/dev/codelanes/canvas/BlocksSessionTest.kt` (or `BlocksCanvasTest.kt`, wherever `titleText()` is tested today: `grep -rn "titleText" src/test`)

**Interfaces:**
- Produces: `Block.sourceless: Boolean = false` (last constructor parameter, after `summary`).

- [ ] **Step 1: Write the failing test** (next to the existing `testTheFlowEntryHasNoFoldArrowAndAClassBlockNoCallsToggle`, reusing its way of getting a `BlockView`; read that test first)

```kotlin
    fun testASourcelessBlockHasNoFoldArrowAndNoEditor() {
        val block = Block("parent:junit.framework.TestCase", BlockKind.PARENT, "class TestCase", "/lib/junit.jar!/junit/framework/TestCase.class",
            SourceRange(0, 0), collapsed = true, summary = listOf("run()"), sourceless = true)
        val view = BlockView(block)   // use the same construction the existing fold-arrow test uses
        view.update(block, collapsed = false, body = javax.swing.JPanel(), zoom = 1.0)
        assertEquals("class TestCase", view.titleText())
    }
```

If the existing test builds views differently (e.g. through a session), copy that route; the assertion stays the same.

- [ ] **Step 2: Run it**

Run: `./gradlew test --tests 'dev.codelanes.canvas.*' > build/t4.log 2>&1; grep -E "error:|FAILED" build/t4.log | head`
Expected: compile error `No parameter with name 'sourceless'`.

- [ ] **Step 3: Implement**

`Block` gets, after `summary`:

```kotlin
    /** A library type without source (e.g. a compiled Spring interface): title and summary only, never an editor. */
    val sourceless: Boolean = false,
```

`BlockView.update`:

```kotlin
        // A MORE block and a block without source never open into code, so they have nothing to fold.
        title.text = when {
            block.kind == BlockKind.MORE || block.sourceless -> block.title
            collapsed -> "▸ " + block.title
            else -> "▾ " + block.title
        }
```

`BlocksSession` line 280:

```kotlin
            val slice = if (isCollapsed || block.kind == BlockKind.MORE || block.sourceless) null else sliceFor(block)
```

and the toggle at line 385 (`collapsed[id] = !(collapsed[id] ?: false)`): return early when the block is sourceless:

```kotlin
        if (model?.blocks?.firstOrNull { it.id == id }?.sourceless == true) return@withModelAccess
```

(check the enclosing function's lambda label when you read it; use `return` if it isn't inside `withModelAccess`).

- [ ] **Step 4: Run all tests**

Expected: `0`, `292 0`.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat: blocks for library types without source show their summary only"
```

---

### Task 5: Java files open as blocks: structure, methods, `+ method`

**Files:**
- Create: `src/main/kotlin/dev/codelanes/java/JavaBlockBuilder.kt`
- Create: `src/main/kotlin/dev/codelanes/java/JavaMethodFinder.kt`
- Create: `src/main/kotlin/dev/codelanes/java/JavaLanguageSupport.kt`
- Create: `src/main/resources/META-INF/codelanes-java.xml`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Test: `src/test/kotlin/dev/codelanes/java/JavaBuilderTestCase.kt`, `JavaBlockBuilderStructureTest.kt`

**Interfaces:**
- Consumes: `LanguageSupport`, `MethodRef`, `Block.sourceless`.
- Produces:
  - `JavaBlockBuilder.build(file: PsiFile, revealed: Set<String> = emptySet(), followedCalls: Set<String> = emptySet(), added: Set<String> = emptySet()): BuildResult`
  - `JavaBlockBuilder.unsupportedReason(file: PsiFile): String?`
  - `internal fun JavaBlockBuilder.fqn(cls: PsiClass): String`
  - `internal fun JavaBlockBuilder.ownMethods(cls: PsiClass): List<PsiMethod>`
  - `internal fun JavaBlockBuilder.methodKey(method: PsiMethod): String`
  - `internal fun JavaBlockBuilder.findOwnMethod(cls: PsiClass, key: String): PsiMethod?`
  - `internal fun JavaBlockBuilder.methodTitle(method: PsiMethod): String`
  - `JavaLanguageSupport` (class, registered in `codelanes-java.xml`)

This task builds the header, class and method blocks, OWNS and own CALLS links. Tasks 6 and 7 add the other lanes into the same `build` (marked `// Task 6` / `// Task 7` below).

- [ ] **Step 1: Test base**

`src/test/kotlin/dev/codelanes/java/JavaBuilderTestCase.kt`:

```kotlin
package dev.codelanes.java

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.codelanes.model.Block
import dev.codelanes.model.BlockModel
import dev.codelanes.model.BuildResult
import java.io.File

/**
 * Java fixture tests. There is no JDK here (java.lang doesn't resolve): stub what a test needs with [addClass].
 * junit.jar is on the module as a library without sources, for "sourceless" parents.
 */
abstract class JavaBuilderTestCase : LightJavaCodeInsightFixtureTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = LIBRARY_DESCRIPTOR

    protected fun build(text: String, fileName: String = "Foo.java", revealed: Set<String> = emptySet(),
                        followed: Set<String> = emptySet(), added: Set<String> = emptySet()): BuildResult =
        JavaBlockBuilder.build(myFixture.configureByText(fileName, text), revealed, followed, added)

    protected fun supported(text: String, fileName: String = "Foo.java", revealed: Set<String> = emptySet(),
                            followed: Set<String> = emptySet(), added: Set<String> = emptySet()): BlockModel =
        when (val result = build(text, fileName, revealed, followed, added)) {
            is BuildResult.Supported -> result.model
            is BuildResult.Unsupported -> error("Expected Supported but got Unsupported(${result.reason})")
        }

    protected fun addClass(text: String) {
        myFixture.addClass(text.trimIndent())
    }

    /** The text of [block] as shown on the canvas: its range minus its excluded ranges. */
    protected fun visibleText(block: Block): String {
        val text = myFixture.file.text
        return (block.range.start until block.range.end)
            .filter { offset -> block.excluded.none { it.contains(offset) } }
            .map { text[it] }
            .joinToString("")
    }

    /** The annotations Spring code uses, as stubs (no Spring jar in the fixture). */
    protected fun addSpringStubs() {
        addClass("package org.springframework.stereotype; public @interface Service {}")
        addClass("package org.springframework.stereotype; public @interface Repository {}")
        addClass("package org.springframework.beans.factory.annotation; public @interface Autowired {}")
        addClass("package org.springframework.context.event; public @interface EventListener {}")
        addClass("package org.springframework.context; public interface ApplicationEventPublisher { void publishEvent(Object event); }")
        addClass("package lombok; public @interface RequiredArgsConstructor {}")
        addClass("package lombok; public @interface AllArgsConstructor {}")
        addClass("package javax.inject; public @interface Inject {}")
    }

    private companion object {
        val LIBRARY_DESCRIPTOR = object : DefaultLightProjectDescriptor() {
            override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
                super.configureModule(module, model, contentEntry)
                val jar = File(PathManager.getJarPathForClass(junit.framework.TestCase::class.java)!!)
                PsiTestUtil.addLibrary(model, "junit", jar.parent, jar.name)
            }
        }
    }
}
```

- [ ] **Step 2: Write the failing tests**

`src/test/kotlin/dev/codelanes/java/JavaBlockBuilderStructureTest.kt`:

```kotlin
package dev.codelanes.java

import dev.codelanes.lang.LanguageSupports
import dev.codelanes.model.BlockKind
import dev.codelanes.model.BuildResult
import dev.codelanes.model.LinkKind

class JavaBlockBuilderStructureTest : JavaBuilderTestCase() {
    private val service = """
        package com.acme.order;

        import java.util.List;
        import com.acme.Thing;

        /** Places orders. */
        @Deprecated
        public class OrderService {
            private int count;

            OrderService() {}

            /** Places one. */
            public void place(String customer) {
                check();
            }

            private void check() {}
        }
    """.trimIndent()

    fun testAClassBecomesHeaderClassAndMethodBlocks() {
        val model = supported(service)
        assertEquals(listOf("header", "class:com.acme.order.OrderService", "method:place", "method:check"), model.blocks.map { it.id })
        assertEquals("package com.acme.order · 2 imports", model.block("header").title)
        assertTrue(model.block("header").collapsed)
        assertEquals("class OrderService", model.block("class:com.acme.order.OrderService").title)
        assertEquals("place(String customer)", model.block("method:place").title)
    }

    fun testJavadocAndAnnotationsBelongToTheirBlock() {
        val model = supported(service)
        assertTrue(visibleText(model.block("class:com.acme.order.OrderService")).startsWith("/** Places orders. */\n@Deprecated\npublic class"))
        assertTrue(visibleText(model.block("method:place")).startsWith("/** Places one. */"))
        // The constructor and the field stay in the class block; the methods don't.
        val classText = visibleText(model.block("class:com.acme.order.OrderService"))
        assertTrue(classText.contains("OrderService() {}"))
        assertFalse(classText.contains("check()"))
    }

    fun testMethodsAreOwnedAndOwnCallsAreLinked() {
        val model = supported(service)
        val cls = "class:com.acme.order.OrderService"
        assertTrue(model.links.contains(dev.codelanes.model.Link(LinkKind.OWNS, cls, "method:place")))
        assertTrue(model.links.contains(dev.codelanes.model.Link(LinkKind.CALLS, "method:place", "method:check")))
    }

    fun testOverloadsGetTheirParameterTypesInTheirIds() {
        val model = supported("""
            package a;
            class Repo {
                void save(Order o) {}
                void save(java.util.List<Order> all, boolean flush) {}
                void find() {}
            }
        """.trimIndent())
        assertEquals(listOf("method:save(Order)", "method:save(List, boolean)", "method:find"), model.ofKind(BlockKind.METHOD).map { it.id })
    }

    fun testKeywordsInTheClassTitle() {
        addClass("package a; public interface Named {}")
        addClass("package a; public class Base {}")
        assertEquals("class Foo extends Base implements Named", supported("package a; public class Foo extends Base implements Named {}").ofKind(BlockKind.CLASS).single().title)
        assertEquals("interface Foo", supported("package a; public interface Foo { void x(); }").ofKind(BlockKind.CLASS).single().title)
        assertEquals("@interface Foo", supported("package a; public @interface Foo {}").ofKind(BlockKind.CLASS).single().title)
    }

    fun testWhichFilesDontOpen() {
        assertEquals("Expected exactly one top-level class, interface, enum or record, found 2",
            (build("package a; class A {} class B {}") as BuildResult.Unsupported).reason)
        assertEquals("No class in this file", (build("package a;", "package-info.java") as BuildResult.Unsupported).reason)
    }

    fun testNestedClassMethodsStayInTheClassBlock() {
        val model = supported("""
            package a;
            public class Outer {
                static class Inner { void hidden() {} }
                void visible() { Runnable r = new Runnable() { public void run() {} }; }
            }
        """.trimIndent())
        assertEquals(listOf("method:visible"), model.ofKind(BlockKind.METHOD).map { it.id })
        assertTrue(visibleText(model.ofKind(BlockKind.CLASS).single()).contains("void hidden() {}"))
    }

    fun testEnumWithConstantBodiesAndRecordOpen() {
        val enumModel = supported("""
            package a;
            public enum Status {
                NEW { void label() {} },
                DONE;
                void shared() {}
            }
        """.trimIndent(), "Status.java")
        assertEquals(listOf("method:shared"), enumModel.ofKind(BlockKind.METHOD).map { it.id })
        assertEquals("enum Status", enumModel.ofKind(BlockKind.CLASS).single().title)
        val recordModel = supported("package a; public record Point(int x, int y) { int sum() { return x + y; } }", "Point.java")
        assertEquals("record Point", recordModel.ofKind(BlockKind.CLASS).single().title)
        assertEquals(listOf("method:sum"), recordModel.ofKind(BlockKind.METHOD).map { it.id })
    }

    fun testHalfTypedMethodDoesNotThrow() {
        val result = build("package a;\npublic class Foo {\n    void ok() {}\n    void broken(Strin\n    Unknown x;\n}\n")
        assertTrue(result is BuildResult.Supported)
    }

    fun testTheJavaSupportHandlesJavaFilesAndWritesJavaMethods() {
        val file = myFixture.configureByText("Foo.java", "package a; class Foo {}").virtualFile
        val java = LanguageSupports.forFile(file)
        assertTrue(java is JavaLanguageSupport)
        assertEquals("    public void go() {\n    }\n", java!!.newMethodText("go", "    ", inInterface = false))
        assertEquals("    void go();\n", java.newMethodText("go", "    ", inInterface = true))
        assertTrue(java.routes(project).isEmpty())
    }

    fun testAddMethodSearchListsJavaMethods() {
        addClass("package com.acme; public class Mailer { public void send(String to) {} void send(String to, int n) {} }")
        val refs = JavaMethodFinder.find(project).filter { it.classFqn == "com.acme.Mailer" }
        assertEquals(listOf("send(String)", "send(String, int)"), refs.map { it.method }.sorted())
        assertTrue(refs.any { it.label == "Mailer::send(String to)  com.acme" })
    }
}
```

Note in `testOverloadsGetTheirParameterTypesInTheirIds`: `Order` is unresolved (no class); its presentable text is still `Order`. `java.util.List<Order>` is unresolved too (no JDK) and must still print as `List`.

- [ ] **Step 3: Run them**

Run: `./gradlew test --tests 'dev.codelanes.java.*' > build/t5.log 2>&1; grep -E "error:|FAILED" build/t5.log | head`
Expected: compile errors, `Unresolved reference 'JavaBlockBuilder'`.

- [ ] **Step 4: Implement the builder (structure part)**

`src/main/kotlin/dev/codelanes/java/JavaBlockBuilder.kt`:

```kotlin
package dev.codelanes.java

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiType
import com.intellij.psi.SyntheticElement
import com.intellij.psi.impl.light.LightElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import dev.codelanes.model.Block
import dev.codelanes.model.BlockKind
import dev.codelanes.model.BlockModel
import dev.codelanes.model.BuildResult
import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind
import dev.codelanes.model.SourceRange

/** Turns a Java file with one top-level type into a [BlockModel]; anything else is [BuildResult.Unsupported]. */
object JavaBlockBuilder {
    const val HEADER_ID = "header"
    const val MORE_ID = "more:implementers"
    const val MAX_IMPLEMENTERS = 10
    const val MAX_CALLEES = 10
    const val MAX_CHAIN_BLOCKS = 80
    const val MORE_CHAIN_ID = "more:chain"
    const val MAX_IMPLEMENTATIONS = 5

    fun build(file: PsiFile, revealed: Set<String> = emptySet(), followedCalls: Set<String> = emptySet(), added: Set<String> = emptySet()): BuildResult {
        unsupportedReason(file)?.let { return BuildResult.Unsupported(it) }
        val cls = (file as PsiJavaFile).classes.single()
        val classRange = rangeOf(cls)
        val path = file.virtualFile.path
        val classId = "class:${fqn(cls)}"
        val methods = ownMethods(cls)
        val methodBlocks = methods.map { Block(methodId(it), BlockKind.METHOD, methodTitle(it), path, rangeOf(it)) }

        val blocks = mutableListOf<Block>()
        val links = mutableListOf<Link>()
        blocks += Block(HEADER_ID, BlockKind.HEADER, headerTitle(file), path, SourceRange(0, classRange.start), collapsed = true)
        blocks += Block(classId, BlockKind.CLASS, classTitle(cls), path, classRange, excluded = methodBlocks.map { it.range })
        blocks += methodBlocks
        links += methodBlocks.map { Link(LinkKind.OWNS, classId, it.id) }
        // Task 6: related types, overrides, dependencies, revealDeeper
        // Task 7: followCalls, implementedBy
        links += calls(methods)
        return BuildResult.Supported(BlockModel(blocks, links))
    }

    /** Why [file] can't be shown as blocks, or null. Structure only, no resolving: cheap while indexing. */
    fun unsupportedReason(file: PsiFile): String? {
        if (file !is PsiJavaFile) return "Not a Java file"
        if (file.name == "package-info.java" || file.name == "module-info.java") return "No class in this file"
        val classes = file.classes
        if (classes.size != 1) return "Expected exactly one top-level class, interface, enum or record, found ${classes.size}"
        if (classes.single().name == null) return "No class in this file"
        return null
    }

    internal fun fqn(cls: PsiClass): String = cls.qualifiedName ?: cls.name.orEmpty()

    /** The class's own methods as written: no constructors, no generated ones (enum values(), record accessors, Lombok). */
    internal fun ownMethods(cls: PsiClass): List<PsiMethod> =
        cls.methods.filter { !it.isConstructor && it !is LightElement && it !is SyntheticElement }.sortedBy { it.textRange?.startOffset ?: 0 }

    /** `place`, or `place(Order, boolean)` when the class has more than one `place`. */
    internal fun methodKey(method: PsiMethod): String {
        val owner = method.containingClass
        val overloaded = owner != null && ownMethods(owner).count { it.name == method.name } > 1
        return if (overloaded) signatureKey(method, ", ") else method.name
    }

    private fun signatureKey(method: PsiMethod, separator: String): String =
        method.name + method.parameterList.parameters.joinToString(separator, "(", ")") { simpleType(it.type) }

    /** `java.util.List<Order>` → `List`; unresolved types keep the name they were written with. */
    private fun simpleType(type: PsiType): String = type.presentableText.replace(Regex("<.*>"), "")

    /**
     * [key] as an id or a flow writes it: `place`, `place(Order)`, `place(Order,boolean)` (spaces don't matter).
     * A bare name picks the first in source order.
     */
    internal fun findOwnMethod(cls: PsiClass, key: String): PsiMethod? {
        val wanted = key.replace(" ", "")
        val own = ownMethods(cls)
        if ('(' !in wanted) return own.firstOrNull { it.name == wanted }
        return own.firstOrNull { signatureKey(it, ",") == wanted }
    }

    private fun methodId(method: PsiMethod) = "method:${methodKey(method)}"

    internal fun methodTitle(method: PsiMethod): String =
        method.name + method.parameterList.parameters.joinToString(", ", "(", ")") { p -> "${p.typeElement?.text ?: p.type.presentableText} ${p.name}" }

    internal fun keyword(cls: PsiClass): String = when {
        cls.isAnnotationType -> "@interface"
        cls.isInterface -> "interface"
        cls.isEnum -> "enum"
        cls.isRecord -> "record"
        else -> "class"
    }

    private fun classTitle(cls: PsiClass): String = buildString {
        append(keyword(cls)).append(' ').append(cls.name)
        val extends = cls.extendsList?.referenceElements.orEmpty().mapNotNull { it.referenceName }
        if (extends.isNotEmpty()) append(" extends ").append(extends.joinToString(", "))
        val implements = cls.implementsList?.referenceElements.orEmpty().mapNotNull { it.referenceName }
        if (implements.isNotEmpty()) append(" implements ").append(implements.joinToString(", "))
    }

    private fun headerTitle(file: PsiJavaFile): String {
        val imports = file.importList?.allImportStatements?.size ?: 0
        val where = if (file.packageName.isEmpty()) "default package" else "package ${file.packageName}"
        return "$where · $imports imports"
    }

    /** Java PSI ranges include the Javadoc and the annotations. */
    internal fun rangeOf(element: PsiElement): SourceRange = SourceRange(element.textRange.startOffset, element.textRange.endOffset)

    /** Methods [element] calls or refers to (`this::toDto`), constructors excluded. */
    internal fun calledMethods(element: PsiElement): List<PsiMethod> =
        (PsiTreeUtil.findChildrenOfType(element, PsiMethodCallExpression::class.java).mapNotNull { it.resolveMethod() } +
            PsiTreeUtil.findChildrenOfType(element, PsiMethodReferenceExpression::class.java).mapNotNull { it.resolve() as? PsiMethod })
            .filter { !it.isConstructor }

    private fun calls(methods: List<PsiMethod>): List<Link> =
        methods.flatMap { caller ->
            calledMethods(caller)
                .filter { callee -> callee != caller && callee in methods }
                .distinct()
                .map { callee -> Link(LinkKind.CALLS, methodId(caller), methodId(callee)) }
        }

    internal fun classByFqn(context: PsiElement, fqn: String): PsiClass? =
        JavaPsiFacade.getInstance(context.project).findClass(fqn, GlobalSearchScope.allScope(context.project))

    /** Project code only: libraries are never pulled onto the canvas as callees. */
    internal fun inProject(element: PsiElement): Boolean {
        val files = ProjectFileIndex.getInstance(element.project)
        return element.containingFile?.virtualFile?.let { files.isInContent(it) && !files.isInLibrary(it) } == true
    }

    /** True when [cls] only exists as compiled code, so there is nothing to show but its name and members. */
    internal fun isSourceless(cls: PsiClass): Boolean {
        val source = cls.navigationElement
        return source is PsiCompiledElement || source.containingFile?.virtualFile == null
    }
}
```

`src/main/kotlin/dev/codelanes/java/JavaMethodFinder.kt`:

```kotlin
package dev.codelanes.java

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.codelanes.lang.MethodRef

/** Every method of every top-level Java type in the project's own code, for Add method… */
object JavaMethodFinder {
    /** Needs indexes and a read action. Sorted by label. */
    fun find(project: Project): List<MethodRef> {
        val psiManager = PsiManager.getInstance(project)
        val files = ProjectFileIndex.getInstance(project)
        val result = mutableListOf<MethodRef>()
        for (virtualFile in FileTypeIndex.getFiles(JavaFileType.INSTANCE, GlobalSearchScope.projectScope(project))) {
            ProgressManager.checkCanceled()
            if (files.isInLibrary(virtualFile)) continue
            val file = psiManager.findFile(virtualFile) as? PsiJavaFile ?: continue
            for (cls in file.classes) {
                val fqn = JavaBlockBuilder.fqn(cls)
                for (method in JavaBlockBuilder.ownMethods(cls)) {
                    result += MethodRef("${cls.name}::${JavaBlockBuilder.methodTitle(method)}  ${file.packageName}", fqn, JavaBlockBuilder.methodKey(method))
                }
            }
        }
        return result.sortedBy { it.label }
    }
}
```

`src/main/kotlin/dev/codelanes/java/JavaLanguageSupport.kt` (flow methods filled in Task 8):

```kotlin
package dev.codelanes.java

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import dev.codelanes.lang.EntryPoint
import dev.codelanes.lang.FlowLookup
import dev.codelanes.lang.FlowTarget
import dev.codelanes.lang.LanguageSupport
import dev.codelanes.lang.MethodRef
import dev.codelanes.model.BuildResult

class JavaLanguageSupport : LanguageSupport {
    override fun handles(file: VirtualFile): Boolean = FileTypeRegistry.getInstance().isFileOfType(file, JavaFileType.INSTANCE)

    override fun unsupportedReason(file: PsiFile): String? = JavaBlockBuilder.unsupportedReason(file)

    override fun build(file: PsiFile, revealed: Set<String>, followedCalls: Set<String>, added: Set<String>): BuildResult =
        JavaBlockBuilder.build(file, revealed, followedCalls, added)

    override fun methods(project: Project): List<MethodRef> = JavaMethodFinder.find(project)

    /** Spring routes come in a later project; until then Java flows come from the AI. */
    override fun routes(project: Project): List<EntryPoint> = emptyList()

    override fun findFlowTarget(project: Project, reference: String): FlowLookup? = null

    override fun proves(kind: String, from: FlowTarget, to: FlowTarget): Boolean = false

    override fun newMethodText(name: String, indent: String, inInterface: Boolean): String =
        if (inInterface) "${indent}void $name();\n"
        else "${indent}public void $name() {\n$indent}\n"
}
```

`src/main/resources/META-INF/codelanes-java.xml`:

```xml
<!-- Loaded only when the Java plugin is there (IntelliJ IDEA, Android Studio). -->
<idea-plugin>
    <extensions defaultExtensionNs="dev.codelanes">
        <languageSupport implementation="dev.codelanes.java.JavaLanguageSupport"/>
    </extensions>
</idea-plugin>
```

`plugin.xml`, after the PHP `<depends>`:

```xml
    <depends optional="true" config-file="codelanes-java.xml">com.intellij.java</depends>
```

- [ ] **Step 5: Run the Java tests, then everything**

Run: `./gradlew test --tests 'dev.codelanes.java.*' > build/t5.log 2>&1; echo $?`
Expected: `0`. If `testOverloadsGetTheirParameterTypesInTheirIds` prints a type differently (e.g. `List<Order>` survives), fix `simpleType`, not the test. If `ownMethods` lets `values()`/`valueOf()` of the enum through, add `it.isPhysical` to its filter.
Then the whole suite: expected `0`, `303 0` (292 + 11).

- [ ] **Step 6: Commit**

```bash
git add -A src/main src/test
git commit -m "feat: Java classes open as blocks (header, class, methods, own calls)"
```

---

### Task 6: Java lanes: parents, interfaces, injected types, overrides, `+ parents`

**Files:**
- Modify: `src/main/kotlin/dev/codelanes/java/JavaBlockBuilder.kt`
- Test: `src/test/kotlin/dev/codelanes/java/JavaBlockBuilderLanesTest.kt`

**Interfaces:**
- Consumes: Task 5's `JavaBlockBuilder` helpers, `Block.sourceless`.
- Produces: in `JavaBlockBuilder`: `internal fun externalBlock(kind: BlockKind, target: PsiClass): Block` (used by Task 7).

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/dev/codelanes/java/JavaBlockBuilderLanesTest.kt`:

```kotlin
package dev.codelanes.java

import dev.codelanes.model.BlockKind
import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind

class JavaBlockBuilderLanesTest : JavaBuilderTestCase() {
    override fun setUp() {
        super.setUp()
        addSpringStubs()
        addClass("package com.acme; public interface Repo<T> { void save(T item); }")
        addClass("package com.acme; public interface Clock { long now(); }")
        addClass("package com.acme; public interface Mailer { void send(); }")
        addClass("package com.acme; public abstract class BaseService { public abstract void place(); }")
        addClass("package com.acme; public interface Audited extends Clock {}")
    }

    fun testParentAndInterfaceLanes() {
        val model = supported("""
            package com.acme;
            public class OrderService extends BaseService implements Audited {
                public void place() {}
                public long now() { return 0; }
            }
        """.trimIndent())
        val cls = "class:com.acme.OrderService"
        assertTrue(model.links.contains(Link(LinkKind.EXTENDS, "parent:com.acme.BaseService", cls)))
        assertTrue(model.links.contains(Link(LinkKind.IMPLEMENTS, "interface:com.acme.Audited", cls)))
        assertEquals(listOf("place()"), model.block("parent:com.acme.BaseService").summary)
        assertTrue(model.links.any { it.kind == LinkKind.OVERRIDES && it.from == "method:place" && it.to == "parent:com.acme.BaseService" })
    }

    fun testAnInterfaceExtendingAnotherShowsItInTheInterfaceLane() {
        val model = supported("package com.acme; public interface Timed extends Clock { }", "Timed.java")
        assertTrue(model.links.contains(Link(LinkKind.EXTENDS, "interface:com.acme.Clock", "class:com.acme.Timed")))
    }

    fun testPlusParentsDigsDeeper() {
        val model = supported("package com.acme; public class Svc implements Audited { public long now() { return 0; } }", "Svc.java",
            revealed = setOf("interface:com.acme.Audited"))
        assertTrue(model.links.contains(Link(LinkKind.EXTENDS, "interface:com.acme.Clock", "interface:com.acme.Audited")))
    }

    fun testConstructorAndAutowiredFieldsAreInjected() {
        val model = supported("""
            package com.acme;
            import org.springframework.beans.factory.annotation.Autowired;
            public class OrderService {
                @Autowired private Mailer mailer;
                private Clock notInjected;
                public OrderService(Clock clock, int retries, String name) {}
            }
        """.trimIndent())
        val deps = model.ofKind(BlockKind.DEPENDENCY).map { it.id }.toSet()
        assertEquals(setOf("dependency:com.acme.Clock", "dependency:com.acme.Mailer"), deps)
        assertTrue(model.links.contains(Link(LinkKind.INJECTS, "dependency:com.acme.Mailer", "class:com.acme.OrderService")))
    }

    fun testLombokRequiredArgsConstructorInjectsFinalFields() {
        val model = supported("""
            package com.acme;
            import lombok.RequiredArgsConstructor;
            @RequiredArgsConstructor
            public class OrderService {
                private final Mailer mailer;
                private Clock notFinal;
            }
        """.trimIndent())
        assertEquals(listOf("dependency:com.acme.Mailer"), model.ofKind(BlockKind.DEPENDENCY).map { it.id })
    }

    fun testLombokSkipsStaticAndInitializedFields() {
        val model = supported("""
            package com.acme;
            @lombok.RequiredArgsConstructor
            public class OrderService {
                private static final Mailer DEFAULT = null;
                private final Clock clock = null;
                private final Repo<String> repo;
            }
        """.trimIndent())
        assertEquals(listOf("dependency:com.acme.Repo"), model.ofKind(BlockKind.DEPENDENCY).map { it.id })
    }

    fun testGenericProjectTypeIsADependency() {
        val model = supported("package com.acme; public class S { public S(Repo<String> repo) {} }", "S.java")
        assertEquals(listOf("dependency:com.acme.Repo"), model.ofKind(BlockKind.DEPENDENCY).map { it.id })
    }

    fun testJdkTypesAreNotDependencies() {
        addClass("package java.util; public interface List<E> {}")
        addClass("package javax.inject; public interface Provider<T> { T get(); }")
        val model = supported("package com.acme; public class S { public S(java.util.List<Clock> all, javax.inject.Provider<Clock> p) {} }", "S.java")
        assertEquals(listOf("dependency:javax.inject.Provider"), model.ofKind(BlockKind.DEPENDENCY).map { it.id })
    }

    fun testAnUnresolvedLombokAnnotationStillCounts() {
        // No lombok.AllArgsConstructor import and no such class with this name in the default package: matched by short name.
        val model = supported("package com.acme; @AllArgsConstructor public class S { private Mailer m; }", "S.java")
        assertEquals(listOf("dependency:com.acme.Mailer"), model.ofKind(BlockKind.DEPENDENCY).map { it.id })
    }

    fun testALibraryParentWithoutSourceIsSourceless() {
        val model = supported("package com.acme; public class MyTest extends junit.framework.TestCase { }", "MyTest.java")
        val parent = model.block("parent:junit.framework.TestCase")
        assertTrue(parent.sourceless)
        assertTrue(parent.collapsed)
        assertTrue(parent.summary.isNotEmpty())
    }
}
```

- [ ] **Step 2: Run them**

Run: `./gradlew test --tests 'dev.codelanes.java.JavaBlockBuilderLanesTest' > build/t6.log 2>&1; grep -E "FAILED|Assertion" build/t6.log | head`
Expected: the tests fail (no parent, interface or dependency blocks yet).

- [ ] **Step 3: Implement**

Add to `JavaBlockBuilder` (imports: `com.intellij.psi.PsiAnnotation`, `PsiClassType`, `PsiModifier`, `PsiReferenceList`, `PsiTypeParameter`, `PsiEnumConstant`):

```kotlin
    private val INJECT_ANNOTATIONS = setOf(
        "org.springframework.beans.factory.annotation.Autowired",
        "javax.inject.Inject", "jakarta.inject.Inject",
        "javax.annotation.Resource", "jakarta.annotation.Resource",
    )

    internal fun externalBlock(kind: BlockKind, target: PsiClass): Block {
        val sourceless = isSourceless(target)
        val source = if (sourceless) target else (target.navigationElement as? PsiClass ?: target)
        return Block(
            id = "${kind.name.lowercase()}:${fqn(target)}",
            kind = kind,
            title = "${keyword(target)} ${target.name}",
            filePath = source.containingFile?.virtualFile?.path.orEmpty(),
            range = if (sourceless) SourceRange(0, 0) else rangeOf(source),
            collapsed = true,
            summary = ownMethods(source).map(::methodTitle),
            sourceless = sourceless,
        )
    }

    private data class Related(val block: Block, val linkKind: LinkKind, val type: PsiClass)

    private fun resolved(list: PsiReferenceList?): List<PsiClass> =
        list?.referenceElements.orEmpty().mapNotNull { it.resolve() as? PsiClass }.distinctBy(::fqn)

    /** Interfaces, then parents, each with the link kind pointing into the class block. */
    private fun relatedTypes(cls: PsiClass): List<Related> {
        val parentKind = if (cls.isInterface) BlockKind.INTERFACE else BlockKind.PARENT
        return resolved(cls.implementsList).map { Related(externalBlock(BlockKind.INTERFACE, it), LinkKind.IMPLEMENTS, it) } +
            resolved(cls.extendsList).map { Related(externalBlock(parentKind, it), LinkKind.EXTENDS, it) }
    }

    /** A method links to each related type that declares a method it overrides. */
    private fun overrides(methods: List<PsiMethod>, related: List<Related>): List<Link> =
        methods.flatMap { method ->
            val supers = method.findSuperMethods().toSet()
            related.mapNotNull { r ->
                r.type.methods.firstOrNull { it in supers }?.let { overridden ->
                    val landing = (overridden.navigationElement as? PsiMethod)?.takeUnless { r.block.sourceless }
                    Link(LinkKind.OVERRIDES, methodId(method), r.block.id, landing?.let(::rangeOf))
                }
            }
        }

    /** Same as PhpBlockBuilder.revealDeeper: parents of revealed blocks, as deep as revealed. */
    private fun revealDeeper(related: List<Related>, revealed: Set<String>, classId: String): Pair<List<Block>, List<Link>> {
        val known = related.associateBy { it.block.id }.toMutableMap()
        val queue = ArrayDeque(revealed.filter { it in known })
        val seen = mutableSetOf<String>()
        val blocks = mutableListOf<Block>()
        val links = mutableListOf<Link>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            for (deeper in relatedTypes(known.getValue(id).type)) {
                val deeperId = deeper.block.id
                if (deeperId == classId) continue
                if (deeperId !in known) {
                    known[deeperId] = deeper
                    blocks += deeper.block
                }
                links += Link(deeper.linkKind, deeperId, id)
                if (deeperId in revealed) queue += deeperId
            }
        }
        return blocks to links.distinct()
    }

    /**
     * Injected types: constructor parameters, @Autowired/@Inject/@Resource fields, and the fields Lombok's
     * @RequiredArgsConstructor (final, no initializer) or @AllArgsConstructor (all) turn into constructor parameters.
     */
    private fun dependencies(cls: PsiClass): List<Block> {
        val fields = cls.fields.filter { it !is PsiEnumConstant && !it.hasModifierProperty(PsiModifier.STATIC) }
        val types = mutableListOf<PsiType>()
        cls.constructors.forEach { constructor -> constructor.parameterList.parameters.forEach { types += it.type } }
        fields.filter { field -> field.annotations.any { annotated(it, INJECT_ANNOTATIONS) } }.forEach { types += it.type }
        if (cls.annotations.any { annotated(it, setOf("lombok.RequiredArgsConstructor")) }) {
            fields.filter { it.hasModifierProperty(PsiModifier.FINAL) && it.initializer == null }.forEach { types += it.type }
        }
        if (cls.annotations.any { annotated(it, setOf("lombok.AllArgsConstructor")) }) fields.forEach { types += it.type }
        return types.mapNotNull { (it as? PsiClassType)?.resolve() }
            .filter { it !is PsiTypeParameter && fqn(it) != fqn(cls) && !isJdk(it) }
            .distinctBy(::fqn)
            .map { externalBlock(BlockKind.DEPENDENCY, it) }
    }

    /** By qualified name when the annotation resolves, else by its short name. */
    private fun annotated(annotation: PsiAnnotation, names: Set<String>): Boolean {
        if (annotation.resolveAnnotationType() != null) return annotation.qualifiedName in names
        val short = annotation.nameReferenceElement?.referenceName ?: return false
        return names.any { it.substringAfterLast('.') == short }
    }

    private fun isJdk(cls: PsiClass): Boolean {
        val name = fqn(cls)
        return name.startsWith("java.") || (name.startsWith("javax.") && !name.startsWith("javax.inject."))
    }
```

In `build`, replace `// Task 6: …` with:

```kotlin
        val related = relatedTypes(cls)
        related.forEach { (block, linkKind, _) ->
            blocks += block
            links += Link(linkKind, block.id, classId)
        }
        links += overrides(methods, related)
        revealDeeper(related, revealed, classId).let { (deeperBlocks, deeperLinks) ->
            blocks += deeperBlocks
            links += deeperLinks
        }
        dependencies(cls).forEach { block ->
            if (blocks.none { it.id == block.id }) blocks += block
            links += Link(LinkKind.INJECTS, block.id, classId)
        }
```

(Keep the order: header, class, methods, related, deeper, dependencies; Task 7 inserts follow-calls before `revealDeeper` and implementers after it, like PHP.)

- [ ] **Step 4: Run the Java tests, then everything**

Expected: `JavaBlockBuilderLanesTest` green. If `testAnUnresolvedLombokAnnotationStillCounts` fails because `addSpringStubs` made `lombok.AllArgsConstructor` resolvable through a default import, it is still unresolved here (no `import lombok.*`): debug, don't loosen the test. Whole suite: `0`, `313 0`.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat: Java parents, interfaces, injected beans (incl. Lombok) and overrides"
```

---

### Task 7: Java implementers, `+ calls`, methods added from search

**Files:**
- Modify: `src/main/kotlin/dev/codelanes/java/JavaBlockBuilder.kt`
- Test: `src/test/kotlin/dev/codelanes/java/JavaBlockBuilderCallsTest.kt`

**Interfaces:**
- Consumes: Task 5/6 helpers (`externalBlock`, `methodKey`, `findOwnMethod`, `calledMethods`, `inProject`, `classByFqn`, `isSourceless`).
- Produces: callee ids `callee:<fqn>::<methodKey>`, implementation ids `implementation:<fqn>::<methodKey>`; added keys `"<fqn>::<methodKey>"` and `"<fqn>::class"`.

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/dev/codelanes/java/JavaBlockBuilderCallsTest.kt`:

```kotlin
package dev.codelanes.java

import dev.codelanes.model.BlockKind
import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind

class JavaBlockBuilderCallsTest : JavaBuilderTestCase() {
    override fun setUp() {
        super.setUp()
        addClass("package com.acme; public interface OrderRepository { void save(Order o); }")
        addClass("package com.acme; public class Order {}")
        addClass("package com.acme; public class JpaOrderRepository implements OrderRepository { public void save(Order o) {} }")
        addClass("package com.acme; public class Mailer { public void send(Order o) {} public void send(Order o, int copies) {} }")
    }

    private val controller = """
        package com.acme;
        public class OrderController {
            private final OrderRepository repo;
            private final Mailer mailer;
            public OrderController(OrderRepository repo, Mailer mailer) { this.repo = repo; this.mailer = mailer; }
            public void create() {
                Order o = new Order();
                repo.save(o);
                mailer.send(o, 2);
            }
        }
    """.trimIndent()

    fun testPlusCallsShowsCalleesAndTheImplementationsOfInterfaceCalls() {
        val model = supported(controller, "OrderController.java", followed = setOf("method:create"))
        assertTrue(model.links.contains(Link(LinkKind.CALLS_INTO, "method:create", "callee:com.acme.OrderRepository::save")))
        assertTrue(model.links.contains(Link(LinkKind.CALLS_INTO, "method:create", "callee:com.acme.Mailer::send(Order, int)")))
        assertTrue(model.links.contains(Link(LinkKind.IMPLEMENTED_BY, "callee:com.acme.OrderRepository::save", "callee:com.acme.JpaOrderRepository::save")))
        assertEquals("Mailer::send(Order o, int copies)", model.block("callee:com.acme.Mailer::send(Order, int)").title)
    }

    fun testNoCalleesWithoutPlusCalls() {
        assertTrue(supported(controller, "OrderController.java").ofKind(BlockKind.CALLEE).isEmpty())
    }

    fun testAMethodAddedFromSearchGetsABlockAndCanBeFollowed() {
        val model = supported(controller, "OrderController.java",
            added = setOf("com.acme.JpaOrderRepository::save", "com.acme.Order::class"),
            followed = setOf("callee:com.acme.JpaOrderRepository::save"))
        assertEquals("JpaOrderRepository::save(Order o)", model.block("callee:com.acme.JpaOrderRepository::save").title)
        assertFalse(model.block("callee:com.acme.Order::class").collapsed)
    }

    fun testAnAddedMethodThatNoLongerExistsIsLeftOut() {
        val model = supported(controller, "OrderController.java", added = setOf("com.acme.Mailer::gone", "com.acme.Nope::x"))
        assertTrue(model.ofKind(BlockKind.CALLEE).isEmpty())
    }

    fun testAnInterfaceShowsItsImplementersAndTheirMethods() {
        addClass("package com.acme; public class MailNotifier implements Notifier { public void notify(Order o) {} public void other() {} }")
        val model = supported("package com.acme; public interface Notifier { void notify(Order o); }", "Notifier.java")
        assertTrue(model.links.contains(Link(LinkKind.IMPLEMENTED_BY, "class:com.acme.Notifier", "implementer:com.acme.MailNotifier")))
        assertEquals(listOf("notify(Order o)"), model.block("implementer:com.acme.MailNotifier").summary)
        assertTrue(model.links.contains(Link(LinkKind.OVERRIDES, "implementation:com.acme.MailNotifier::notify", "method:notify",
            model.block("method:notify").range)))
    }

    fun testTheChainStopsAtTheCap() {
        // 12 classes, each calling the next: following them all stays within the 80-block cap and notes the 10-callee cap.
        val calls = (1..12).joinToString("\n") { "        new C$it().go();" }
        (1..12).forEach { addClass("package com.acme; public class C$it { public void go() {} }") }
        val model = supported("package com.acme;\npublic class Big {\n    void run() {\n$calls\n    }\n}\n", "Big.java", followed = setOf("method:run"))
        assertEquals(10, model.ofKind(BlockKind.CALLEE).size)
        assertEquals("and 2 more calls", model.block("more:calls:method:run").title)
    }
}
```

- [ ] **Step 2: Run them**

Expected: they fail (no callee/implementer blocks yet).

- [ ] **Step 3: Implement**

Add to `JavaBlockBuilder` (imports: `com.intellij.psi.PsiAnonymousClass`, `com.intellij.psi.search.searches.ClassInheritorsSearch`, `com.intellij.psi.search.searches.OverridingMethodsSearch`):

```kotlin
    private fun isAbstract(method: PsiMethod) = method.hasModifierProperty(PsiModifier.ABSTRACT)

    private fun calleeBlock(id: String, method: PsiMethod): Block {
        val owner = method.containingClass!!
        if (isSourceless(owner)) {
            return Block(id, BlockKind.CALLEE, "${owner.name}::${methodTitle(method)}", owner.containingFile?.virtualFile?.path.orEmpty(),
                SourceRange(0, 0), collapsed = true, sourceless = true)
        }
        val source = method.navigationElement as? PsiMethod ?: method
        return Block(id, BlockKind.CALLEE, "${owner.name}::${methodTitle(source)}", source.containingFile.virtualFile.path, rangeOf(source))
    }

    /** "Fqn::key" → that method of that class, or null if either no longer exists. */
    private fun methodByKey(context: PsiElement, key: String): PsiMethod? {
        val cls = classByFqn(context, key.substringBefore("::")) ?: return null
        return findOwnMethod(cls, key.substringAfter("::"))
    }

    /** Project implementations of an interface/abstract [method] (at most [MAX_IMPLEMENTATIONS]). */
    private fun implementationsOf(method: PsiMethod): List<Pair<String, PsiMethod>> =
        OverridingMethodsSearch.search(method, GlobalSearchScope.projectScope(method.project), true).findAll()
            .filter { !isAbstract(it) && inProject(it) && it.containingClass !is PsiAnonymousClass }
            .mapNotNull { impl -> impl.containingClass?.let { "callee:${fqn(it)}::${methodKey(impl)}" to impl } }
            .sortedBy { it.first }
            .distinctBy { it.first }
            .take(MAX_IMPLEMENTATIONS)

    /** "+ calls", as in PhpBlockBuilder.followCalls: callees in other project classes become editable blocks. */
    private fun followCalls(cls: PsiClass, methods: List<PsiMethod>, followed: Set<String>, added: Set<String>, path: String): Pair<List<Block>, List<Link>> {
        if (followed.isEmpty() && added.isEmpty()) return emptyList<Block>() to emptyList()
        val queue = ArrayDeque(methods.map { methodId(it) to it }.filter { it.first in followed })
        val seen = mutableSetOf<String>()
        val blocks = linkedMapOf<String, Block>()
        var truncated = false
        val firstRoot = queue.firstOrNull()?.first
        for (key in added) {
            if (key.endsWith("::class")) {
                val target = classByFqn(cls, key.removeSuffix("::class")) ?: continue
                val id = "callee:$key"
                blocks.getOrPut(id) { externalBlock(BlockKind.CALLEE, target).let { if (it.sourceless) it.copy(id = id) else it.copy(id = id, collapsed = false) } }
                continue
            }
            val method = methodByKey(cls, key) ?: continue
            val id = "callee:${fqn(method.containingClass!!)}::${methodKey(method)}"
            blocks.getOrPut(id) { calleeBlock(id, method) }
            if (id in followed) queue += id to method
        }
        val links = mutableListOf<Link>()
        while (queue.isNotEmpty()) {
            val (id, caller) = queue.removeFirst()
            if (!seen.add(id)) continue
            if (blocks.size >= MAX_CHAIN_BLOCKS) {
                truncated = true
                break
            }
            val allCallees = calledMethods(caller)
                .filter { it.containingClass.let { c -> c != null && fqn(c) != fqn(cls) } && inProject(it) }
                .distinct()
            val callees = allCallees.take(MAX_CALLEES)
            if (allCallees.size > MAX_CALLEES) {
                val moreId = "more:calls:$id"
                blocks[moreId] = Block(moreId, BlockKind.MORE, "and ${allCallees.size - MAX_CALLEES} more calls", path, SourceRange(0, 0), collapsed = true)
                links += Link(LinkKind.CALLS_INTO, id, moreId)
            }
            for (callee in callees) {
                if (blocks.size >= MAX_CHAIN_BLOCKS) {
                    truncated = true
                    break
                }
                val owner = callee.containingClass!!
                val calleeId = "callee:${fqn(owner)}::${methodKey(callee)}"
                blocks.getOrPut(calleeId) { calleeBlock(calleeId, callee) }
                links += Link(LinkKind.CALLS_INTO, id, calleeId)
                if (calleeId in followed) queue += calleeId to callee
                // A call into an interface/abstract method: the real work happens in its implementations.
                if (owner.isInterface || isAbstract(callee)) {
                    for ((implId, implementation) in implementationsOf(callee)) {
                        blocks.getOrPut(implId) { calleeBlock(implId, implementation) }
                        links += Link(LinkKind.IMPLEMENTED_BY, calleeId, implId)
                        if (implId in followed) queue += implId to implementation
                    }
                }
            }
        }
        if (truncated && firstRoot != null) {
            blocks[MORE_CHAIN_ID] = Block(
                MORE_CHAIN_ID, BlockKind.MORE, "chain cut off at $MAX_CHAIN_BLOCKS blocks — use + calls on a block to go further",
                path, SourceRange(0, 0), collapsed = true,
            )
            links += Link(LinkKind.CALLS_INTO, firstRoot, MORE_CHAIN_ID)
        }
        return blocks.values.toList() to links.distinct()
    }

    /**
     * For an interface or abstract class: the project classes that implement / extend it (at most [MAX_IMPLEMENTERS],
     * alphabetical, plus a "more" block), and per class only the methods that implement or override one of this type's.
     */
    private fun implementedBy(cls: PsiClass, classId: String, methods: List<PsiMethod>, path: String): Pair<List<Block>, List<Link>> {
        if (!cls.isInterface && !cls.hasModifierProperty(PsiModifier.ABSTRACT)) return emptyList<Block>() to emptyList()
        val candidates = ClassInheritorsSearch.search(cls, GlobalSearchScope.projectScope(cls.project), false).findAll()
            .filter { !it.isInterface && it !is PsiAnonymousClass && fqn(it) != fqn(cls) && inProject(it) }
            .distinctBy(::fqn)
            .sortedWith(compareBy({ it.name }, { fqn(it) }))
        val blocks = mutableListOf<Block>()
        val links = mutableListOf<Link>()
        for (implementer in candidates.take(MAX_IMPLEMENTERS)) {
            val implemented = methods.mapNotNull { implementer.findMethodBySignature(it, false) }
            val block = externalBlock(BlockKind.IMPLEMENTER, implementer).copy(summary = implemented.map(::methodTitle))
            blocks += block
            links += Link(LinkKind.IMPLEMENTED_BY, classId, block.id)
            for (method in methods) {
                val implementation = implementer.findMethodBySignature(method, false) ?: continue
                val id = "implementation:${fqn(implementer)}::${methodKey(implementation)}"
                blocks += Block(id, BlockKind.IMPLEMENTATION, "${implementer.name}::${methodTitle(implementation)}",
                    implementer.containingFile.virtualFile.path, rangeOf(implementation), collapsed = true)
                links += Link(LinkKind.OWNS, block.id, id)
                links += Link(LinkKind.OVERRIDES, id, methodId(method), rangeOf(method))
            }
        }
        val rest = candidates.drop(MAX_IMPLEMENTERS)
        if (rest.isNotEmpty()) {
            blocks += Block(MORE_ID, BlockKind.MORE, "and ${rest.size} more", path, SourceRange(0, 0), collapsed = true, summary = rest.mapNotNull { it.name })
            links += Link(LinkKind.IMPLEMENTED_BY, classId, MORE_ID)
        }
        return blocks to links
    }
```

In `build`, replace `// Task 7: …` so the order matches `PhpBlockBuilder.build` (follow-calls after overrides, before revealDeeper; implementers after revealDeeper):

```kotlin
        followCalls(cls, methods, followedCalls, added, path).let { (calleeBlocks, calleeLinks) ->
            blocks += calleeBlocks
            links += calleeLinks
        }
        // (revealDeeper from Task 6 here)
        implementedBy(cls, classId, methods, path).let { (implBlocks, implLinks) ->
            blocks += implBlocks
            links += implLinks
        }
```

`BlockModel` requires unique ids: a type that is both a dependency and a callee `::class` has different prefixes, so no clash; keep the `blocks.none { it.id == block.id }` guard from Task 6 for dependencies.

- [ ] **Step 4: Run the Java tests, then everything**

Expected: `JavaBlockBuilderCallsTest` green; whole suite `0`, `319 0`.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat: + calls, implementers and Add method… for Java"
```

---

### Task 8: AI flows with Java references

**Files:**
- Create: `src/main/kotlin/dev/codelanes/java/JavaFlowLookup.kt`
- Modify: `src/main/kotlin/dev/codelanes/java/JavaLanguageSupport.kt`
- Modify: `src/main/kotlin/dev/codelanes/mcp/FlowTools.kt` (description and "PhpStorm" wording)
- Test: `src/test/kotlin/dev/codelanes/java/JavaFlowLookupTest.kt`

**Interfaces:**
- Consumes: `FlowTarget`, `FlowLookup`, `FlowResolver.resolve` (Task 3); `JavaBlockBuilder.fqn/methodKey/findOwnMethod/calledMethods/classByFqn` (Task 5).
- Produces: `JavaFlowLookup.find(project, reference, support): FlowLookup?`, `JavaFlowLookup.proves(kind, from, to): Boolean`.

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/dev/codelanes/java/JavaFlowLookupTest.kt`:

```kotlin
package dev.codelanes.java

import dev.codelanes.flow.Flow
import dev.codelanes.flow.FlowEntry
import dev.codelanes.flow.FlowHop
import dev.codelanes.flow.FlowResolver
import dev.codelanes.flow.ResolvedFlow
import dev.codelanes.flow.ResolvedHop

class JavaFlowLookupTest : JavaBuilderTestCase() {
    override fun setUp() {
        super.setUp()
        addSpringStubs()
        myFixture.addFileToProject("com/acme/OrderController.java", """
            package com.acme;
            public class OrderController {
                private final OrderService service;
                public OrderController(OrderService service) { this.service = service; }
                public void create() { service.place(new Order()); }
                public void create(int n) {}
            }
        """.trimIndent())
        myFixture.addFileToProject("com/acme/OrderService.java", """
            package com.acme;
            import org.springframework.context.ApplicationEventPublisher;
            public class OrderService extends BaseService {
                private final OrderRepository repo;
                private final ApplicationEventPublisher events;
                public OrderService(OrderRepository repo, ApplicationEventPublisher events) { this.repo = repo; this.events = events; }
                public void place(Order o) { repo.save(o); events.publishEvent(new OrderPlaced(o)); }
                public void cancel(Order o) {}
            }
        """.trimIndent())
        myFixture.addFileToProject("com/acme/BaseService.java", "package com.acme;\npublic class BaseService { public void audit() {} }")
        myFixture.addFileToProject("com/acme/Order.java", "package com.acme;\npublic class Order {}")
        myFixture.addFileToProject("com/acme/OrderPlaced.java", "package com.acme;\npublic class OrderPlaced { public OrderPlaced(Order o) {} }")
        myFixture.addFileToProject("com/acme/OrderRepository.java", "package com.acme;\npublic interface OrderRepository { void save(Order o); }")
        myFixture.addFileToProject("com/acme/JpaOrderRepository.java", "package com.acme;\npublic class JpaOrderRepository implements OrderRepository { public void save(Order o) {} }")
        myFixture.addFileToProject("com/acme/SendOrderMail.java", """
            package com.acme;
            import org.springframework.context.event.EventListener;
            public class SendOrderMail { @EventListener public void on(OrderPlaced event) {} public void other(Order o) {} }
        """.trimIndent())
    }

    private val create = "com.acme.OrderController::create"
    private val place = "com.acme.OrderService::place"

    private fun resolve(vararg hops: FlowHop, entry: String = create): ResolvedFlow =
        FlowResolver.resolve(project, Flow("POST /orders", FlowEntry("POST /orders", entry), hops.toList()))

    fun testTheEntryOpensTheControllerOnItsMethod() {
        val resolved = resolve()
        assertNull(resolved.entryProblem)
        assertEquals("OrderController.java", resolved.entryFile!!.name)
        assertEquals("method:create()", resolved.entryId)
    }

    fun testAnOverloadIsPickedBySignatureAndABareNameTakesTheFirst() {
        assertEquals("method:create(int)", resolve(entry = "com.acme.OrderController::create(int)").entryId)
        assertEquals("method:create()", resolve(entry = create).entryId)
    }

    fun testProvenAndClaimedHops() {
        val resolved = resolve(
            FlowHop(create, place, "calls"),
            FlowHop(place, "com.acme.OrderPlaced::class", "dispatches"),
            FlowHop("com.acme.OrderPlaced::class", "com.acme.SendOrderMail::on", "listens"),
            FlowHop("com.acme.OrderRepository::save", "com.acme.JpaOrderRepository::save", "binds"),
            FlowHop(place, "com.acme.OrderService::cancel", "calls"),
            FlowHop("com.acme.OrderPlaced::class", "com.acme.SendOrderMail::other", "listens"),
        )
        assertEquals(listOf(
            ResolvedHop("method:create()", "callee:com.acme.OrderService::place", "calls", true),
            ResolvedHop("callee:com.acme.OrderService::place", "callee:com.acme.OrderPlaced::class", "dispatches", true),
            ResolvedHop("callee:com.acme.OrderPlaced::class", "callee:com.acme.SendOrderMail::on", "listens", true),
            ResolvedHop("callee:com.acme.OrderRepository::save", "callee:com.acme.JpaOrderRepository::save", "binds", true),
            ResolvedHop("callee:com.acme.OrderService::place", "callee:com.acme.OrderService::cancel", "calls", false),
            ResolvedHop("callee:com.acme.OrderPlaced::class", "callee:com.acme.SendOrderMail::other", "listens", false),
        ), resolved.hops)
        assertTrue(resolved.unknown.isEmpty())
    }

    fun testUnknownReferences() {
        val resolved = resolve(FlowHop(create, "com.acme.Nope::x", "calls"), FlowHop(create, "com.acme.OrderService::gone", "calls"))
        assertEquals(listOf("com.acme.Nope::x (no such class)", "com.acme.OrderService::gone (no such method)"), resolved.unknown)
        assertTrue(resolved.hops.isEmpty())
    }

    fun testAnInheritedMethodLandsOnTheClassThatDeclaresIt() {
        val resolved = resolve(FlowHop(create, "com.acme.OrderService::audit", "calls"))
        assertEquals("callee:com.acme.BaseService::audit", resolved.hops.single().toId)
    }

    fun testAConstructorStandsForTheClass() {
        val resolved = resolve(FlowHop(place, "com.acme.OrderPlaced::<init>", "dispatches"))
        assertEquals(ResolvedHop("callee:com.acme.OrderService::place", "callee:com.acme.OrderPlaced::class", "dispatches", true), resolved.hops.single())
    }

    fun testAPhpReferenceIsNotReadAsJava() {
        val resolved = resolve(FlowHop(create, "App\\Services\\OrderService::place", "calls"))
        assertEquals(listOf("App\\Services\\OrderService::place (no such class)"), resolved.unknown)
    }
}
```

Note on `entryId` in `testTheEntryOpensTheControllerOnItsMethod`: `create` is overloaded in `OrderController`, so its key is `create()`. A bare `::create` picks the first in source order (`create()`).

- [ ] **Step 2: Run them**

Expected: failures, `entryProblem` "… (not a Class::method reference)" because no language reads Java references yet.

- [ ] **Step 3: Implement**

`src/main/kotlin/dev/codelanes/java/JavaFlowLookup.kt`:

```kotlin
package dev.codelanes.java

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.MethodSignatureUtil
import com.intellij.psi.util.PsiTreeUtil
import dev.codelanes.lang.FlowLookup
import dev.codelanes.lang.FlowTarget
import dev.codelanes.lang.LanguageSupport

/**
 * Java flow references: `com.acme.OrderService::place`, `::place(Order)` for an overload, `::class` for the class,
 * `::<init>` (or the class's own name) for a constructor, which stands for the class block.
 */
object JavaFlowLookup {
    /** Null when the class part has a backslash: that's PHP's syntax. */
    fun find(project: Project, reference: String, support: LanguageSupport): FlowLookup? {
        val trimmed = reference.trim()
        val classPart = trimmed.substringBefore("::")
        if ('\\' in classPart) return null
        val name = trimmed.substringAfter("::")
        val cls = JavaPsiFacade.getInstance(project).findClass(classPart, GlobalSearchScope.allScope(project))
            ?: return FlowLookup.Missing("$trimmed (no such class)")
        if (name == "class" || name == "<init>" || name.substringBefore('(') == cls.name) return FlowLookup.Found(target(cls, null, support))
        val method = JavaBlockBuilder.findOwnMethod(cls, name) ?: inherited(cls, name)
            ?: return FlowLookup.Missing("$trimmed (no such method)")
        return FlowLookup.Found(target(cls, method, support))
    }

    /** An inherited method, found in the nearest superclass or interface that declares it. */
    private fun inherited(cls: PsiClass, name: String): PsiMethod? {
        val seen = mutableSetOf<PsiClass>()
        val queue = ArrayDeque(cls.supers.toList())
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next)) continue
            JavaBlockBuilder.findOwnMethod(next, name)?.let { return it }
            queue += next.supers
        }
        return null
    }

    private fun target(named: PsiClass, method: PsiMethod?, support: LanguageSupport): FlowTarget {
        val declaring = method?.containingClass ?: named
        val source = declaring.navigationElement as? PsiClass ?: declaring
        return FlowTarget(
            classFqn = JavaBlockBuilder.fqn(named),
            declaringFqn = JavaBlockBuilder.fqn(declaring),
            methodKey = method?.let(JavaBlockBuilder::methodKey),
            isConstructor = false,
            file = source.containingFile,
            classElement = named,
            methodElement = method,
            support = support,
        )
    }

    fun proves(kind: String, from: FlowTarget, to: FlowTarget): Boolean {
        val fromMethod = from.methodElement as? PsiMethod
        val toMethod = to.methodElement as? PsiMethod
        return when (kind) {
            "calls", "dispatches", "middleware" -> fromMethod != null && callsOrCreates(fromMethod, to.classElement as PsiClass, toMethod)
            "binds" -> fromMethod != null && toMethod != null && fromMethod != toMethod && MethodSignatureUtil.isSuperMethod(fromMethod, toMethod)
            "listens" -> {
                val event = (toMethod?.parameterList?.parameters?.firstOrNull()?.type as? PsiClassType)?.resolve()
                event != null && JavaBlockBuilder.fqn(event) == JavaBlockBuilder.fqn(from.classElement as PsiClass)
            }
            else -> false // "routes": nothing in a method proves a route
        }
    }

    /** [from] calls [toMethod], or (for a class target) creates an instance of [toClass] (`publishEvent(new OrderPlaced(o))`). */
    private fun callsOrCreates(from: PsiMethod, toClass: PsiClass, toMethod: PsiMethod?): Boolean {
        if (toMethod != null) return JavaBlockBuilder.calledMethods(from).any { it == toMethod }
        val target = JavaBlockBuilder.fqn(toClass)
        return PsiTreeUtil.findChildrenOfType(from, PsiNewExpression::class.java).any { created ->
            created.classReference?.resolve()?.let { it is PsiClass && JavaBlockBuilder.fqn(it) == target } == true
        }
    }
}
```

`JavaLanguageSupport`:

```kotlin
    override fun findFlowTarget(project: Project, reference: String): FlowLookup? = JavaFlowLookup.find(project, reference, this)

    override fun proves(kind: String, from: FlowTarget, to: FlowTarget): Boolean = JavaFlowLookup.proves(kind, from, to)
```

`FlowTools.kt` description: replace "on the CodeLanes canvas in PhpStorm" with "on the CodeLanes canvas in the IDE", and before "Use fully qualified class names" add:
`Java references use dots and no spaces, e.g. com.acme.OrderController::create, or ::create(Order,int) for one of several overloads; PHP references use backslashes.`
Replace `NO_PROJECT`'s "PhpStorm" with "the IDE" (twice), and the class KDoc "through PhpStorm's MCP server" with "through the IDE's MCP server". Then check `FlowToolsTest` doesn't assert those strings: `grep -n "PhpStorm" src/test/kotlin/dev/codelanes/mcp/FlowToolsTest.kt`; if it does, that is the only allowed assertion change in this plan (ledger it).

- [ ] **Step 4: Run the Java and flow tests, then everything**

Expected: `JavaFlowLookupTest` green, `FlowResolverTest` green; whole suite `0`, `326 0`.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "feat: AI flows accept Java references and prove Spring hops"
```

---

### Task 9: A Java canvas in the session

**Files:**
- Test: `src/test/kotlin/dev/codelanes/java/JavaSessionTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (action text)
- Modify (only if a test shows it): `src/main/kotlin/dev/codelanes/canvas/BlocksSession.kt`

**Interfaces:**
- Consumes: everything above; `BlocksSession(project, virtualFile)`, `session.addMethod()`, `session.followChain(id, depth)`, `session.hasSliceEditor(id)` (see `BlocksSessionTest` for how they are driven; read it first).

- [ ] **Step 1: Write the failing tests**

`src/test/kotlin/dev/codelanes/java/JavaSessionTest.kt` (copy `open()`/the model accessor pattern from `BlocksSessionTest`):

```kotlin
package dev.codelanes.java

import com.intellij.openapi.util.Disposer
import dev.codelanes.canvas.BlocksSession
import dev.codelanes.settings.PinStore

class JavaSessionTest : JavaBuilderTestCase() {
    private fun open(text: String, name: String = "OrderService.java"): BlocksSession {
        val psi = myFixture.configureByText(name, text)
        PinStore.getInstance(project).prune(psi.virtualFile.path, emptySet())
        return BlocksSession(project, psi.virtualFile).also { Disposer.register(testRootDisposable, it) }
    }

    fun testAJavaFileOpensWithItsMethodBlocks() {
        val session = open("package com.acme;\npublic class OrderService {\n    public void place() {}\n}\n")
        assertTrue(session.hasSliceEditor("method:place"))
    }

    fun testPlusMethodAddsAJavaMethod() {
        val session = open("package com.acme;\npublic class OrderService {\n    public void place() {}\n}\n")
        session.addMethod()
        assertTrue(myFixture.editor.document.text.contains("    public void newMethod() {\n    }\n"))
        assertTrue(session.hasSliceEditor("method:newMethod"))
    }

    fun testPlusMethodInAnInterfaceAddsASignature() {
        val session = open("package com.acme;\npublic interface Repo {\n    void save();\n}\n", "Repo.java")
        session.addMethod()
        assertTrue(myFixture.editor.document.text.contains("    void newMethod();\n"))
    }

    fun testFollowChainReachesAnotherClass() {
        addClass("package com.acme; public class Mailer { public void send() {} }")
        val session = open("package com.acme;\npublic class OrderService {\n    private final Mailer m = new Mailer();\n    public void place() { m.send(); }\n}\n")
        session.followChain("method:place", 3)
        assertTrue(session.hasSliceEditor("callee:com.acme.Mailer::send"))
    }
}
```

If `BlocksSessionTest` needs extra calls before asserting (e.g. a rebuild flush or `PlatformTestUtil.dispatchAllEventsInIdeEventQueue()`), use the same ones here.

- [ ] **Step 2: Run them**

Run: `./gradlew test --tests 'dev.codelanes.java.JavaSessionTest' > build/t9.log 2>&1; grep -E "FAILED|Assertion|Exception" build/t9.log | head`
Expected: either all pass (Tasks 2–7 already wired the session) or a failure that shows a remaining PHP assumption in `BlocksSession`. Fix any such assumption in `BlocksSession` (not in the test), ledger it, rerun.

- [ ] **Step 3: Rename the default-view action**

`plugin.xml`: `text="Open PHP Files as Blocks by Default"` → `text="Open Files as Blocks by Default"`. Check for the old text elsewhere: `grep -rn "Open PHP Files" src README.md docs` and update README mentions (Task 10 also edits README).

- [ ] **Step 4: Run everything**

Expected: `0`, `330 0`.

- [ ] **Step 5: Commit**

```bash
git add -A src/main src/test
git commit -m "test: a Java canvas end to end; the default-view action no longer says PHP"
```

---

### Task 10: Playground, docs and the 0.2.0 release build

**Files:**
- Create: `playground/java-spring/` (sources below)
- Modify: `README.md`, `CHANGELOG.md`, `docs/roadmap.md`, `src/main/resources/META-INF/plugin.xml` (description), `build.gradle.kts` (version, change notes)

**Interfaces:**
- Consumes: the finished feature.
- Produces: `build/distributions/codelanes-0.2.0.zip`, verified; the user signs and uploads it.

- [ ] **Step 1: The playground app**

A Spring-style app that compiles without a build tool (annotation stubs included), for opening in the sandbox IDE. Create these files under `playground/java-spring/src/`:

`org/springframework/stereotype/Service.java`: `package org.springframework.stereotype; public @interface Service {}`
`org/springframework/stereotype/Repository.java`: `package org.springframework.stereotype; public @interface Repository {}`
`org/springframework/web/bind/annotation/RestController.java`: `package org.springframework.web.bind.annotation; public @interface RestController {}`
`org/springframework/web/bind/annotation/PostMapping.java`: `package org.springframework.web.bind.annotation; public @interface PostMapping { String value() default ""; }`
`org/springframework/web/bind/annotation/RequestBody.java`: `package org.springframework.web.bind.annotation; public @interface RequestBody {}`
`org/springframework/context/ApplicationEventPublisher.java`: `package org.springframework.context; public interface ApplicationEventPublisher { void publishEvent(Object event); }`
`org/springframework/context/event/EventListener.java`: `package org.springframework.context.event; public @interface EventListener {}`
`lombok/RequiredArgsConstructor.java`: `package lombok; public @interface RequiredArgsConstructor {}`

`com/acme/shop/order/OrderController.java`:

```java
package com.acme.shop.order;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Takes orders from the web shop. */
@RestController
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orders;

    @PostMapping("/orders")
    public OrderResponse create(@RequestBody OrderRequest request) {
        Order order = orders.place(request.customer(), request.items());
        return new OrderResponse(order.id());
    }
}
```

`com/acme/shop/order/OrderService.java`:

```java
package com.acme.shop.order;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository repository;
    private final ApplicationEventPublisher events;

    public Order place(String customer, int items) {
        check(items);
        Order order = repository.save(new Order(0, customer, items));
        events.publishEvent(new OrderPlaced(order));
        return order;
    }

    public Order place(Order order) {
        return place(order.customer(), order.items());
    }

    private void check(int items) {
        if (items <= 0) throw new IllegalArgumentException("An order needs items");
    }
}
```

`com/acme/shop/order/OrderRepository.java`: `package com.acme.shop.order; public interface OrderRepository { Order save(Order order); }`

`com/acme/shop/order/JpaOrderRepository.java`:

```java
package com.acme.shop.order;

import org.springframework.stereotype.Repository;

@Repository
public class JpaOrderRepository implements OrderRepository {
    private int nextId = 1;

    @Override
    public Order save(Order order) {
        return new Order(nextId++, order.customer(), order.items());
    }
}
```

`com/acme/shop/order/Order.java`: `package com.acme.shop.order; public record Order(int id, String customer, int items) {}`
`com/acme/shop/order/OrderRequest.java`: `package com.acme.shop.order; public record OrderRequest(String customer, int items) {}`
`com/acme/shop/order/OrderResponse.java`: `package com.acme.shop.order; public record OrderResponse(int id) {}`
`com/acme/shop/order/OrderPlaced.java`: `package com.acme.shop.order; public record OrderPlaced(Order order) {}`

`com/acme/shop/mail/SendOrderMail.java`:

```java
package com.acme.shop.mail;

import com.acme.shop.order.OrderPlaced;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class SendOrderMail {
    @EventListener
    public void on(OrderPlaced event) {
        System.out.println("Mail for order " + event.order().id());
    }
}
```

`playground/java-spring/README.md`:

```markdown
# Java Spring playground

A small Spring-style shop to try CodeLanes in IntelliJ IDEA. The Spring and Lombok annotations are stubs in `src/`,
so it opens without Maven or Gradle: **File → Open…** this folder, mark `src` as Sources Root if IDEA doesn't.

Try:
- Open `OrderService.java`: the repository and the event publisher sit in the dependency lane (Lombok), `place` is
  overloaded, `check` is called by `place`.
- `+ calls` on `OrderController.create` reaches `OrderRepository.save` and its `JpaOrderRepository` implementation.
- Ask your AI assistant: *"Show the flow of POST /orders in CodeLanes."* It should give
  `com.acme.shop.order.OrderController::create` as entry, `OrderService::place(String,int)`, a `dispatches` hop to
  `com.acme.shop.order.OrderPlaced::class` and a `listens` hop to `com.acme.shop.mail.SendOrderMail::on`.
```

- [ ] **Step 2: Docs**

- `README.md`:
  - Intro: "CodeLanes is a plugin for PhpStorm and IntelliJ IDEA that replaces the vertical, line-by-line editor …" and "PHP and Java classes".
  - Install: requirements "**PhpStorm 2025.2 or newer** for PHP, **IntelliJ IDEA 2025.2 or newer** for Java"; Marketplace steps say "in PhpStorm or IntelliJ IDEA"; drop the "CodeLanes reads PHP only for now …" sentence; the zip name becomes `codelanes-0.2.0.zip`.
  - New short section **Java and Spring** after "Flows from your AI assistant": what maps to which lane (the spec's table, shortened), Lombok injection, overload ids in flows (`::place(Order,int)`), "Follow a Request lists Spring routes in a later version; ask your AI assistant for Spring flows until then", and a pointer to `playground/java-spring`.
  - "Open PHP Files as Blocks by Default" → "Open Files as Blocks by Default".
  - Status: "Usable for reading and editing PHP and Java classes. Next up: Spring routes in Follow a Request, then JAX-RS/Micronaut, Kotlin and TypeScript, and VS Code later."
  - Develop: CI verifies "PhpStorm and IntelliJ IDEA 2025.2, 2025.3, 2026.1 and 2026.2"; `runIde` starts IntelliJ IDEA with PHP, so both playgrounds work in it.
- `CHANGELOG.md`: new section above 0.1.0:

```markdown
## 0.2.0

Java in IntelliJ IDEA. For PhpStorm and IntelliJ IDEA 2025.2 and newer.

- **Java classes as a canvas**: classes, interfaces, enums, records and annotations, with the same lanes as PHP:
  parent class, interfaces, injected beans (constructor, `@Autowired`/`@Inject` fields and Lombok
  `@RequiredArgsConstructor`), methods, calls and overrides; overloaded methods are told apart by their parameters.
- **+ calls**, **+ parents**, **Add method…**, implementers of interfaces and abstract classes, review marks, notes and
  working sets all work for Java.
- **AI flows for Spring**: Java references (`com.acme.OrderController::create`); `binds` (interface → bean), `dispatches`
  (`publishEvent(new …)`) and `listens` (`@EventListener`) are proven from the code.
- Library types without source show their name and methods only.
- The plugin no longer requires PHP: it installs in IntelliJ IDEA without the PHP plugin, and in PhpStorm without Java.
```

- `docs/roadmap.md`: item 4 becomes "**Java** (done in 0.2.0, `docs/superpowers/specs/2026-10-22-java-support-design.md`); next: Spring routes in Follow a Request, then JAX-RS/Micronaut routes, then Kotlin."
- `plugin.xml` description: first paragraph "… replaces the vertical, line-by-line editor for PHP and Java classes …"; "every block is a real IDE editor"; "The file on disk stays plain PHP or Java."; add a bullet `<li><b>Java and Spring</b> in IntelliJ IDEA: injected beans (also through Lombok), overloads, and AI flows that prove Spring's bindings, events and listeners.</li>`.

- [ ] **Step 3: Version and change notes**

`build.gradle.kts`: `version = "0.2.0"`, and the change notes read the current version's section:

```kotlin
        changeNotes = provider { file("CHANGELOG.md").readText().substringAfter("## ${project.version}").substringBefore("\n## ").let(::markdownToHtml) }
```

- [ ] **Step 4: Full check**

```bash
./gradlew test > build/t10.log 2>&1; echo $?
./gradlew buildPlugin verifyPlugin > build/v10.log 2>&1; echo $?
grep -E "Compatible|incompatible|problems" build/v10.log | head -20
unzip -p build/distributions/codelanes-0.2.0.zip 'codelanes/lib/codelanes-0.2.0.jar' > build/check.jar && unzip -p build/check.jar META-INF/plugin.xml | grep -E "version|since-build|depends"
```

Expected: tests `0` (330 0); verify `0`, every one of the 8 IDEs "Compatible" (an experimental-API note is fine, as in 0.1.0); plugin.xml shows `<version>0.2.0</version>`, `since-build="252"`, and three optional `<depends>` (php, java, mcpServer).

If CI's verify job runs out of disk with 8 IDEs, split the IDE list over two CI jobs; ledger it.

- [ ] **Step 5: Manual check in the sandbox (the user does this)**

`./gradlew runIde` starts IntelliJ IDEA with the PHP plugin. Ask the user to:
1. Open `playground/java-spring`, open `OrderService.java`: lanes as in the playground README.
2. Open `playground/laravel`: PHP canvases unchanged.
3. Try an AI flow on the Java playground over MCP.

- [ ] **Step 6: Commit**

```bash
git add -A playground/java-spring README.md CHANGELOG.md docs/roadmap.md src/main/resources/META-INF/plugin.xml build.gradle.kts
git commit -m "release: 0.2.0, Java in IntelliJ IDEA"
```

Then stop: the user tests, says "merge and push", signs the plugin with their key (`./gradlew signPlugin` with `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD` set in their shell) and uploads `build/distributions/codelanes-0.2.0-signed.zip` on the Marketplace.
