# AI flows for Follow a Request — design

Date: 2026-10-12. Status: approved, implemented (see `docs/superpowers/plans/2026-10-12-ai-flows.md`).

## Goal

Follow a Request works for frameworks whose request flow can't be found with static rules. Symfony has a static
finder (`#[Route]`, YAML/XML route files). Laravel doesn't fit that: routes in `routes/web.php`, middleware,
`event()` → listeners, jobs and container bindings. Spring needs Java support first and is out of scope here.

An AI assistant (JetBrains AI Assistant / Junie, GitHub Copilot agent mode, Claude Code, anything that speaks MCP)
reads the code and tells CodeLanes the hops of a flow. CodeLanes checks each hop against the real code, follows
the ordinary calls itself and draws the chain on the canvas. The flow is saved as a dot file, so colleagues reopen it
without an AI.

## Rules

- The AI only **points at existing methods** and names the relation between them. It never writes, changes or
  explains code. CodeLanes draws nothing that isn't in the code.
- The reader always sees **what the code proves and what the AI claims**: proven hops are solid lines, claimed
  hops are dashed.
- Without the IDE's MCP server, CodeLanes works as before. Saved flows still open.

## Who does what

The AI gives the **entry point and the hops static analysis can't see**. CodeLanes follows the ordinary calls from
every hop target itself, as Follow a Request does today (depth 3, cap 80 blocks, "and N more calls").

Hop kinds (closed list, also the label on the line):

| kind | meaning | example |
|---|---|---|
| `routes` | a route lands in an action | `POST /orders` → `OrderController::store` |
| `middleware` | a middleware runs before the next step | `Authenticate::handle` → `OrderController::store` |
| `dispatches` | code fires an event, job or message | `OrderService::place` → `OrderPlaced::__construct` |
| `listens` | a listener or handler picks it up | `OrderPlaced::__construct` → `SendOrderMail::handle` |
| `binds` | an interface or abstract call ends up in a bound class | `OrderRepository::save` → `EloquentOrderRepository::save` |
| `calls` | a plain call the AI wants on the chain | `OrderController::store` → `OrderService::place` |

## Components

All new code lives in `dev.codelanes.flow`, except the MCP part.

- **`Flow`** (model): `Flow(name, entry, hops, version)`. `entry` is a label (e.g. `POST /orders`) plus the
  method it lands in. `Hop(from, to, kind)` holds method references as `Fully\Qualified\Class::method`.
- **`FlowResolver`**: no UI, unit-testable, runs in a read action. It turns a `Flow` into a `BlockModel`:
  - Each reference is looked up with `PhpIndex` (class by FQN, then its own or inherited method).
  - Hops with an unknown end are not drawn. They're returned as `unknown` references.
  - Every resolved hop target is followed with the existing call following (`followChain`).
  - Hops become links of a new `LinkKind.FLOW`, which carries the hop kind and a `proven` flag (see below).
- **`FlowFiles`**: reads and writes `*.flow.json` through `SharedJsonFile`, so it re-reads on change, never
  overwrites a file it can't parse and notifies on errors. It also keeps the list of flow folders.
- **`FlowTools`** (`dev.codelanes.mcp`): an MCP toolset, registered in `META-INF/codelanes-mcp.xml` via
  `<depends optional="true" config-file="codelanes-mcp.xml">com.intellij.mcpServer</depends>` on the extension
  point `com.intellij.mcpServer.mcpToolset`.
- **Follow a Request**: the picker lists saved flows (marked as flows) next to the static Symfony routes.
- **Verifying**: `verifyPlugin` also checks PhpStorm 2025.2, the first version with the MCP server. The calling
  project is looked up by name, because its accessor moved between 2025.2 and 2026.1.

## MCP tools

**`codelanes_show_flow(name, entry, hops)`**
- `entry`: `{ "label": "POST /orders", "method": "App\\Http\\Controllers\\OrderController::store" }`.
- `hops`: strings, in order, each `From\Class::method --kind--> To\Class::method`. Plain strings rather than
  objects, so nothing depends on the MCP server's serialization library version.
- The tool description briefs the AI:
  - Use full class names and existing methods only.
  - Use only the six hop kinds, with one line on each.
  - Leave plain calls to CodeLanes unless they matter for the story.
  - Give no explanations.
