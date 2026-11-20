# Kotlin Canvas (Part A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every `.kt` file opens as a CodeLanes canvas in IntelliJ IDEA and Android Studio, with calls, overrides,
dependencies, `+ calls`, `+ method`, Add method…, Spring routes written in Kotlin, and AI flows — including projects
that mix Java and Kotlin.

**Architecture:** A new Gradle submodule `kotlin/` is a content module (`codelanes.kotlin`, depends on
`org.jetbrains.kotlin` and on the `codelanes.java` content module) that registers `KotlinLanguageSupport`, like
`java/` and `python/`. It reuses Python's module layout (several centre blocks) and session hooks, and Java's
external-block code for Java targets. Three small additions to `LanguageSupport` (with defaults that keep today's
behaviour) let Java canvases show Kotlin targets and let flows prove hops between Java and Kotlin.

**Tech Stack:** Kotlin 2.4.21, IntelliJ Platform Gradle Plugin 2.19.0, IntelliJ IDEA 2026.1 with the bundled Kotlin
and Java plugins as the development target, JUnit 3 light fixture tests, K2 mode. Gradle needs
`JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`.

**Spec:** `docs/superpowers/specs/2026-11-20-kotlin-android-support-design.md`, sections "Rules" and "Part A" (read
them; the spec is the authority). Part B (Android taps) gets its own plan after this one.

**Ruling (plan style):** as in the Python plan, steps give exact interfaces, ids, titles and test cases and leave the
PSI code to the implementer. Task 1 checks the Kotlin PSI entry points and the test fixture first and reports them;
later tasks build on that report.

## Global Constraints

- Nothing PHP, Java or Python users have today changes; every existing test passes without edits to what it asserts.
  In pure-Java projects Java canvases and flows behave exactly as before.
- Kotlin code lives only in `kotlin/src/main/kotlin/dev/codelanes/kotlin/` (package `dev.codelanes.kotlin`). The main
  module and the Java module never import `org.jetbrains.kotlin`.
- Content module `codelanes.kotlin`, descriptor `kotlin/src/main/resources/codelanes.kotlin.xml`, dependencies
  `<plugin id="org.jetbrains.kotlin"/>` and `<module name="codelanes.java"/>`, and in the same descriptor
  `<extensions defaultExtensionNs="org.jetbrains.kotlin"><supportsKotlinPluginMode supportsK2="true"/></extensions>`.
- **No Kotlin Analysis API** (`analyze {}`, `KaSession`, `resolveToCall`, `resolveToSymbol`, symbols) and no K1 APIs
  (`resolveToDescriptor`, `BindingContext`, descriptors). Resolve with `PsiElement.references` → `resolve()`; search
  with `ReferencesSearch` / `DefinitionsScopedSearch`. Avoid `org.jetbrains.kotlin.idea.*` helper classes
  (`mainReference` etc.); `org.jetbrains.kotlin.psi.*` and `org.jetbrains.kotlin.asJava.*` (light classes) are fine.
- No Kotlin stdlib API newer than 2.1.20.
- Ids: module `module:<package>.<FileName>`; class `class:<fqn>`; top-level function `method:<name>`; member
  `method:<Class>.<name>`; companion member `method:<Class>.Companion.<name>`; overloads append
  `(<Type>, <Type>)` (simple names as written); same-named extension functions `method:<Receiver>.<name>`; callee
  `callee:<owner fqn>::<short key>` — owner is the class fqn or the module fqn, short key is the key without the
  class prefix (`callee:acme.Repo::save`, `callee:acme.Repo::Companion.create`, `callee:acme.Audit::audit`,
  `callee:acme.Repo::save(Int)`); `parent:`, `interface:`, `dependency:`,
  `implementer:`, `implementation:` as in Java.
- Every IntelliJ class used by the Kotlin module must be visible to a content module in every supported IDE:
  `python3 scripts/check-module-classes.py`.
- Version `0.4.0` (becomes `0.5.0` if `feat/rust-support` merges first); `sinceBuild` stays `252`; no `untilBuild`.
- Commits: author `40357662+agitri@users.noreply.github.com`; message ends with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never push.
- Run tests with `./gradlew test` (never `test clean` in one invocation).

## Review Focus

