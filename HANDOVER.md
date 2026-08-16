# 交接：多语言代码分块与 tree-sitter 取舍

> **状态更新（2026-08-16）**：Java 基线已获准升级到 23，下面“暂不引入”的历史决策已被取代。
> 当前实现使用按语言拆分、内含 Windows/Linux/macOS 原生库的 `tree-sitter-ng` grammar：
> Java 保留 JavaParser；Python、Go、Rust、JavaScript/TypeScript、C/C++/C#、Ruby 优先走
> `TreeSitterChunker`；加载或解析失败时降级到 `SymbolChunker`，再降级到文本分块。PDF/Office
> 等文档仍走独立的内容提取与结构化文本分块链路。

- 日期：2026-08-16
- 影响范围：`agent-rag` 分块与检索

## 背景

RAG 分块此前只有两条路径：`.java` 交给 JavaParser 做 AST 分块，其余一律交给
`LangChainDocumentChunker`——一个按段落/句子/词边界切 450 token 窗口的散文切分器。它不知道
函数是什么，所以一个 Python chunk 经常从函数中间开始、到函数中间结束。

真正的代价是间接的。散文切出来的 chunk，`symbol` 字段恒为空，而 symbol 是全链路权重最高的
字段：

| 位置 | 权重 |
|---|---|
| `Bm25Retriever` 对 `symbol_text` 的 boost | 3.0（`path_text` 1.8，`search_text` 1.0） |
| `CodeAwareReranker` TF-IDF 字段权重 | 5.0（path 2.5，content 1.0） |
| `CodeAwareReranker.symbolAffinity` 精确符号匹配 | 额外 0.20 综合权重 |

也就是说，**非 Java 代码在决定排序的那个字段上是结构性劣势**，不是"效果差一点"，是拿不到分。
benchmark 的 symbol 分组 hybrid Recall@5 = 1.000，那是 Java 独享的成绩。

结论是需要给其他语言补上声明边界和 symbol。问题只剩用什么补。

## 候选方案评估

评估基线来自三条硬约束：

1. **编译目标锁定 Java 21**：根 POM 的 `maven.compiler.release=21`，Enforcer 的
   `requireJavaVersion [21,)`，CI 三个 matrix 全部使用 JDK 21。
2. **发布物是 shade 出来的 fat JAR**：CI 在 ubuntu / windows / macos 上冒烟测试它。
3. **检索必须离线可用**：`CodeQueryRewriter` 的类注释写明了这一点，查询扩展刻意不调模型就是
   为了守住它。任何让检索依赖网络的方案都与之冲突。

### 1. `io.github.tree-sitter:jtreesitter`（官方）

README 明写 `Install JDK 23+`，基于 Panama FFM。项目 `release=21` 下 FFM API 在编译期就不可见。

**否决：与 Java 21 基线直接冲突（约束 1）。**

### 2. `io.xberg.treesitterlanguagepack:tree-sitter-language-pack`

371 种语言的预编译语法，Java 侧同样是 Panama FFM 绑定（JDK 22+）。语法**首次使用时联网下载并
缓存**。此外 1.8.1 版本存在 native 资源路径打包错误（`natives/native/` 而非
`natives/{os-arch}/`，见其 issue #128，已在 ≥1.8.2 修复），说明其跨平台打包链路仍在磨合。

**否决：JDK 版本冲突（约束 1），且运行时下载语法破坏离线检索（约束 3）。**

### 3. `ch.usi.si.seart:java-tree-sitter`

JDK 11+，JNI，版本 1.12.0，API 完善（增量编辑、Query、游标遍历）。看起来是唯一绕开 JDK 版本
问题的成熟选择——但其 README 的特性列表原文是「Support for both macOS and Linux out of the
box」，**不包含 Windows**。

**否决：CI 有 windows-latest，主要开发环境也是 Windows（约束 2）。**

### 4. `io.github.bonede:tree-sitter-ng`

JDK 8+，JNI，用 Zig 交叉编译，每种语言是独立 artifact（`tree-sitter-python`、
`tree-sitter-javascript`…），native 库打进 jar。技术上唯一可行的一条。

代价：

- 每语言 × 每平台一份 native 库，全部要进 fat JAR。当前 shade 已经在报 68 个 jar 的资源重叠
  告警，再叠 native 资源会让打包更脆弱。
- JNI 库从 shaded uber-jar 里加载需要解压到临时目录，跨三平台的失败模式各不相同，而 CI 的冒烟
  测试只跑 `--version` / `--help`，覆盖不到 RAG 索引路径。
- 主线版本停在 0.22.5 / 0.21.0 一档，落后上游 tree-sitter 较多。

**暂缓：可行但成本与当前收益不匹配，且需要为它单独建立跨平台回归测试。**

## 决策

1. **现在**：实现 `SymbolChunker`，用模式匹配 + 定界扫描识别声明边界，不引入任何 native 依赖。
2. **暂不引入 tree-sitter**。前提条件是把编译基线升到 JDK 23+，那是一个独立决策，不应搭在分块
   改进里顺带做掉。

