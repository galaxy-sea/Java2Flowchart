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
- foldSequentialCalls (合并顺序调用): true  
- foldSeqSetters (连续 set): true  
- foldSeqGetters (连续 get/is): true  
- foldSeqCtors (连续构造方法): true  
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
- foldSequentialCalls  
  - 开启后，允许按顺序合并下列子项；关闭时子项自动失效。  
  - foldSeqSetters / foldSeqGetters / foldSeqCtors: 仅在顺序合并开启时生效，分别合并连续的 setter/getter/构造调用（仅同类调用会合并）。  
- regex patterns  
  - 按正则匹配完整签名 `package.Class#method(paramTypes)`；匹配后该调用会被跳过渲染与展开。默认常见 get/set/is/toString/hashCode 已列出。

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