1. A file with two top-level classes and nothing else — two CLASS centre blocks, no MODULE block, no crash in the
   layout (Task 2 test `testTwoClassesNoModule`).
2. A top-level function and a member with the same name (`fun save()` and `Repo.save`) — ids `method:save` and
   `method:Repo.save`, no collision (Task 2 test `testSameNameTopLevelAndMember`).
3. Calls that don't resolve (missing import, stdlib not attached) — no line, never an exception (Task 3 test
   `testUnresolvedCallsAreSkipped`).
4. A class without a body (`class Foo(val x: Int)`) and an `object` — `+ method` produces compiling code (Task 5 tests
   `testAddMethodToBodylessClass`, `testAddMethodToObject`).
5. A pure-Java project with the Kotlin plugin loaded — Java flows still claim Java classes and every Java test is
   unchanged; Kotlin classes are claimed by Kotlin only (Task 7 test `testJavaDoesNotClaimKotlinClasses`).

---

### Task 1: The `kotlin/` content module, K2 declaration, test base

**Files:**
- Create: `kotlin/build.gradle.kts`, `kotlin/src/main/resources/codelanes.kotlin.xml`,
  `kotlin/src/main/kotlin/dev/codelanes/kotlin/KotlinLanguageSupport.kt`
- Create: `src/test/kotlin/dev/codelanes/kotlin/KotlinTestCase.kt`, `src/test/kotlin/dev/codelanes/kotlin/KotlinFixtureTest.kt`
- Modify: `settings.gradle.kts`, `build.gradle.kts` (deps, test task), `src/main/resources/META-INF/plugin.xml` (`<content>`)

**Interfaces:**
- Produces: `KotlinLanguageSupport : LanguageSupport` with `handles` = extension `kt` and Kotlin file type (not `kts`);
  `unsupportedReason` = `"Kotlin scripts aren't supported yet"` for `.kts`, `"Library code"` for library files, else
  null; other members return empty / `BuildResult.Unsupported("Not built yet")` until later tasks.
- Produces: `abstract class KotlinTestCase : LightJavaCodeInsightFixtureTestCase` (or whatever the spike finds works
  with Kotlin + Java resolution) with `protected fun addFile(path: String, text: String): PsiFile` (trimIndent) and
  `protected fun open(path: String, text: String): PsiFile` (adds and configures in the editor). Kotlin stdlib
  attached or the needed declarations stubbed — the spike decides; record it.

- [ ] **Step 1:** Mirror `java/`: `kotlin/build.gradle.kts` like `java/build.gradle.kts` with
  `bundledPlugin("com.intellij.java")`, `bundledPlugin("org.jetbrains.kotlin")`, `compileOnly(project(":"))`,
  `compileOnly(project(":java"))`; `include(":kotlin")`; root: `bundledPlugin("org.jetbrains.kotlin")`,
  `pluginModule(project(":kotlin"))`, `testImplementation(project(":kotlin"))`, and
  `tasks.test { systemProperty("idea.kotlin.plugin.use.k2", "true") }`; `<module name="codelanes.kotlin"/>` in
  `plugin.xml`. Descriptor:

```xml
<!-- Content module: loaded only where the Kotlin plugin is (IntelliJ IDEA, Android Studio). -->
<idea-plugin package="dev.codelanes.kotlin">
    <dependencies>
        <plugin id="org.jetbrains.kotlin"/>
        <module name="codelanes.java"/>
    </dependencies>
    <!-- Without this, 2025.2–2026.1 refuse to load the module in K2 mode (the default). -->
    <extensions defaultExtensionNs="org.jetbrains.kotlin">
        <supportsKotlinPluginMode supportsK2="true"/>
    </extensions>
    <extensions defaultExtensionNs="dev.codelanes">
        <languageSupport implementation="dev.codelanes.kotlin.KotlinLanguageSupport"/>
    </extensions>
</idea-plugin>
```

- [ ] **Step 2: Write the failing fixture test** — everything later depends on these resolving in K2 test mode:

