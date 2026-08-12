# Java2Flowchart IntelliJ 插件

Java2Flowchart 在 JetBrains IDE 中把 Java 代码转换为 Mermaid 流程图。插件基于 PSI 构建通用控制流 IR，再渲染为 Mermaid，方便快速可视化方法逻辑。

## 功能
- 提取方法/代码块的控制流：if/else、循环、switch、try/catch、三元、模式匹配、方法调用/递归、可控深度的 JDK 调用等。
- 生成 Mermaid `flowchart TD`，包含 Start/End/Decision/Action/Return/Exception 等节点。
- 支持内联调用链（虚线 calls），可按配置展开三元和 switch 表达式。
- 在项目根目录创建 `Java2Flowchart/`，按 “包名_类名_方法名” 生成 Markdown 文件并打开。

## 使用
1) 右键 Java 方法或选中代码，选择 **Generate Flowchart**。  
2) 插件会生成 `Java2Flowchart/<package>_<Class>_<method>.md`。  
3) 在文件中查看或复制 Mermaid 图。

## 设置（File | Settings | Tools | Java2Flowchart）
- 折叠线性链。
- 合并相同的调用目标。
- JDK API 调用深度（-1 不展示，0 仅调用标签，1+ 展开）。
- 三元展开级别（-1 全展开，0 不展开，N 展开 N 层）。
- 标签最大长度（-1 不截断）。
- 界面语言（中/英文）。

## 实现原理
- PSI 抽取 → 控制流 IR（节点/边及源码位置信息）→ Mermaid 渲染。
- 节点 ID 基于源码行号，便于稳定跳转；元信息保留 TextRange。

## 参与贡献
欢迎在仓库提交 issue/PR。测试执行 `./gradlew test`。


