# Kotlin and Android support — design

Date: 2026-11-20. Status: direction approved in chat ("ok lets go"); this spec writes it out for review. Branch
`feat/kotlin-support`, from `main` (independent of the unmerged `feat/rust-support`).

## Why

On 2026-11-20 the Marketplace showed Android Studio as the most-downloaded named IDE (about 17, ahead of PyCharm 8,
IntelliJ IDEA 6, PhpStorm 6). Nobody advertised the plugin there: it installs because `plugin.xml` only depends on the
platform. Those users mostly write Kotlin, and CodeLanes shows nothing for a `.kt` file. This project makes the plugin
useful to them.

The product owner's direction: **in an Android app, UI actions (taps, submits, CRUD buttons) play the role routes play
in the web frameworks** — they are the entry points a reader starts from.

## Two parts, built in order

- **Part A — Kotlin canvas.** Any `.kt` file opens as a canvas, in IntelliJ IDEA and Android Studio. Also benefits
  Spring-on-Kotlin users in IDEA. Shippable on its own.
- **Part B — Android entry points.** Compose taps, navigation destinations and deep links in Follow a Request.

One spec, two implementation plans (A first). Part B depends on A.

## Research behind the decisions (2026-11-20)

- Google declared Android UI "Compose First" at I/O 2026 (2026-05-19): XML Views, Fragments, RecyclerView are in
  maintenance mode. 68%+ of the top 1,000 apps use Compose (Google, 2026-07). → Compose only in this project.
- Google's recommended architecture: UI layer → screen-level ViewModel (receives UI actions as method calls) →
  repositories (a domain/UseCase layer only in big apps). Hilt for DI. Navigation 3 stable since 2025-11; most existing
  apps still on Navigation 2. MVVM most common, MVI (`onEvent(Event)`) growing.
- Android Studio Otter (2025.2.x, build 252) and later run on IntelliJ 2025.2+, so `sinceBuild = 252` already covers
  them. K2 Kotlin mode is the default in Android Studio and IDEA.
- The Kotlin Analysis API's call-resolution entry points are being replaced during 2026 and the replacements are
  experimental and not binary-compatible back to 252. Plain Kotlin PSI plus platform references (`PsiReference.resolve()`)
  and `DefinitionsScopedSearch` work in K1 and K2 across 252–262.

## Rules

- Nothing PHP, Java or Python users have today changes. All current tests stay green without edits to what they
  assert; existing block ids stay the same.
- The canvas, review, notes, working sets, flows and MCP tools stay language-neutral.
- Kotlin lives in its own content module (`kotlin/`, `codelanes.kotlin`) depending on `org.jetbrains.kotlin` (which
  itself requires `com.intellij.java`). It loads in IntelliJ IDEA and Android Studio, never elsewhere.
- **No Kotlin Analysis API** (`analyze { }`, `KaSession`, `resolveToCall`, symbols) in this project. Resolution uses
  Kotlin PSI, `PsiElement.references` / `PsiReference.resolve()`, and platform searches only. If a single case truly
  needs an inferred type, it may use `analyze { expression.expressionType }` (stable, no opt-in) behind one small class,
  and the plan must call it out.
- No K1-only APIs (`resolveToDescriptor`, `BindingContext`, descriptors).
- No Kotlin stdlib API newer than 2.1.20 (the stdlib bundled in 2025.2); the verifier enforces this.
- Every IntelliJ class the module uses must be visible to a content module in every supported version:
  `scripts/check-module-classes.py`, as for Python.

## Part A — Kotlin canvas

### Which files open

Every `.kt` file in the project's own content, except: files in libraries ("Library code"); `.kts` scripts (left out:
"Kotlin scripts aren't supported yet"). An empty file opens with just its file block. Files under `build/`,
generated sources and test sources open normally (tests are code people read).

### Layout: the module canvas (as Python)

Kotlin files often hold several classes plus top-level functions (Compose screens are mostly top-level `@Composable`
functions). So Kotlin uses Python's module layout: several centre blocks, already supported by `LayoutEngine`.

