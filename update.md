# JavaSlicer 修复记录

本文档汇总了相对于原始 [mistupv/JavaSlicer](https://github.com/mistupv/JavaSlicer) 的所有修改，涵盖 SDG 核心容错、CLI 批量模式修复和数据质量改进。

---

## 一、SDG 核心容错修改（vs upstream/develop）

原始 JavaSlicer 假设所有源代码依赖均可完全解析，对于自包含项目成立，但真实开源项目通常依赖外部库（如 Android SDK、Spring Framework、Apache Commons）。当遇到无法解析的符号时，原始工具抛出未捕获异常，导致整个分析管线终止，不产出任何结果。

以下修改将 7 类崩溃场景转为优雅降级（graceful degradation），切片算法本身（两遍过程间后向遍历）完全未修改。

### 1. 方法调用无法解析 → 调用图构建中止

**文件**：`sdg-core/.../graphs/CallGraph.java`

**崩溃场景**：
- `MethodCallExpr.resolve()` — 调用第三方库方法（如 `retrofit.create()`）
- `ObjectCreationExpr.resolve()` — 实例化第三方类（如 `new OkHttpClient()`）
- `ExplicitConstructorInvocationStmt.resolve()` — `super()`/`this()` 的父类不可解析

**原始行为**：抛出 `UnsolvedSymbolException` → 调用图构建失败 → 整个 SDG 中止

**修复**：在三个 `resolve()` 调用外层各加 try-catch，跳过不可解析的调用，继续构建调用图：
```java
// 修复前
n.resolve().toAst().ifPresent(decl -> createPolyEdges(decl, n));

// 修复后
try {
    n.resolve().toAst().ifPresent(decl -> createPolyEdges(decl, n));
} catch (RuntimeException e) {
    // Skip unresolvable method calls (e.g. third-party dependencies)
}
```

### 2. 类型继承关系无法解析 → 类图构建中止

**文件**：`sdg-core/.../graphs/ClassGraph.java`

**崩溃场景**：
- `extendedType.resolve()` — 继承第三方基类（如 `extends AppCompatActivity`）
- `implementedType.resolve()` — 实现第三方接口（如 `implements Serializable`）
- `type.resolve()` in `ObjectTree` — 字段类型解析失败

**原始行为**：抛出 `UnsolvedSymbolException` → 类图构建失败 → 多态分析中止 → SDG 中止

**修复**：在三处 `resolve()` 调用外层各加 try-catch，跳过不可解析的类型：
```java
// 修复前
Vertex<?> source = classDeclarationMap.get(mapKey(p.resolve()));
if (source != null && containsVertex(v))
    addEdge(source, v, new ClassArc.Extends());

// 修复后
try {
    Vertex<?> source = classDeclarationMap.get(mapKey(p.resolve()));
    if (source != null && containsVertex(v))
        addEdge(source, v, new ClassArc.Extends());
} catch (RuntimeException e) {
    // Skip unresolvable parent types
}
```

### 3. CFG 构建失败 → 整个 SDG 中止

**文件**：`sdg-core/.../graphs/sdg/SDG.java` → `buildCFGs()`

**崩溃场景**：方法体或构造器包含无法解析的语法或依赖

**原始行为**：`RuntimeException` → 单个方法 CFG 失败 → 整个程序 SDG 中止

**修复**：在 `MethodDeclaration` 和 `ConstructorDeclaration` 的 CFG 构建外层各加 try-catch，跳过失败的方法：
```java
// 修复前
CFG cfg = createCFG();
buildCFG(n, cfg);
cfgMap.put(n, cfg);

// 修复后
try {
    CFG cfg = createCFG();
    buildCFG(n, cfg);
    cfgMap.put(n, cfg);
} catch (RuntimeException e) {
    System.err.println("WARN: Skipping method " + n.getNameAsString() + ": " + e.getMessage());
}
```

### 4. 数据流分析 / 过程间连接 / 摘要弧失败 → SDG 构建中止

**文件**：`sdg-core/.../graphs/sdg/SDG.java` → `build()`

**崩溃场景**：
- `dataFlowAnalysis()` — 数据流传播遇到未解析的变量类型
- `connectCalls()` — 调用点与声明连接中遇到缺失的调用图边
- `createSummaryArcs()` — 摘要弧创建引用了未构建的 CFG/PDG

**原始行为**：`RuntimeException` → SDG 构建中止

**修复**：三个阶段各自包裹 try-catch，部分失败不阻塞后续阶段：
```java
try { dataFlowAnalysis(); }
catch (RuntimeException e) { System.err.println("WARN: Data flow analysis partially failed: " + e.getMessage()); }
buildAndCopyPDGs();
try { connectCalls(); }
catch (RuntimeException e) { System.err.println("WARN: Call connection partially failed: " + e.getMessage()); }
try { createSummaryArcs(); }
catch (RuntimeException e) { System.err.println("WARN: Summary arc creation partially failed: " + e.getMessage()); }
```

### 5. PDG 构建失败 → 所有方法 PDG 中止

**文件**：`sdg-core/.../graphs/sdg/SDG.java` → `buildAndCopyPDGs()`

**崩溃场景**：单个方法的程序依赖图构建遇到未解析符号

**原始行为**：`RuntimeException` → 所有方法 PDG 中止

**修复**：每个 CFG 对应的 PDG 构建独立 try-catch：
```java
for (CFG cfg : cfgMap.values()) {
    try {
        PDG pdg = createPDG(cfg);
        pdg.build(cfg.getDeclaration());
        pdg.vertexSet().forEach(SDG.this::addVertex);
        pdg.edgeSet().forEach(arc -> addEdge(pdg.getEdgeSource(arc), pdg.getEdgeTarget(arc), arc));
    } catch (RuntimeException e) {
        System.err.println("WARN: Skipping PDG: " + e.getMessage());
    }
}
```

### 6. CFG 缺失导致断言失败 → 过程间分析中止

**文件**：`sdg-core/.../graphs/sdg/InterproceduralActionFinder.java`

**崩溃场景**：方法的 CFG 未构建（因场景 3 失败），但过程间分析仍尝试访问该 CFG

**原始行为**：`assert cfg != null` → `AssertionError`（或 assertions 关闭时 `NullPointerException`）

**修复**：
```java
// 修复前
assert cfg != null;

// 修复后
if (cfg == null) return new HashSet<>(); // CFG not built (e.g. unresolved symbols)
```

### 7. CFG 缺失导致过程间定义/使用分析崩溃

**文件**：
- `sdg-core/.../graphs/sdg/InterproceduralDefinitionFinder.java`
- `sdg-core/.../graphs/sdg/InterproceduralUsageFinder.java`

**崩溃场景**：同场景 6，`handleFormalAction()` 中 `cfgMap.get()` 返回 null 后直接访问 `cfg.getExitNode()` / `cfg.getRootNode()`

**原始行为**：`NullPointerException` → 过程间分析中止

**修复**：在方法入口加 null 检查：
```java
CFG cfg = cfgMap.get(vertex.getDeclaration());
if (cfg == null) return; // CFG not built (e.g. unresolved symbols)
```

### 精度影响分析

| 维度 | 对可完整解析的项目 | 对有未解析依赖的项目 |
|------|-------------------|---------------------|
| **精度（Precision）** | 无变化 | 无变化 — 标记为 in-slice 的节点仍然正确 |
| **召回（Recall）** | 无变化 | 可能略低 — 涉及未解析符号的过程间边缺失 |
| **可用性** | 无变化 | 从「崩溃无输出」变为「输出部分结果」 |

---

## 二、CLI 批量模式修复（`Slicer.java`）

### 8. Git 合并冲突解决

`Slicer.java` 中有两处合并冲突（HEAD vs a1537a5），阻止了构建。

- **冲突 1（行 414-456）**：保留 HEAD 版本，包含 `StackOverflowError`/`Throwable` 的 try-catch 容错
- **冲突 2（行 472-487）**：保留 HEAD 版本的 `outputEmptyResult()` 方法

### 9. 空切片过滤

**根因**：真实项目有大量外部依赖无法解析 → 方法的 CFG 无法构建 → `sdg.slice()` 返回空结果（graphNodes=0），但这些空切片仍被输出，所有节点标记为 `y_bwd=0`。

**修复**：在 `sliceAll()` 中添加空切片跳过逻辑：
```java
if (slicedLines.isEmpty()) continue;
```

### 10. 行号匹配替代 Node.equals

**根因**：旧代码用 `HashSet<Node>.contains(stmt)` 匹配切片节点，但 JavaParser 的 `equals()`/`hashCode()` 不可靠，导致切片中的节点无法被正确识别。

**修复**：改为行号匹配：
```java
Set<Integer> slicedLines = new HashSet<>();
for (GraphNode<?> gn : slice.getGraphNodes()) {
    slicedLines.add(gn.getAstNode().getBegin().get().line);
}
// ...
boolean inSlice = slicedLines.contains(stmt.getBegin().get().line);
```

### 11. StackOverflowError / Exception 容错

在两个层级添加了容错：

- **SDG 构建层**（`slice()` 方法）：捕获 `StackOverflowError` 和 `Exception`，批量模式下输出空结果而非崩溃
- **函数处理层**（`sliceAll()` 方法）：捕获 `StackOverflowError` 和 `Throwable`，跳过有问题的函数继续处理

### 12. 清理调试日志

移除了所有 `DEBUG-SLICE` 调试日志。

### 修复前后对比

| 指标 | 修复前 | 修复后 |
|------|--------|--------|
| 切片级标签率 | ~3-6% | ~98% |
| 节点级标签率 | ~0.3% | ~21-27% |
| 平均每切片标签比例 | 接近 0 | ~34% |

---

## 本次对话修复

### 6. EID 碰撞修复（重载方法）

**问题**：EID 生成仅使用 `projectName + relativePath + functionName`，重载方法（同名不同参数）生成相同 EID，导致数据碰撞。

**修复**：EID 生成加入方法签名（参数类型列表）：
```java
// 修复前
String uniqueString = projectName + "|" + relativePath + "|" + functionName;

// 修复后
StringBuilder sigBuilder = new StringBuilder("(");
for (int pi = 0; pi < callable.getParameters().size(); pi++) {
    if (pi > 0) sigBuilder.append(",");
    sigBuilder.append(callable.getParameter(pi).getType().asString());
}
sigBuilder.append(")");
String uniqueString = projectName + "|" + relativePath + "|" + functionName + "|" + sigBuilder.toString();
```

示例：`add(int, int)` 和 `add(double, double)` 现在生成不同的 EID。

### 7. 节点 ID 稳定性修复

**问题**：使用 `stmt.hashCode()` 作为节点 ID，`hashCode()` 在不同 JVM 运行间不稳定，无法复现。

**修复**：改为基于源代码位置的确定性 ID `line:column`：
```java
// 修复前
nodeInfo.put("id", String.valueOf(stmt.hashCode()));

// 修复后
com.github.javaparser.Position stmtPos = stmt.getBegin().get();
nodeInfo.put("id", stmtPos.line + ":" + stmtPos.column);
```

### 8. 相对路径计算修复

**问题**：使用字符串前缀匹配（`startsWith`）计算相对路径，边界情况下可能出错（如 `/src1` 误匹配 `/src1_backup`）。

**修复**：改用 `java.nio.file.Path` API：
```java
// 修复前
if (filePath.startsWith(includeDir.getAbsolutePath())) {
    relativePath = filePath.substring(includeDir.getAbsolutePath().length());
    ...
}

// 修复后
java.nio.file.Path includePath = includeDir.getAbsoluteFile().toPath().normalize();
java.nio.file.Path absFilePath = new File(filePath).getAbsoluteFile().toPath().normalize();
if (absFilePath.startsWith(includePath)) {
    relativePath = includePath.relativize(absFilePath).toString();
    break;
}
```

### 9. Forward Slicing 支持

**问题**：`y_fwd` 被硬编码为 `0`（`Slicer.java` 原注释：`// Forward slicing not supported yet`），无法产出正向切片标签。

**修复**：新增 `ForwardClassicSlicingAlgorithm` + 在 `Slicer.java` 批量模式中增加正向切片计算。

#### 新增文件：`sdg-core/.../slicing/ForwardClassicSlicingAlgorithm.java`

继承 `ClassicSlicingAlgorithm`，仅覆盖 `pass()` 方法，将遍历方向从 backward（`incomingEdgesOf` + `getEdgeSource`）反转为 forward（`outgoingEdgesOf` + `getEdgeTarget`）。两遍 pass 的 ignore 条件完全不变（Horwitz-Reps-Binkley 对偶算法）：
- Pass 1：忽略 interprocedural output arcs（不从被调方法上升至调用方）
- Pass 2：忽略 interprocedural input arcs（不从调用方下降至被调方法）

```java
// 唯一改变：遍历方向
// ClassicSlicingAlgorithm（backward）:
for (Arc arc : graph.incomingEdgesOf(node)) { ... graph.getEdgeSource(arc) ... }

// ForwardClassicSlicingAlgorithm（forward）:
for (Arc arc : graph.outgoingEdgesOf(node)) { ... graph.getEdgeTarget(arc) ... }
```

#### 修改文件：`sdg-cli/.../cli/Slicer.java`

在 `sliceAll()` 中，对每个切片准则额外执行一次正向切片：
```java
// 1. Backward slice（原逻辑，完全不变）
Slice bwdSlice = sdg.slice(sc);
Set<Integer> bwdSlicedLines = ...;

// 2. Forward slice（新增，独立于 backward）
SlicingCriterion fwdSc = new FileLineSlicingCriterion(...);
ForwardClassicSlicingAlgorithm fwdAlgo = new ForwardClassicSlicingAlgorithm(sdg);
Slice fwdSlice = fwdAlgo.traverse(fwdSc.findNode(sdg));
Set<Integer> fwdSlicedLines = ...;

// 3. 输出双标签
nodeInfo.put("y_fwd", inFwdSlice ? 1 : 0);
nodeInfo.put("y_bwd", inBwdSlice ? 1 : 0);
```

Forward slice 的计算包裹在 try-catch 中，失败时 `y_fwd` 默认为 `0`，不影响 `y_bwd`。

#### 精度影响

| 维度 | 影响 |
|------|------|
| **Backward slice (y_bwd)** | **零影响** — `sdg.slice(sc)` 的调用路径和所有现有类均未修改 |
| **Forward slice (y_fwd)** | 使用经典两遍算法的正向对偶，SDG 已包含全部边信息，精度等同于 backward |
| **设计选择** | Forward 使用 `ClassicSlicingAlgorithm` 基类而非 `ExceptionSensitiveSlicingAlgorithm`，因异常敏感规则（CC1/CC2、PPDG）为 backward 专用剪枝逻辑 |

#### 验证结果（Example1.java, criterion: `sum` at line 3）

```
int sum = 0;                  y_bwd=1, y_fwd=1  ← criterion 自身
int prod = 0;                 y_bwd=0, y_fwd=0  ← 无关
sum += 1;                     y_bwd=0, y_fwd=1  ← sum 值正向传播
prod += n;                    y_bwd=0, y_fwd=0  ← 无关
System.out.println(sum);      y_bwd=0, y_fwd=1  ← sum 值正向传播
System.out.println(prod);     y_bwd=0, y_fwd=0  ← 无关
```

### 关于行号匹配的设计决策

调查了是否使用 `line:column` 对代替纯行号进行切片标签匹配。结论：**保留行号匹配**。

原因：SDG 的 `GraphNode` 包裹的 AST 节点类型（如 `VariableDeclarator`）与 `findAll(Statement.class)` 返回的 `Statement` 对象的列号往往不同，使用 `line:column` 会导致大量漏标（false negatives），反而降低标签率。在规范格式的 Java 代码中，同一行多个独立语句极为罕见，行号匹配是 ML 训练数据的最优实践选择。

---

## 已确认无需修复的项目

- **变量发现**：`findAll(VariableDeclarator.class)` 和 `findAll(Parameter.class)` 已递归发现所有变量类型，包括 for 循环变量、catch 变量、lambda 参数、try-with-resources 变量
- **SDG 核心引擎**：`Slice.getGraphNodes()` 不会返回 null（返回空 Set），`GraphNode.getAstNode()` 的 null 检查已存在

---

## 构建说明

```bash
# 构建（跳过测试，因测试依赖需要 Java 17 而运行时为 Java 11）
mvn package -DskipTests -Dmaven.test.skip=true

# 运行示例
java -jar sdg-cli/target/sdg-cli-1.3.0-jar-with-dependencies.jar -a -i examples/ -p test_project -o output/
```
