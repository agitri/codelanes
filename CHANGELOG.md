# Changelog

## 0.1.0

First public release: read and edit PHP classes as a canvas of real code blocks.

- **The canvas is the editor**: every block is a real PhpStorm editor (completion, inspections, refactorings, undo);
  the file on disk stays plain PHP.
- **Lanes**: what a class builds on (parents, interfaces, traits, injected dependencies) on the left, the class in
  the middle, its methods on the right; methods that call each other sit together. Column and Tree layouts too.
- **Colour-coded, labelled lines** for extends, implements, uses, injected, calls / called by and overrides, routed
  around blocks; where two lines cross, one hops over the other.
- **Follow a Request**: pick a route (`#[Route]` attributes or `config/routes` YAML/XML) and see the whole chain of
  code it runs, through interfaces into their implementations.
- **+ calls**, **Add method…** and **+ parents** to follow the flow across files and dig deeper.
- **Review marks and notes** (✓ / ? / !, "changed since review") and **note blocks**, shared with your team in
  `.codelanes/`.
- **Working sets** to save and reopen the canvases of a task or review.
- **Flows from your AI assistant**: through PhpStorm's MCP server (2025.2+), an assistant can show a request flow
  for any framework (e.g. Laravel's routes, middleware, events and bindings). CodeLanes checks every step against the
  code: solid lines are proven, dotted lines are only claimed, and references that don't exist are never drawn.
  Flows are saved as `*.flow.json` dot files and open from Follow a Request without an AI.
- Usage highlighting, Cmd+click staying on the canvas, `+ method` / delete, keyboard navigation between blocks.