- What it does: resolves, opens the canvas on the entry point's file with the flow shown, and saves (see Storage).
- Reply, as plain text for the AI, for example:
  `Shown "POST /orders": 6 hops, 4 proven, 2 claimed. Saved to .codelanes/flows/post-orders.flow.json. Unknown: App\Foo::bar (no such method).`
  The reply says when nothing was saved (the user cancelled) and when the entry method doesn't exist (then
  nothing is shown).

**`codelanes_list_flows()`**
- Lists the names, entry labels and file paths of saved flows, so the AI updates a flow instead of making a duplicate.

Both tools reject input they can't parse, and an unknown hop kind, with a reply that says what's wrong. They never
throw at the AI.

## Proven or claimed

A hop is **proven** (solid line) when CodeLanes finds it in the code:

- `calls`, `dispatches`, `middleware`: the `from` method contains a call or `new` that resolves to the `to` method or
  its class.
- `binds`: the `to` class implements or extends the class of `from`, and has a method of that name.
- `listens`: the `to` method's first parameter type is the class of `from`.
- `routes`: never proven. The entry label is the AI's (and the human's) word.

Everything else is **claimed** (dashed line): both ends exist, nothing in the code links them directly. Labels and
colours follow the existing line style: the label is the hop kind; a proven or claimed `calls` uses the call colour.

## Storage

- One file per flow: `<slug-of-name>.flow.json`, e.g. `post-orders.flow.json`, containing
  `{ "version": 1, "name", "entry", "hops" }`.
- **New flow:** CodeLanes asks where, with a folder picker that suggests `.codelanes/flows/` (created if needed).
  A git-ignored folder keeps the flow private. If the user cancels, the flow is shown but not saved, and the reply
  says so.
- **Existing flow** (same name in a known file): overwritten in place, without asking.
- **Where CodeLanes looks:** `.codelanes/flows/`, plus every folder this user has saved a flow to. That list is stored
  per user, in the workspace file, like pins.
- **Known limitation:** a colleague who saves to a custom shared folder only gets that folder into your list once
  you open it from Follow a Request ("Open flow file…"). The default `.codelanes/flows/` is always seen.

## When the code has changed since

- A flow is resolved again every time it opens; nothing about the code is stored in the file except the
  references.
- Hops with an end that no longer exists go into one grey block: "no longer in the code: `App\Foo::bar`, …".
  The rest of the flow is drawn as usual.
- An entry method that no longer exists shows the canvas notice "Couldn't follow the flow: … no longer exists".
- Asking the AI again updates the flow.

## Threading

- MCP tool calls arrive off the EDT. Resolving runs in a cancellable non-blocking read action in smart mode.
- The folder picker and opening the canvas run on the EDT. The tool waits for them and then replies.

## Testing

- `FlowResolverTest` (BasePlatformTestCase) with Laravel-style PHP fixtures: a controller, a service that fires an
  event, an event, a listener, an interface with a bound class, a middleware. It covers:
  - Proven vs claimed for each kind.
  - Unknown class, unknown method, inherited method.
  - The callee chain is followed from hop targets.
  - The 80-block cap.
- `FlowFilesTest`:
  - Round trip and slug naming.
  - A broken file is never overwritten.
  - An unknown `version` is refused, not misread.
  - The known-folders list.
- `FlowToolsTest`: calls the tool functions directly. It covers:
  - The reply text for success, unknown references, a bad kind, a missing entry and a cancelled save (with the
    picker replaced by a test stub).
  - The tests need `bundledPlugin("com.intellij.mcpServer")` for the test classpath only.
- Manual:
  - A small Laravel example in `playground/` (routes file, controller, event, listener, binding).
  - An MCP client (Claude Code or Copilot agent mode) against PhpStorm's MCP server: show, update and list a flow,
    then reopen it from Follow a Request without the AI.

## Out of scope

- Spring/Java: needs Java support (roadmap item 4).
- A "Follow with AI" button on the canvas that calls an AI itself (would need API keys).
- Built-in Laravel rules in the plugin: the AI reads the code.
- Explanations or summaries from the AI, by design.