| Kotlin | Block | Id |
|---|---|---|
| `package` and `import`s | HEADER, collapsed, `package com.acme.notes · 12 imports` | `header` |
| the file: file annotations, KDoc, and every top-level declaration that isn't a class, object, interface or function (top-level properties, `typealias`es, `const val`s) | MODULE, centre | `module:<package>.<FileName>` (file name without `.kt`, e.g. `com.acme.notes.NoteEditScreen`) |
| each top-level `class`, `interface`, `object`, `enum class`, `data class`, `sealed class/interface`, `annotation class`, `value class` | CLASS, centre, source order | `class:<fqn>` |
| each top-level function (incl. extension functions and `@Composable` functions) | METHOD, right, OWNS from the module | `method:<name>` |
| each member function of a class, and of its `companion object` | METHOD, right, OWNS from its class | `method:<Class>.<name>`; companion members `method:<Class>.Companion.<name>` |
| superclass and implemented interfaces | PARENT / INTERFACE, left, EXTENDS / IMPLEMENTS | as in Java (`parent:<fqn>`, `interface:<fqn>`) |
| injected types (below) | DEPENDENCY, left, INJECTS from the class | `dependency:<fqn>` |
| for an interface, abstract or sealed class: project implementers / subclasses | IMPLEMENTER + IMPLEMENTATION | as in Java |

- If the file has nothing for the MODULE block (no top-level functions, properties, typealiases or file annotations
  — the common "one class per file" case), there is no MODULE block and the canvas looks like Java's: one class in
  the centre. "+ method" on the class adds a member. *(My call: an empty file block above every ViewModel is noise.)*
  A file with no declarations at all shows just its MODULE block.
- Overloads: `method:<name>(<Type>, <Type>)` with simple type names as written, only when the name is overloaded in
  the same owner — Java's rule. Extension functions with the same name on different receivers count as overloads and
  get the receiver in front: `method:String.slug`. *(My call.)*
- Nested and local classes, local functions and lambdas stay inside their parent block. Member properties (with their
  getters/setters) and `init` blocks stay in the CLASS block, as Java fields do. Primary constructors are part of the
  class signature; secondary constructors stay in the class block, as Java constructors do.
- Titles: `class NoteEditViewModel(…) : ViewModel()`, `object NoteRepositoryImpl : NoteRepository`,
  `fun save()`, `suspend fun load(id: Long): Note`, `@Composable fun NoteEditScreen(state: UiState, …)`,
  `fun String.slug(): String`; parameters as written (shortened with `…` past the existing title length limit).
- KDoc and annotations are part of each block's range, as in Java.

### Injected types (the dependency lane)

The types of:

1. primary-constructor parameters (with or without `val`/`var`), and secondary-constructor parameters;
2. properties annotated `@Inject` (Hilt/Dagger field injection, `lateinit var`) or `@Autowired`;
3. properties delegated to `by inject()` / `by viewModel()` (Koin) or `by viewModels()` / `by activityViewModels()`
   (AndroidX): the type argument if written, else the declared type.

Only types that resolve to a class in the project's own code. Nullable types (`Foo?`) count as `Foo`; a generic type
counts as its own class, as in Java. Duplicates dropped. Annotations match by qualified name when they resolve, else by
short name.

### Lines

- **calls**: inside a function, every `KtCallExpression` and `KtCallableReferenceExpression` (`vm::save`) whose callee
  resolves (`references` → `resolve()`) to a function of this file. Calls inside lambdas count (so
  `Button(onClick = { vm.save() })` and `viewModelScope.launch { repo.load() }` produce lines). A resolved target may be
  a `KtNamedFunction`, a `KtPrimaryConstructor`/`KtClass` (constructor call → the class), or a `PsiMethod` (a Java
  method: Kotlin → Java calls work).
- **overrides**: a function marked `override` links to each related type on the canvas that declares the function it
  overrides (resolve via the platform super-method search on the function's light method, or by name and parameter
  count on the supertypes' PSI — builder's call, as long as it works in K1 and K2).
- **+ calls**: calls into other project files become CALLEE blocks, as in Java and Python. A call to an interface or
  abstract function also shows its project implementations, via `DefinitionsScopedSearch` (which the K2 Kotlin plugin
  supports), linked IMPLEMENTED_BY.
- **+ parents** digs through supertypes, as in Java.
- Library and stdlib targets are never pulled in as callees.
- Types without source (library supertypes such as `ViewModel`, `RoomDatabase`) show as sourceless blocks, as in Java.

### Session behaviour

