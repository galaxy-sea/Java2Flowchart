# Java2Flowchart 配置说明

本文件记录当前生成流程图时的配置，并对每个开关的作用与典型取值做简要说明，方便对照与调整。

## 当前配置快照

- mergeCalls: true  
- jdkApiDepth: 0  
- callDepth: 1  
- ternaryExpandLevel: -1  
- labelMaxLength: 80  
- useJavadoc: true  
- language: ZH  
- foldFluentCalls (合并链式调用): true  
- foldNestedCalls (合并嵌套调用): true  
- foldSequentialCalls (合并顺序调用): true  
- foldSeqSetters (连续 set): true  
- foldSeqGetters (连续 get/is): true  
- foldSeqCtors (连续构造方法): true  
- exportSource (输出方法源码): false  
- regex patterns (跳过规则): 空

## 选项详解

- mergeCalls  
  - true: 同一个方法的多次调用合并为一个被调用节点。false: 每次调用都独立显示。  
- jdkApiDepth  
  - -1: 展开 JDK 方法实现；0: 仅显示调用名称，不展开；>0: 展开到指定深度。  
- callDepth  
  - 0: 只解析当前方法；1: 解析直接调用的方法；N: 递归解析到 N 层；-1: 不限深度。  
- ternaryExpandLevel  
  - -1: 完全展开三元表达式；0: 不展开；N: 展开至 N 层。  
- labelMaxLength  
  - 节点文字最大长度，-1 表示不截断。  
- useJavadoc  
  - true: 方法节点优先使用 Javadoc 第一行/句作为标签。  
- language  
  - EN / ZH，影响标签和设置描述的语言。  
- foldFluentCalls  
  - true: 将链式调用合并为一个节点；false: 拆成多节点，节点间边标记为 fluent。  
- foldNestedCalls  
  - true: 将嵌套调用合并为一个节点；false: 拆成多节点，节点间边标记为 nested。  
- foldSequentialCalls  
  - 开启后，允许按顺序合并下列子项；关闭时子项自动失效。  
  - foldSeqSetters / foldSeqGetters / foldSeqCtors: 仅在顺序合并开启时生效，分别合并连续的 setter/getter/构造调用（仅同类调用会合并）。  
- regex patterns  
  - 按正则匹配完整签名 `package.Class#method(paramTypes)`；匹配后该调用会被跳过渲染与展开。默认常见 get/set/is/toString/hashCode 已列出。
- exportSource  
  - true: 在生成的 Markdown 中追加所选方法的源码片段（含 Javadoc/注释）；false: 不输出源码。  

## 解析顺序与开关影响

- 解析顺序：先依据 `foldFluentCalls` / `foldNestedCalls` 拆分或折叠调用，再执行顺序合并（set/get/is/ctor），最后应用合并相同调用、递归展开等处理。  
- 链式/嵌套拆分：`foldFluentCalls=false` 会拆成多个节点并打上 `fluent` 边；`foldNestedCalls=false` 会为参数中的调用打上 `nested` 边。只有真正出现链（长度 > 1 或存在 fluent 关系）时才会标记 `chainSplit`，防止这些链节点被顺序折叠。单个调用不会被标记，可参与顺序合并。  
- 顺序合并：`foldSequentialCalls=true` 时，连续的 set/get/is/ctor 节点（同一类别、无空行、无 chainSplit/noFold 标记）会合并到一个节点，标签使用 `<br/>` 叠加。  
- 调用展开：`callDepth` 与 `jdkApiDepth` 控制递归展开深度；被 `regex patterns` 命中的调用会被跳过渲染/展开；JDK 调用在深度 0 时只显示标签不展开，在深度 -1 时完全展开。  
- 标签：`useJavadoc` 优先取 Javadoc 第一句；`labelMaxLength` 会截断超长标签（-1 不截断）。  
- 语言/展示：`language` 影响节点和设置说明的语言；`mergeCalls` 决定同一个方法的多次调用是否合并为单个被调用节点。

## 示例

```java
// callDepth 对比
foo();            // 当前方法
bar();            // 直接调用
baz().qux();      // 更深层调用
```
- callDepth=0: 只显示 foo 内部流程，不展开 bar/baz/qux。  
- callDepth=1: 展开 bar 和 baz，但不展开 qux。  
- callDepth=2: 展开到 qux。

```java
// 顺序合并示例（foldSequentialCalls=true, foldSeqSetters=true）
bean.setA(1);
bean.setB(2);
bean.setC(3);
```
- 上例会合并为一个连续 set 节点；中间若有空行则不会合并。
