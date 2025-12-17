# Java2Flowchart IntelliJ Plugin

Java2Flowchart converts Java code into Mermaid flowcharts inside JetBrains IDEs. It walks PSI, builds a language‑agnostic control‑flow IR, then renders Mermaid so you can visualize methods quickly.

## What it does
- Extracts control flow from methods/blocks (if/else, loops, switch, try/catch, ternary, pattern matching, method calls, recursion, JDK calls with depth control).
- Renders a Mermaid `flowchart TD` with shapes for start/end/decision/action/return/exception.
- Adds inline call chains (dashed “calls” edges) and optional expansion of ternary and switch expressions.
- Opens the generated Markdown in your project under `Java2Flowchart/` (package + class + method name).

## Usage
1) Right‑click a Java method or select code, choose **Generate Flowchart**.  
2) The plugin creates `Java2Flowchart/<package>_<Class>_<method>.md` with Mermaid content.  
3) Copy/paste or preview the Mermaid diagram.

## Settings (File | Settings | Tools | Java2Flowchart)
- Fold linear chains.
- Merge identical call targets.
- JDK API call depth (-1 none, 0 show call label only, 1+ expand).
- Ternary expansion level (-1 fully expand, 0 none, N expand N levels).
- Label max length (-1 no truncation).
- Language (Chinese/English).

## How it works
- PSI extractor → ControlFlow IR (nodes/edges with metadata) → Mermaid renderer.
- Node IDs are line‑based for stable hyperlinks; metadata keeps source ranges for navigation.

## Contributing
Open issues/PRs in this repo. Tests: run `./gradlew test`.