- **+ method** on the MODULE block appends `fun newFunction() {\n}` at the end of the file; on a CLASS block, a
  member `fun newMethod() {\n}` before the class's closing brace (the existing brace rule). A class without a body
  (`class Foo(val x: Int)`) gets ` {\n}` added. An interface gets `fun newMethod()` without a body.
- Delete, Remove from canvas, review marks, notes, pins and working sets work unchanged with the ids above.
- **Add method…** lists every function in the project's own `.kt` files.

### Follow a Request (Part A)

Spring routes written in Kotlin (`@RestController` classes in `.kt` files) appear in Follow a Request with the same
labels as Java routes. Reuse `JavaRouteFinder`'s annotation rules; read them from Kotlin PSI. *(My call — small, and
the Kotlin-on-Spring users in IDEA get it for free.)* If it turns out costly, it can move to a follow-up; say so in the
plan.

### AI flows for Kotlin

- References: a member function `com.acme.notes.NoteEditViewModel::save`; a top-level function
  `com.acme.notes.NoteEditScreen::NoteEditScreen` (package + file name, the same name as the MODULE id; not the JVM
  facade name `NoteEditScreenKt`); a class `com.acme.notes.NoteRepository`; a constructor `…::<init>` → the class
  block. When a class and a file share a name (`Note.kt` holding `class Note` and a top-level `fun Note.slug()`), the
  class wins for `Fqn::name` lookups if it declares `name`, else the file's top-level function.
- Proven hops as Java: `calls` (the `from` function calls `to` or constructs its class, including inside lambdas),
  `binds` (`to` overrides `from`), `dispatches`/`middleware` as `calls`, `routes` never.
- Both Java and Kotlin supports answer `findFlowTarget`; a dotted `Fqn::name` that resolves to a `.kt` declaration is
  Kotlin's. The builder must make sure the Java support doesn't claim Kotlin classes through light classes (or that
  the first match is fine either way).
- The MCP tool description gets one sentence on Kotlin references.

### Mixed Java and Kotlin projects

In scope: Spring-on-Kotlin calling Java services and Android apps with Java left over are the everyday case.
*(My call; the product owner delegates Java/JVM decisions.)* The "nothing changes for Java users" rule still holds for
pure-Java projects; behaviour only changes where a Kotlin declaration is involved.

- **Kotlin canvas → Java:** calls, parents, dependencies and implementations that resolve to Java `PsiClass` /
  `PsiMethod` become blocks with Java's ids and ranges (reuse the Java builder's external-block logic through a small
  shared helper, or duplicate it — builder's call).
- **Java canvas → Kotlin:** today `JavaBlockBuilder` sees Kotlin declarations as light classes. Change: whenever a
  target's `navigationElement` is Kotlin PSI (`KtClassOrObject`, `KtNamedFunction`), the block's range comes from that
  element (so the slice shows real Kotlin source), and a call to a top-level function through its JVM facade
  (`NoteEditScreenKt.foo()`) gets the Kotlin id `callee:com.acme.notes.NoteEditScreen::foo`. The Java module must not
  import Kotlin PSI (it loads without the Kotlin plugin): it asks `LanguageSupports` for a support that claims the
  element (new `LanguageSupport` hook `blockFor(element: PsiElement): ExternalBlock?`, default null) and falls back to
  today's behaviour.
- **Flows:** `FlowResolver` proves a hop only when both ends share a support. Change: a new hook (built as `proofFamily = "jvm"`, see the plan)
  `LanguageSupport.canProveWith(other: LanguageSupport): Boolean` (default `this === other`); Java and Kotlin return
  true for each other, and the `from` target's support does the proving on PSI (both resolve to the same JVM elements,
  so a Kotlin call to a Java method is proven by the Kotlin support, and vice versa). PHP and Python are unaffected.
- Tests: a mixed fixture (Kotlin controller → Java service → Kotlin repository) for canvases in both directions and
  for a flow whose hops cross languages.

## Part B — Android entry points

All of this is Kotlin PSI over Compose source; it needs no Android plugin APIs. It only runs when the project has
Compose: some file imports `androidx.compose.runtime.Composable`.

### Taps (UI actions) in Follow a Request