```kotlin
class KotlinFixtureTest : KotlinTestCase() {
    fun testACallResolvesIntoAnotherFile() // `repo.save(1)` in acme/Service.kt → KtNamedFunction `save` in class Repo (acme/Repo.kt)
    fun testACallInsideALambdaResolves()   // `run { repo.save(1) }` → same target
    fun testACallableReferenceResolves()   // `val f = repo::save` → same target
    fun testAKotlinCallResolvesToJava()    // `JavaRepo().find()` → PsiMethod `find` in acme/JavaRepo.java
    fun testImplementationsOfAKotlinInterfaceFunction() // DefinitionsScopedSearch on `interface Store { fun put() }` finds `class DbStore : Store { override fun put() {} }`
    fun testSupportHandlesKtAndRejectsScripts() // handles(a.kt) true; unsupportedReason(b.kts) == "Kotlin scripts aren't supported yet"
}
```

- [ ] **Step 3:** `./gradlew test --tests 'dev.codelanes.kotlin.*'` — FAIL, then make it pass. If resolution needs
  the stdlib, attach it (e.g. `KotlinWithJdkAndRuntimeLightProjectDescriptor` if visible, else a library jar from the
  bundled plugin's `kotlinc` dist, else stubs) and record the choice.
- [ ] **Step 4:** `./gradlew test` (all green, count printed), `./gradlew buildPlugin`,
  `python3 scripts/check-module-classes.py build/distributions/codelanes-0.3.0.zip <cached IDEA 2025.2> <cached IDEA 2026.2>` → `ok`.
- [ ] **Step 5:** Commit `feat(kotlin): kotlin content module, K2 declaration, test base`.
- [ ] **Report:** the fixture and stdlib setup, the exact PSI calls used for each resolve case, and whether the Kotlin
  module jar landed in `lib/modules/`.

### Task 2: `KotlinBlockBuilder` — header, module, classes, functions, ids, titles

**Files:**
- Create: `kotlin/src/main/kotlin/dev/codelanes/kotlin/KotlinBlockBuilder.kt`
- Modify: `KotlinLanguageSupport.kt` (`build` delegates)
- Test: `src/test/kotlin/dev/codelanes/kotlin/KotlinBlockBuilderStructureTest.kt`

**Interfaces:**
- Produces: `object KotlinBlockBuilder { fun build(file: KtFile, revealed: Set<String>, followedCalls: Set<String>, added: Set<String>): BuildResult; fun moduleFqn(file: KtFile): String; fun functionKey(function: KtNamedFunction): String; fun functionTitle(function: KtNamedFunction): String; fun classKeyword(cls: KtClassOrObject): String }`
  — `functionKey` returns the part after `method:` (`save`, `Repo.save`, `Repo.Companion.create`, `save(Int, String)`,
  `String.slug`). Same constants as Python (`MAX_IMPLEMENTERS = 10`, `MAX_CALLEES = 10`, `MAX_CHAIN_BLOCKS = 80`,
  `MAX_IMPLEMENTATIONS = 5`).

- [ ] **Step 1: Failing tests** (`model(path, text)` helper as in `PythonBlockBuilderStructureTest`):
  - `testAFileBecomesHeaderModuleClassAndFunctionBlocks`: `acme/notes/NoteScreen.kt` with `package acme.notes`, two
    imports, `const val MAX = 3`, `fun NoteScreen(id: Long) {}`, `class NoteViewModel(private val repo: Repo) { fun save() {} companion object { fun create() = 1 } }`
    → ids `header`, `module:acme.notes.NoteScreen`, `method:NoteScreen`, `class:acme.notes.NoteViewModel`,
    `method:NoteViewModel.save`, `method:NoteViewModel.Companion.create`; header title `package acme.notes · 2 imports`, collapsed.
  - `testOneClassFileHasNoModuleBlock`: `package acme` + `class Repo { fun save() {} }` → ids `header`,
    `class:acme.Repo`, `method:Repo.save`; no `module:` block. (Header exists when there is a package or an import.)
  - `testTwoClassesNoModule`: two classes, nothing else → two CLASS blocks, no MODULE; `LayoutEngine` lays it out
    without exceptions.
  - `testEmptyFileShowsModuleBlock`: `package acme` only → just `module:acme.Empty`.
  - `testSameNameTopLevelAndMember`: `fun save() {}` + `class Repo { fun save() {} }` → `method:save`, `method:Repo.save`.
  - `testOverloadsAndExtensions`: `fun save(x: Int)`, `fun save(x: Int, y: String)`, `fun String.slug()`,
    `fun Int.slug()` → `method:save(Int)`, `method:save(Int, String)`, `method:String.slug`, `method:Int.slug`.
  - `testTitles`: `class NoteViewModel(private val repo: Repo) : ViewModel()` →
    `class NoteViewModel(private val repo: Repo) : ViewModel()`; `object Store : Repo` → `object Store : Repo`;
    `suspend fun load(id: Long): Note` → `suspend fun load(id: Long): Note`; `@Composable fun Screen(state: UiState)` →
    `@Composable fun Screen(state: UiState)`; `fun String.slug(): String` → same; `data class`, `sealed interface`,
    `enum class`, `value class`, `annotation class` keywords.
  - `testRanges`: KDoc and annotations inside each block's range; the CLASS block excludes its member functions'
    ranges; the MODULE block excludes header, classes and functions; nested classes, local functions, properties,
    `init` blocks and secondary constructors stay in the class block.
- [ ] **Step 2:** Run — FAIL. **Step 3:** Implement. **Step 4:** Run — PASS; all PHP/Java/Python tests unchanged.
- [ ] **Step 5:** Commit `feat(kotlin): file, class and function blocks`.

### Task 3: Lanes and lines — parents, interfaces, dependencies, overrides, calls

**Files:**
- Modify: `KotlinBlockBuilder.kt`
- Modify: `java/src/main/kotlin/dev/codelanes/java/JavaBlockBuilder.kt` — make `externalBlock`, `calleeBlock`,
  `isSourceless` public (no behaviour change) so Kotlin can build blocks for Java targets.
- Test: `src/test/kotlin/dev/codelanes/kotlin/KotlinBlockBuilderLanesTest.kt`

**Interfaces:**
- Consumes: `JavaBlockBuilder.externalBlock(kind: BlockKind, target: PsiClass): Block`,
  `JavaBlockBuilder.calleeBlock(id: String, method: PsiMethod): Block`, `JavaBlockBuilder.fqn`, `methodKey`.
- Produces: `KotlinBlockBuilder.injectedTypes(cls: KtClassOrObject): List<PsiElement>` (KtClassOrObject or PsiClass,
  in source order, deduplicated) — the Android plan reuses it.

- [ ] **Step 1: Failing tests:**
  - `testSuperclassAndInterfaces`: `class A : Base(), Foo, Bar` with project `open class Base`, `interface Foo`,
    `interface Bar` → `parent:acme.Base` (EXTENDS), `interface:acme.Foo`, `interface:acme.Bar` (IMPLEMENTS); a library
    supertype (stubbed jar class or `java.io.Serializable`) shows `sourceless`.
  - `testJavaSuperclass`: `class A : JavaBase()` with `acme/JavaBase.java` → `parent:acme.JavaBase` with Java's range.
  - `testDependencies`: primary-ctor params `(private val repo: Repo, store: Store?)`, a secondary ctor param `Log`,
    `@Inject lateinit var bus: Bus`, `@Autowired lateinit var mailer: Mailer`, `private val vm: NoteViewModel by viewModels()`,
    `val koin by inject<Clock>()` → `dependency:` for Repo, Store, Log, Bus, Mailer, NoteViewModel, Clock, in that order;
    `String`, `Int`, `List<Repo>` (counts as List → left out) excluded; duplicates once.
  - `testCallsBetweenOwnFunctions`: `fun a() { b(); run { c() }; val f = ::d }` → CALLS `method:a`→`b`,`c`,`d`.
  - `testConstructorCallLinksToClass`: top-level `fun make() = Repo()` with `class Repo` in the same file → CALLS to
    `class:acme.Repo`.
  - `testOverrides`: `open class Base { open fun f() {} }`, `class A : Base() { override fun f() {} }` on the same
    canvas → OVERRIDES `method:A.f` → `parent:acme.Base` with targetRange on `Base.f`; works for interface functions
    and for a Java base method.
  - `testUnresolvedCallsAreSkipped`: `fun a() { missing(); x.y() }` → no link, no exception.
- [ ] **Steps 2–4:** FAIL, implement, PASS (Java tests unchanged).
- [ ] **Step 5:** Commit `feat(kotlin): parents, dependencies, overrides and calls`.

### Task 4: `+ calls`, `+ parents`, implementers

**Files:**
- Modify: `KotlinBlockBuilder.kt`
- Test: `src/test/kotlin/dev/codelanes/kotlin/KotlinBlockBuilderCallsTest.kt`

- [ ] **Step 1: Failing tests** (mirror `PythonBlockBuilderCallsTest`'s cases):
  - `testFollowedCallsBecomeCallees`: `followedCalls = {"method:Service.place"}`, `place` calls `repo.save()` (class in
    another file) and top-level `audit()` (in `acme/Audit.kt`) → `callee:acme.Repo::save` and `callee:acme.Audit::audit`
    (CALLS_INTO from `method:Service.place`). `blockIdOf` (Task 5) and `calleeIdFor` (Task 8) produce the same strings.
  - `testCallToJavaBecomesJavaCallee`: a call to `JavaRepo.find()` → `callee:acme.JavaRepo::find` with Java's range.
  - `testInterfaceCallShowsImplementations`: calling `store.put()` on `interface Store` with `DbStore`, `MemStore` →
    CALLEE `callee:acme.Store::put` plus `callee:acme.DbStore::put`, `callee:acme.MemStore::put`, IMPLEMENTED_BY; at most 5.
  - `testLibraryCallsAreNotCallees`: `listOf(1).map {}` → no callee.
  - `testImplementersOfAnInterface`: canvas of `interface Store` → IMPLEMENTER blocks for project implementers (max 10,
    then `more:implementers`); also for `abstract class` and `sealed class` (subclasses).
  - `testPlusParentsDigsDeeper`: `revealed = {"parent:acme.Base"}` shows Base's own parents.
  - `testChainCap`: more than 80 chain blocks → `more:chain`.
- [ ] **Steps 2–4:** FAIL, implement, PASS. **Step 5:** Commit `feat(kotlin): + calls, + parents and implementers`.

### Task 5: Session hooks — `+ method`, delete, flow ids, Add method…

**Files:**
- Modify: `KotlinLanguageSupport.kt` (`methodInsertion`, `newMethodText`, `blockIdOf`, `methods`)
- Create: `kotlin/src/main/kotlin/dev/codelanes/kotlin/KotlinMethodFinder.kt`
- Test: `src/test/kotlin/dev/codelanes/kotlin/KotlinSessionTest.kt` (drive `BlocksSession` as `PythonSessionTest` does)

**Interfaces:**
- Produces: `KotlinMethodFinder.find(project: Project): List<MethodRef>` —
  `MethodRef("<Owner short>::<title>  <package>", <owner fqn>, <key>)`, sorted by label; owner = class fqn or module fqn.
- Produces: `KotlinLanguageSupport.blockIdOf(target, openFqn)`: class/constructor target in the open file
  `class:<fqn>`, elsewhere `callee:<fqn>::class`; function in the open file `method:<key>`, elsewhere
  `callee:<owner fqn>::<short key>` (Global Constraints).

- [ ] **Step 1: Failing tests:**
  - `testAddMethodToModule`: on `fun a() {}\n` → appends `\nfun newFunction() {\n}\n`; block `method:newFunction`,
    name selected; second press → `newFunction2`.
  - `testAddMethodToClass`: `class C {\n    fun a() {}\n}\n` → inserts `\n    fun newMethod() {\n    }\n` before the
    closing brace → `method:C.newMethod`.
  - `testAddMethodToBodylessClass`: `class C(val x: Int)\n` → `class C(val x: Int) {\n    fun newMethod() {\n    }\n}\n`.
  - `testAddMethodToObject` and `testAddMethodToInterface` (`fun newMethod()` without a body).
  - `testDeleteRemovesExactlyItsLines`: delete `method:C.a`.
  - `testFlowIdsViaHook`: via `FlowResolver.idOf`, ids as in the Interfaces block.
  - `testAddMethodListsProjectFunctions`: functions and members of project `.kt` files, no library ones.
- [ ] **Steps 2–4:** FAIL, implement, PASS (PHP/Java/Python session tests unchanged).
- [ ] **Step 5:** Commit `feat(kotlin): + method, delete, flow ids and Add method…`.

### Task 6: Spring routes written in Kotlin

**Files:**
- Modify: `java/src/main/kotlin/dev/codelanes/java/JavaRouteFinder.kt` — extract
  `fun routesIn(cls: PsiClass): List<Pair<String /* full path */, Pair<String /* label */, PsiMethod>>>` from `find`
  (same rules, same labels); `find` keeps its exact output.
- Create: `kotlin/src/main/kotlin/dev/codelanes/kotlin/KotlinRouteFinder.kt`
- Test: `src/test/kotlin/dev/codelanes/kotlin/KotlinRouteFinderTest.kt`

**Interfaces:**
- Produces: `KotlinRouteFinder.find(project: Project): List<EntryPoint>` — candidate `.kt` files via the same
  `processAllFilesWithWord` scan, each `KtClass` → its light class (`toLightClass()`), `JavaRouteFinder.routesIn`,
  `EntryPoint(label, kt file path, KotlinBlockBuilder.functionKey(the KtNamedFunction behind the PsiMethod))`.

- [ ] **Step 1: Failing tests:** `@RestController @RequestMapping("/notes") class NoteController { @GetMapping("/{id}") fun get(...) ; @PostMapping fun create(...) }`
  → `GET /notes/{id} → NoteController::get` with methodName `NoteController.get`, and `POST /notes → NoteController::create`;
  a Kotlin `interface` with mappings is skipped; Java route tests unchanged.
- [ ] **Steps 2–4:** FAIL, implement, PASS. **Step 5:** Commit `feat(kotlin): Spring routes in Kotlin files`.

### Task 7: AI flows for Kotlin, and hops between Java and Kotlin

**Files:**
- Modify: `src/main/kotlin/dev/codelanes/lang/LanguageSupport.kt` — add
  `/** Supports in one family resolve to the same PSI and prove each other's hops ("jvm": Java, Kotlin). */ val proofFamily: String? get() = null`
- Modify: `src/main/kotlin/dev/codelanes/flow/FlowResolver.kt:54` — proven when
  `(from.support === to.support || (from.support.proofFamily != null && from.support.proofFamily == to.support.proofFamily)) && from.support.proves(hop.kind, from, to)`
- Modify: `java/.../JavaLanguageSupport.kt` — `proofFamily = "jvm"`. `java/.../JavaFlowLookup.kt` — `find` returns
  null when the found class's `navigationElement` isn't Java (a Kotlin light class), so Kotlin claims it; `proves`
  compares methods and classes by `navigationElement`, so a Java call to a Kotlin light method matches a Kotlin
  `methodElement`. (The spec's `canProveWith` hook is realised as `proofFamily`.)
- Create: `kotlin/src/main/kotlin/dev/codelanes/kotlin/KotlinFlowLookup.kt`
- Modify: the flow MCP tool's description in `src/main/kotlin/dev/codelanes/mcp/` (one sentence on Kotlin references)
- Test: `src/test/kotlin/dev/codelanes/kotlin/KotlinFlowLookupTest.kt`

**Interfaces:**
- Produces: `KotlinFlowLookup.find(project, reference, support): FlowLookup?` and `proves(kind, from, to)`.
  References: `acme.NoteViewModel::save`; `acme.notes.NoteScreen::NoteScreen` (module fqn + top-level function);
  `acme.Repo` and `acme.Repo::<init>` → class target. A class and a file with the same fqn: the class wins if it
  declares the name. Returns null when the reference has `\` or no `.kt` declaration matches and the project has no
  `.kt` files; `Missing(..., ownerFound = true)` when the owner exists but the member doesn't.
- `proves`: `calls`/`dispatches`/`middleware` — the `from` function body (lambdas included) calls `to` (KtNamedFunction
  or PsiMethod, compared by `navigationElement`) or constructs its class; `binds` — `to` overrides `from`; `listens`
  — `to`'s first parameter type is the `from` class; `routes` — false.

- [ ] **Step 1: Failing tests:** each reference shape found; missing member reason; each proven hop kind;
  `testHopFromKotlinToJavaIsProven` (Kotlin controller → Java service method); `testHopFromJavaToKotlinIsProven`
  (Java service → Kotlin repository); `testJavaDoesNotClaimKotlinClasses` (Java `find` on a Kotlin fqn → null);
  PHP→Java hops still claimed (existing test unchanged).
- [ ] **Steps 2–4:** FAIL, implement, PASS. **Step 5:** Commit `feat(kotlin): AI flows and Java↔Kotlin hops`.

### Task 8: Java canvases show Kotlin targets as Kotlin

**Files:**
- Modify: `LanguageSupport.kt` — add
  `fun externalBlockFor(element: PsiElement, kind: BlockKind): Block? = null` (a block for a declaration this support
  owns, seen from another language's canvas: id, title, range and summary in this language's terms) and
  `fun calleeIdFor(element: PsiElement): String? = null`.
- Modify: `JavaBlockBuilder.kt` — in `externalBlock` and `calleeBlock` (and where callee ids are made, lines ~155–187
  and ~232), first ask `LanguageSupports.all()` for a non-Java support whose `externalBlockFor` / `calleeIdFor`
  answers for the target's `navigationElement`; else today's code.
- Modify: `KotlinLanguageSupport.kt` — implement both for `KtClassOrObject` / `KtNamedFunction` navigation elements
  (light classes of top-level functions, i.e. JVM facades like `NoteScreenKt`, map to `callee:<module fqn>::<name>`).
- Test: `src/test/kotlin/dev/codelanes/kotlin/JavaCanvasWithKotlinTest.kt`

- [ ] **Step 1: Failing tests:** Java class `Service` extends Kotlin `open class Base` → `parent:acme.Base` with the
  Kotlin source range (block text starts with `open class Base`); Java injects a Kotlin `Repo` → `dependency:acme.Repo`
  with Kotlin range; `followedCalls` on a Java method calling `repo.save()` (Kotlin member) →
  `callee:acme.Repo::save` with Kotlin range; calling `NoteScreenKt.format()` → `callee:acme.NoteScreen::format`;
  every existing Java test unchanged.
- [ ] **Steps 2–4:** FAIL, implement, PASS. **Step 5:** Commit `feat(kotlin): Java canvases show Kotlin code`.

### Task 9: Product — Android Studio, docs, playground, verification, version

**Files:**
- Modify: `build.gradle.kts` (version, `AndroidStudio` verify family, `runAndroidStudio`), `.github/workflows/ci.yml`
  (one more verify job, as the PyCharm one), `README.md`, `src/main/resources/META-INF/plugin.xml` (description),
  `CHANGELOG.md`, `docs/roadmap.md`
- Create: `playground/java-spring/src/main/kotlin/…` — one Kotlin `@RestController` calling the existing Java service,
  and one Kotlin repository the Java code calls (the mixed case, for the manual check)

- [ ] **Step 1:** `pluginVerification`: family `AndroidStudio` with
  `create(IntelliJPlatformType.AndroidStudio, "<Otter 2025.2.x>")`, Panda 2025.3.x, Quail 2026.1.x, Rabbit 2026.2.x —
  exact strings from `./gradlew printProductsReleases`; default families now `PhpStorm, IntelliJ, PyCharm, AndroidStudio`.
  `register("runAndroidStudio") { type = IntelliJPlatformType.AndroidStudio; version = "<latest Rabbit>" }`.
- [ ] **Step 2:** In the Android Studio sandbox, check and note in the report: Java plugin bundled (Java files open
  as canvases)? `com.intellij.mcpServer` installable? Kotlin module loaded in K2 mode (open a `.kt` file)?
- [ ] **Step 3:** Docs: README languages table gets "Kotlin | IntelliJ IDEA, Android Studio | Spring (routes)";
  requirements and install lines mention Android Studio Otter (2025.2) or newer; drop "other JetBrains IDEs aren't
  supported yet" for Android Studio; Marketplace description likewise; CHANGELOG `## 0.4.0` entry; roadmap: Kotlin
  done in 0.4.0, Part B next. Version `0.4.0`.
- [ ] **Step 4: Verify:** `./gradlew test` green; `./gradlew verifyPlugin` green on all families (no
  `UndeclaredKotlinK2CompatibilityModeWarning`); `check-module-classes.py` on the zip against cached IDEA 2025.2,
  2026.2 and Android Studio Otter and Rabbit → `ok`.
- [ ] **Step 5: Manual:** `./gradlew runIde` with `playground/java-spring` — open the Kotlin controller (canvas,
  `+ calls` into Java), open the Java service (Kotlin repository shows Kotlin source), Follow a Request lists the Kotlin
  route. `./gradlew runAndroidStudio` — a `.kt` file opens as a canvas. Report what you saw.
- [ ] **Step 6:** Commit `chore(kotlin): Android Studio verification, docs, version 0.4.0`.
