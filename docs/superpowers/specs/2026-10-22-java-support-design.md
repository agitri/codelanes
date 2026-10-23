# Java support (IntelliJ IDEA) — design

Date: 2026-10-22. Status: approved in chat, spec written for review.

## Goal

CodeLanes works for Java in IntelliJ IDEA, with Spring Boot as the main target: open a Java class as a canvas of
lanes, follow calls across files, review and note as in PHP, and let an AI assistant show Spring request flows through
the existing MCP tools. The plugin installs in IntelliJ IDEA without the PHP plugin, and in PhpStorm without Java.

This is project 1 of four. Later projects, each with its own spec:

2. Spring routes in Follow a Request (`@GetMapping`, `@RequestMapping`, …), found without AI.
3. JAX-RS (Jakarta EE, Quarkus) and Micronaut routes.
4. Kotlin.

## Rules

- **Nothing PHP users have today changes.** All current tests stay green without edits to what they assert. Block ids
  for PHP stay the same, so saved layouts, review marks, notes, working sets and flow files keep working.
- The canvas, layout, slice editors, review, notes, working sets, flow files and MCP tools stay language-neutral: they
  never import PHP or Java classes.
- Java gets the same lanes and the same lines as PHP. Where Java has no equivalent (traits), the lane stays empty.

## Approach

A `LanguageSupport` extension point, one implementation per language, each in an optional plugin descriptor:

- `plugin.xml` depends only on `com.intellij.modules.platform` (and optionally the MCP server, as today).
- `META-INF/codelanes-php.xml`, loaded with `<depends optional="true">com.jetbrains.php</depends>`, registers
  `PhpLanguageSupport`.
- `META-INF/codelanes-java.xml`, loaded with `<depends optional="true">com.intellij.java</depends>`, registers
  `JavaLanguageSupport`.

Rejected: a single UAST-based builder (rougher source ranges, and the slice editors need exact ones; PHP isn't UAST
anyway), and a Java copy with `if php … else java` in the callers (grows messy with every language).

### `LanguageSupport` (package `dev.codelanes.lang`)

```kotlin
interface LanguageSupport {
    /** Cheap check on the file type only, no PSI: is this file this language's? */
    fun handles(file: VirtualFile): Boolean
    /** Why [file] can't be shown as blocks, or null. Structure only, safe while indexing. */
    fun unsupportedReason(file: PsiFile): String?
    fun build(file: PsiFile, revealed: Set<String>, followedCalls: Set<String>, added: Set<String>): BuildResult
    /** Every method in the project's own code, for Add method… Needs a read action and indexes. */
    fun methods(project: Project): List<MethodRef>
    /** Routes found without AI, for Follow a Request. Empty for Java in this project. */
    fun routes(project: Project): List<EntryPoint>
    /** `Fqn::method` → what it points at, for AI flows; null if the reference isn't this language's syntax. */
    fun findFlowTarget(project: Project, reference: String): FlowLookup?
    /** Whether the code proves a hop of [kind] between two targets this support found. */
    fun proves(kind: String, from: FlowTarget, to: FlowTarget): Boolean
    /** Text of an empty method called [name], indented by [indent], for "+ method". */
    fun newMethodText(name: String, indent: String, inInterface: Boolean): String
}
```

- `LanguageSupports.forFile(file)` returns the first registered support that `handles` it, or null.
- `MethodRef`, `EntryPoint` move from `dev.codelanes.php` to `dev.codelanes.lang` unchanged.
- `FlowTarget` holds the class fqn the reference named, the block-id parts of the method (or null for the class as a
  whole), the PSI file of the declaring class, and an opaque PSI element for `proves`. `FlowLookup` is
  `Found(FlowTarget)` or `Missing(why)`, as `FlowResolver.Found` is today.

### Who calls it

| Today | After |
|---|---|
| `BlocksEditorProvider`, `BlocksUnavailableNotification`: `PhpFileType` + `PhpBlockBuilder.unsupportedReason` | `LanguageSupports.forFile(file)?.unsupportedReason(psi)` |
| `BlocksSession` rebuild: `PhpBlockBuilder.build` | the file's support `.build` |
| `BlocksSession.addMethod`: PHP method text | the file's support `.newMethodText` |
| `AddMethodToCanvasAction`: `MethodFinder.find` | the open file's support `.methods` |
| `FollowRequestAction`: `RouteFinder.find` | all supports' `.routes`, concatenated |
| `FlowResolver`: `PhpIndex` lookups and PHP proof rules | asks every support's `findFlowTarget` (first non-null wins), the entry file's support for `unsupportedReason`, and the targets' support for `proves`; the id logic (`method:`, `callee:`, `::class`) stays in `FlowResolver` |