A **tap** is an argument for a parameter named `onClick`, `onLongClick`, `onValueChange`, `onCheckedChange`,
`onDismissRequest` or `onConfirm` of a library (non-project) function, or the trailing lambda of `Modifier.clickable` /
`Modifier.combinedClickable` / `Modifier.toggleable` / `Modifier.selectable`, or an argument of `KeyboardActions(onDone
= …, onSend = …, onSearch = …, onGo = …)`, inside a `@Composable` function in project code. *(The exact list is my
call from the research; the builder may extend it with other `on[A-Z]…` parameters of Material components if cheap.)*

All three argument shapes count *(my call — Google's stateless-screen pattern relies on the third)*:

- a lambda: `onClick = { vm.save() }`;
- a callable reference: `onClick = vm::save`;
- a bare name of a function-typed parameter or local: `onClick = onSaveClick` (goes straight to hoisting, step 2).

Named, positional and trailing-lambda arguments all count. The parameter name comes from the written name when there
is one, else from the callee's parameter list resolved through PSI references (`Button({ … }) { … }` → `onClick`).
So the Compose stubs in fixtures and the playground must declare real parameter lists for the components they use.

**Where a tap lands — the ViewModel method.** As a route lands in its controller method, a tap lands in the first
method of a class extending `androidx.lifecycle.ViewModel` (directly or indirectly; match by name if unresolved) that
the action reaches:

1. Inside the lambda (or the callable reference), take the calls in order. A call that resolves to a ViewModel method
   → that method; done.
2. **Hoisting.** If the lambda only calls (or the argument names) a function-typed parameter of the enclosing
   composable (`onSaveClick()`), find the callers of that composable in project code (`ReferencesSearch`) and look at
   the argument each passes for that parameter (by name, or by position); go to step 1 with it. At most **4 hops**;
   each caller that reaches a ViewModel method gives its own entry (a component used from two screens gives two taps).
3. **MVI events.** If the reached ViewModel method has a single parameter whose type is a sealed class/interface, and
   the call passes a specific subtype (`onEvent(NoteEvent.Delete)`, `onEvent(NoteEvent.Open(id))`), the entry still
   lands on that method (`onEvent`) — and its label names the event. *(My call: landing on the `when` branch would
   need a block per branch, which the canvas doesn't have. The followed chain from `onEvent` shows all branches'
   calls.)*
4. A tap that reaches no ViewModel method within the hops (navigation-only lambdas like `onBack`, local state changes)
   is not listed. *(Navigation actions appear through destinations below.)*

**Label:** `Tap "Save" · NoteEditScreen → NoteEditViewModel::save`

- `"Save"`: the first string literal passed to a `Text(...)` call inside the component's content lambda; else its
  `contentDescription` string literal; else `stringResource(R.string.save_note)` → `save_note`; else the parameter
  name (`onClick`). Verb word by kind: `Tap` (click/long click/clickable/selectable/toggleable), `Edit` (onValueChange),
  `Toggle` (onCheckedChange), `Submit` (KeyboardActions), `Dismiss`/`Confirm` (dialogs).
- `NoteEditScreen`: the screen. Rule *(my call)*: the composable where the ViewModel call was found (the far end of
  the hoisting chain), with a trailing `Route` replaced by `Screen` when a composable of that name exists in the same
  file or package (`NoteEditRoute` → `NoteEditScreen`, Now-in-Android style); otherwise the name as is. A component
  used from two screens therefore gives two differently labelled taps. With no hoisting, the screen is the composable
  holding the component.
- MVI: `Tap "Delete" · NoteEditScreen → NoteEditViewModel::onEvent(Delete)`.
- Sorted by screen, then by source order.

**What picking a tap does:** opens the ViewModel's file and follows `method:<Class>.<name>` three levels deep, exactly
like a route (`FollowRequestAction.follow`). `EntryPoint` stays as is (`label`, `filePath`, `methodName`), with
`methodName` = the method key `NoteEditViewModel.save`.

### Navigation destinations and deep links

Shown in Follow a Request as their own entries, landing on the screen composable they show (open its file, follow
`method:<ScreenName>`):

- Navigation 3: `entry<NoteEdit> { … }` → `Screen NoteEdit → NoteEditScreen` (the first `@Composable` project function
  called in the lambda).
- Navigation 2: `composable("notes/{id}") { … }` → `Screen notes/{id} → NoteEditScreen`; type-safe
  `composable<NoteEdit> { … }` → `Screen NoteEdit → …`. `navigation(...)` nesting is not resolved into prefixes.
- Deep links: `navDeepLink { uriPattern = "https://acme.com/notes/{id}" }` (Nav2) attached to a destination →
  `Link https://acme.com/notes/{id} → NoteEditScreen`. Manifest `<intent-filter>` deep links are out of scope (they
  point at the Activity, not a screen).

### Out of scope for Part B (later)

XML Views (`setOnClickListener`, `android:onClick`, View/DataBinding), Fragments and Activity lifecycle callbacks,
WorkManager workers, BroadcastReceivers, Services and push messages, manifest deep links, Koin/Hilt binding resolution
beyond what `DefinitionsScopedSearch` finds, Kotlin Multiplatform source-set specifics, lambdas stored in variables or
`remember {}` before being passed, callback holder classes (`actions: NoteActions`).

## Product

- Version: the next minor (0.4.0, or 0.5.0 if Rust ships first). Part A may ship before Part B.
- README and Marketplace description: add "Kotlin (IntelliJ IDEA, Android Studio)" and, with Part B, "Jetpack Compose
  taps and navigation in Follow a Request". Drop the "other JetBrains IDEs aren't supported yet" wording for Android
  Studio; Java works there too.
- `verifyPlugin`: a new family `AndroidStudio` with one Android Studio per platform line we support (2025.2.x Otter,
  2025.3.x Panda, 2026.1.x Quail, 2026.2.x Rabbit), plus the existing IntelliJ IDEA ones (which now also check the
  Kotlin module). CI gets one more job.
- `./gradlew runAndroidStudio` starts a sandbox Android Studio.
- `playground/android/`: a tiny Compose notes app (list screen, edit screen with Save/Delete buttons, one MVI-style
  screen, one hoisted button component, a ViewModel, a repository interface + implementation, Nav2 and Nav3
  destinations, one deep link). Like the other playgrounds it needs no build: Compose/AndroidX/Hilt names are stubbed
  in a small `stubs/` source folder so the IDE resolves them without Gradle sync.
- Check in the sandbox, and note in README: whether Java and the MCP server plugin are available in Android Studio
  (AI flows need MCP; the canvas doesn't).

## Build

- Gradle submodule `kotlin/`, content module `codelanes.kotlin`, wired like `:java`, with
  `bundledPlugin("org.jetbrains.kotlin")` and `bundledPlugin("com.intellij.java")`.
- `codelanes.kotlin.xml`: `<dependencies><plugin id="org.jetbrains.kotlin"/></dependencies>` **and**
  `<extensions defaultExtensionNs="org.jetbrains.kotlin"><supportsKotlinPluginMode supportsK2="true"/></extensions>`
  in the module descriptor itself. Without it, 2025.2–2026.1 silently refuse to load the module in K2 mode.
- Root project: `pluginModule(project(":kotlin"))`; tests get the Kotlin plugin and run in K2 mode
  (`idea.kotlin.plugin.use.k2=true` on the test task).
- Tests need the Kotlin stdlib attached to the light test project (or stubbed declarations) — builder's call.

## Testing

- Every existing test passes unchanged.
- Kotlin fixture tests: which files open; every block kind and id above (top-level functions, classes, objects,
  companions, extension functions, overloads); titles; ranges; dependencies (constructor params, `@Inject lateinit`,
  delegates); calls including inside lambdas and callable references; Kotlin → Java calls; overrides; `+ calls` with
  interface implementations; `+ method` on module, class, body-less class, interface; Spring Kotlin routes; flow
  references and proven hops.
- Part B fixture tests: each tap kind and label rule; hoisting over 1, 2 and 4 hops and the 5th-hop cut-off; a
  component used from two screens; MVI `onEvent(Event.X)` labels; taps that reach no ViewModel are left out; Nav2
  string and type-safe destinations, Nav3 entries, deep links.
- `verifyPlugin` green on all families, `check-module-classes.py` clean for IDEA and Android Studio.
- Manual: `runAndroidStudio` with `playground/android`; `runIde` with a Kotlin file.

## Left to the builder

Internal structure of the builder and finders, the exact Android Studio version strings, how test fixtures get the
Kotlin stdlib and Compose stubs, how overrides are resolved (see Lines), and extending the tap parameter list.