## 已实现：SymbolChunker

`agent-rag/src/main/java/dev/miniclaudecode/rag/chunk/SymbolChunker.java`

路由在 `FallbackChunker`，按可用精度分三层：Java → JavaParser AST；`SymbolChunker.supports(path)`
为真 → 模式化声明分块；其余 → 文本分块。

两种定界策略：

- `INDENT`（Python、Ruby）：函数体是后续缩进更深的行，遇到同级或更浅缩进即结束。
- `BRACE`（Go、Rust、JS/TS/JSX/TSX、Kotlin、Swift、Scala、PHP）：从声明行开始计花括号，回到零
  即结束；没有花括号但以 `;` 结尾的（接口方法、抽象声明、Rust trait 方法）取单行。

容器 chunk 自动止于下一个声明处，所以一个 class chunk 只包含自己的头部——成员各自成 chunk，
不会把文件存两遍。这是 `JavaAstChunker` 的 TYPE skeleton 同一个思路。首个声明之前的内容（import、
模块级语句）单独成一个 `module header` chunk。

**刻意排除 C / C++ / C#。** 它们的成员声明没有可锚定的前导关键字——`void Start()` 可能是方法定义、
函数调用或变量声明，取决于上下文。松到能抓住它的正则同时会抓住控制流。symbol 是权重最高的字段，
**错的 symbol 比没有 symbol 更糟**，所以这三种语言维持文本分块。

**已知不精确之处**（都是刻意接受的）：

- 不跟踪字符串和注释，字符串字面量里的 `}` 会提前结束 chunk。
- 缩进定界依赖代码格式规范，混用 tab/空格的文件边界会漂。
- 容器归属（`owner`）按缩进层级推断，压缩过的单行代码推不出来。

**降级保证**：识别不出任何声明的文件原样交给文本分块器。误判只会退回改动前的行为，不会丢文件。
见 `SymbolChunkerTest.aFileWithNoRecognisedDeclarationFallsBackToTheTextChunker`。

## 何时重新评估

出现下列任一情况时值得重开这个决策：

- 项目因其他原因升到 JDK 23+ → 官方 `jtreesitter` 立刻变成首选，方案 1 的否决理由消失。
- `seart-group/java-tree-sitter` 增加 Windows 支持 → 方案 3 在 JDK 21 下即可落地。
- 正则分块在真实多语言仓库上暴露出系统性错误边界 → 那时成本天平会倾斜。
- 需要的不只是声明边界，而是调用图、引用关系这类真正需要完整语法树的能力 → 正则方案没有
  演进空间，必须换。

## 如果要落地 tree-sitter：交接清单

按顺序执行，每步都能独立验证：

1. 先把编译基线提到 JDK 23+：同步修改根 POM 的 `maven.compiler.release`、Enforcer 的
   `requireJavaVersion`，以及 `.github/workflows/ci.yml` 三个 matrix 的 `java-version`。这一步
   单独提交、单独验证，不要和分块改动混在一起。
2. 引入 `io.github.tree-sitter:jtreesitter` 与所需语法。确认它在 shade 后的 fat JAR 里仍能加载
   native 库——**这是最容易翻车的一步**，务必在三个平台各跑一次真实索引（`index -w .`），而不是
   只跑 `--version`。
3. 新增 `TreeSitterChunker implements DocumentChunker`，与 `SymbolChunker` 实现同一个接口，
   在 `FallbackChunker` 里替换掉那一层即可，其余管线不用动。
4. `SymbolChunker` 先保留作为 tree-sitter 加载失败时的降级路径，观察一个版本再决定是否删除。
   它没有 native 依赖，是廉价的保险。
5. **提升 `LuceneCodeIndex.SCHEMA_VERSION`**。分块边界变化意味着 chunk id 全部变化，旧索引和新
   查询的失败方式是静默零结果，不是报错。
6. 更新 `benchmarks/rag/miniclaudecode-v2` 的 ground truth：其 `chunkId` 哈希了
   path/kind/owner/symbol/起始行，换分块器等于全部作废，必须重新标注。
7. 更新 `DependencyBaselineTest`，把 tree-sitter 的入口类型加进 classpath 断言。

## 后果

- 非 Java 代码现在有 symbol，能参与到 BM25 的 3 倍 boost 和重排器的 5.0 字段权重里；这是本次
  改动的全部目的。
- 分块精度按语言分三档，是有意为之的不均衡：Java 最准，九种语言够用，C 家族和散文维持原状。
  向用户解释检索质量差异时要说清这一点，不要宣称"支持所有语言"。
- `SymbolChunker` 的语言表是纯数据（`LANGUAGES` map），加一门语言只需加模式，不动逻辑。但加
  之前先确认该语言的声明有可锚定的前导关键字，否则参照 C 家族的理由拒绝。
- 本文档不改变 Java 21 基线。任何以"为了上 tree-sitter"为由的 JDK 升级，都必须作为独立决策
  评审，因为它同时影响 CI matrix、发布物和所有使用者的运行时要求。