`PhpBlockBuilder`, `MethodFinder`, `RouteFinder` and `RouteFiles` stay where they are; `PhpLanguageSupport` delegates
to them. The PHP lookup and proof code moves out of `FlowResolver` into `PhpFlowLookup` in `dev.codelanes.php`.

## Java blocks

`JavaBlockBuilder` (package `dev.codelanes.java`) mirrors `PhpBlockBuilder` with Java PSI. Same constants (10
implementers, 10 callees per method, 80-block chain, 5 implementations).

### Which files open as blocks

A `.java` file with exactly one top-level type: `class`, `interface`, `enum`, `record` or `@interface`. Otherwise the
existing banner says why:

- "Expected exactly one top-level class, interface, enum or record, found N"
- `package-info.java` and `module-info.java`: "No class in this file"

Nested and local classes stay inside the class block.

### Lanes

| Java | Block |
|---|---|
| `package` and `import`s | HEADER, collapsed, titled `package com.acme.order · 12 imports` |
| `extends Base` (for an interface: the interfaces it extends) | PARENT (INTERFACE for an interface), EXTENDS link |
| `implements Foo` | INTERFACE, IMPLEMENTS link |
| injected types (below) | DEPENDENCY, INJECTS link |
| the class: annotations, Javadoc, signature, fields, constructors, nested types | CLASS |
| each method | METHOD, OWNS link |
| for an interface or abstract class: project classes implementing / extending it | IMPLEMENTER + IMPLEMENTATION, as in PHP |

Javadoc and annotations are part of every block's range (Java PSI includes them in the element's text range).
Constructors stay in the class block, like PHP's `__construct`.

### Injected types (the dependency lane)

The types of:

1. the parameters of every constructor;
2. fields annotated `@Autowired`, `@Inject` or `@Resource`;
3. with Lombok `@RequiredArgsConstructor`: every `final` instance field without an initializer;
   with `@AllArgsConstructor`: every instance field.

Annotations match by qualified name when they resolve, else by short name (a project without the Lombok jar on the
index still gets its dependency lane). Left out: primitives, the class itself, and JDK types (`java.*`, and `javax.*`
except `javax.inject`; `jakarta.*` stays in). A generic type counts as its own class (`List<Order>` is
`java.util.List`, so it's left out). Duplicates are dropped.

### Method ids and titles

- Id: `method:<name>` when the name is unique among the class's own methods, `method:<name>(<Type>, <Type>)` with the
  simple, erased parameter type names when it is overloaded. So most ids look exactly like PHP's, and `+ method`,
  review keys and flows work unchanged. Cost: adding an overload changes the id of the existing method, so its review
  mark and dragged position are lost once.
- Callee and implementation ids follow the same rule: `callee:com.acme.OrderService::place`.
- Title: `place(Order order, boolean notify)`, the parameters' text as written, without annotations.
- Class title: `class OrderService extends Base implements Foo, Bar` (also `interface`, `enum`, `record`,
  `@interface`).

### Lines

- **calls** between own methods: `PsiMethodCallExpression.resolveMethod()` inside the class's own methods.
- **overrides**: a method links to each related type that declares a method it overrides (`findSuperMethods`).
- **+ calls**: calls into other project classes, as in PHP. A call to an interface or abstract method also shows its
  project implementations (`OverridingMethodsSearch` in project scope), linked IMPLEMENTED_BY.
- **+ parents** digs deeper through parents and interfaces, as in PHP.
- "Project code" means in content and not in a library, as in PHP. Library types are never pulled in as callees.

### Types without source

Parents, interfaces and dependencies can come from a library (`JpaRepository`, `ApplicationEventPublisher`). If the
type's navigation element is in a source file (an attached sources jar), the block shows that source. If not, the block
shows the type's title and its method list as summary, and can't be expanded: a new `Block.sourceless` flag hides its
fold arrow, as for MORE blocks.

### `+ method`

Adds `public void newMethod() {\n}` to a class, `void newMethod();` to an interface, with the file's indent, at the
end of the class, exactly where PHP adds it today.

## AI flows for Java

- References: `com.acme.order.OrderService::place`, or `::place(Order)` for an overload (simple parameter type
  names), `::class` for the class as a whole, `::<init>` or the class name as method for a constructor (the class block).
  A bare `::place` on an overloaded method takes the first in source order; the reply doesn't complain.
- Inherited methods resolve to the class that declares them, as in PHP.
- The six hop kinds are proven like this:
  - `calls`, `dispatches`, `middleware`: the `from` method calls `to`, or `new`s its class
    (`publisher.publishEvent(new OrderPlaced(order))`).
  - `binds`: the `to` method overrides the `from` method (interface → `@Service`/`@Repository` bean).
  - `listens`: the `to` method's first parameter type is the `from` class (`@EventListener void on(OrderPlaced e)`,
    `onApplicationEvent(OrderPlaced e)`).
  - `routes`: never proven.
- The MCP tool description adds one sentence: Java references use dots (`com.acme.OrderController::create`), PHP
  references backslashes. Examples stay PHP-first.
- A flow whose entry is a Java method opens the Java file's canvas. Mixed PHP/Java flows aren't a goal; a hop whose
  ends are in different languages is drawn as claimed if both resolve.

## Product

- Version 0.2.0. `sinceBuild` stays 252.
- Name "Open PHP Files as Blocks by Default" becomes "Open Files as Blocks by Default".
- Marketplace description and README: "for PHP (PhpStorm) and Java (IntelliJ IDEA)".
- `verifyPlugin`: PhpStorm 2025.2, 2025.3, 2026.1, 2026.2 and IntelliJ IDEA 2025.2, 2025.3, 2026.1, 2026.2.

## Build

- Develop and test against IntelliJ IDEA 2026.1 with the bundled Java plugin and the PHP plugin from the Marketplace,
  so one `runIde` has both languages.
- PHP-only and Java-only behaviour (the plugin loads with one of the two missing) is checked by `verifyPlugin` on
  PhpStorm and IntelliJ IDEA, and by hand once in each.

## Testing

- Every existing test passes unchanged in what it asserts (imports may move with `MethodRef` and `EntryPoint`).
- `JavaBlockBuilder*Test` (Java light fixture tests) with a small Spring-style fixture: `OrderController`,
  `OrderService` with a Lombok `@RequiredArgsConstructor`, `OrderRepository` interface and `JpaOrderRepository`,
  `OrderPlaced` event and `SendOrderMail` listener. Spring and Lombok annotations are stub classes in the fixture.
  Covered:
  - which files open and the banner reasons;
  - every lane and link kind above, including the three injection styles and the JDK filter;
  - overload ids and titles;
  - `+ calls` into an interface reaching its implementation; the caps;
  - a sourceless library parent.
- `JavaFlowLookupTest`: references (plain, overloaded, `::class`, constructor, inherited, unknown) and proven vs
  claimed for each hop kind with the Spring fixture.
- `BlocksSession` tests for a Java file: opens, `+ method` inserts valid Java, `+ calls` works.
- Manual: `playground/java-spring` (the same small Spring app, compilable without a build tool) in the sandbox
  IntelliJ IDEA; an MCP client shows `POST /orders` with a listener and a binding.

## Out of scope

- Spring route detection (project 2), JAX-RS/Micronaut (3), Kotlin (4).
- Nested classes as blocks of their own.
- Android-specific lanes.
- Bean resolution beyond inheritance (`@Qualifier`, `@Primary`, `@Bean` factory methods): a `binds` hop the AI names
  through those is claimed, not proven.

## Addendum (2026-10-23)

Project 2 (Spring routes in Follow a Request) was pulled into 0.2.0 after the sandbox test: `JavaRouteFinder` finds
`@Get/Post/Put/Delete/PatchMapping` and `@RequestMapping` (its `method`, else ANY) on methods of concrete classes,
prefixed by the class's `@RequestMapping`; several paths give several routes, constant paths are evaluated, anything
else is `?`. The entry carries the method key, so an overloaded action opens the right block.
