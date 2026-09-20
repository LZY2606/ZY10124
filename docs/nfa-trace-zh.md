# RE2/J 一次匹配的端到端追踪

本文基于本仓库当前源码（`version = 1.8`），按 `Parser → Simplify → Compiler →
Prog → Machine → Matcher` 的真实调用链追踪一次匹配。所有结论均标注具体类与方法；
凡属公开 API 保证与当前实现细节，文中明确区分。

配套的可运行观测工具与测试：

- `javatests/com/google/re2j/trace/NfaTracer.java`：包内观测 helper，通过反射
  驱动生产代码的 `Machine.add` / `Machine.step`，不新增任何公开 API。
- `javatests/com/google/re2j/trace/NfaTraceTest.java`：11 个 JUnit 用例，核对
  最终 match、capture 与若干关键线程转移，不对整段日志做快照。

追踪用的最小表达式（含 alternation、greedy repetition、可空分支、行首断言、
两个捕获组）：

```
^((a)*|a)(b?)
```

输入（Java UTF-16 字符串，含代理对 U+1D54F，MATHEMATICAL FRAKTUR CAPITAL X）：

```
"aa\uD835\uDD4Fb"   // length() == 5（UTF-16 code unit），codePointCount == 4
```

期望（`Matcher.find()` 实际结果）：整体匹配 `aa`，group 1 = `aa`，
group 2 = `a`（`(a)*` 最后一次迭代），group 3 = `""`（`(b?)` 走可空分支）。

---

## 1. 编译链：Parser / Simplify / Compiler / Prog

### 1.1 Parser 产生 Regexp 语法树

`Parser.parse(expr, flags)`（`Parser.java:788`）是唯一入口，`parseInternal`
（`Parser.java:792`）逐字符压栈：

- `^`：PERL 默认带 `ONE_LINE`? 否——`RE2.PERL = CLASS_NL | ONE_LINE | PERL_X |
  UNICODE_GROUPS`（`RE2.java:93`），但 MULTILINE 语义由 `(?m)` 在
  `parsePerlFlags` 里清掉 `ONE_LINE`（`Parser.java:1101`）。默认 flags 下
  `ONE_LINE` 置位，`^` 本应编译成 `BEGIN_TEXT`（`Parser.java:830-833`）。
  注意：`Pattern.compile` 的默认路径用的也是 `RE2.PERL`
  （`Pattern.java:152`），所以这里的 `^` 是 **BEGIN_TEXT（仅文本开头）**；
  带 `Pattern.MULTILINE` 时才是 `BEGIN_LINE`（也匹配 `\n` 之后）。两种形式在
  机器层都是同一条 `EMPTY_WIDTH` 指令，只是 arg flag 不同。
- `(`：`op(LEFT_PAREN).cap = ++numCap`（`Parser.java:818-820`），捕获组按
  开括号顺序编号；`|` 调 `parseVerticalBar`（`Parser.java:1212`），最终由
  `alternate()`（`Parser.java:276`）折叠成 `Op.ALTERNATE`。
- `*`：`repeat(STAR,...)`（`Parser.java:866-877`），惰性修饰由
  `NON_GREEDY` flag 携带。
- 结尾 `concat()` / `alternate()` 清空操作数栈，得到单个 `Regexp` 根。

### 1.2 Simplify：决定“可空”信息的来源

`Simplify.simplify` 把复杂重复改写成 `CONCAT/ALTERNATE/STAR/PLUS/QUEST` 的
规范形式。`Compiler.Frag.nullable`（`Compiler.java:25`）标记片段能否匹配空串，
它直接影响贪心 star 的代码生成：

- `Compiler.star(f1, ...)`（`Compiler.java:155`）：当 `f1.nullable` 为真时
  返回 `quest(plus(f1, ...), ...)`，即把 `x*` 改写成 `(x+)?`。注释
  （`Compiler.java:119-123`）说明这是为了在体可空时仍保持正确的优先级顺序，
  否则空闭包会让 ALT 的两个出口次序与回溯语义不一致。
- 本例 `(a)*` 的体 `a` 不可空，直接走 `loop`；`(b?)` 的 `quest` 片段
  `nullable = true`（ALT 含跳过出口）。

### 1.3 Compiler：指令优先级在代码生成时就固定

`Compiler.compileRegexp`（`Compiler.java:54`）先放 pc=0 的 `FAIL`
（`Compiler.java:51`，patch list 用 0 表示空），编译根片段，再把所有出口
patch 到一条新的 `MATCH`，并设 `prog.start`。

关键的优先级编码（这就是 leftmost-first 的根，**不是**运行时猜测）：

- `alt(f1, f2)`（`Compiler.java:103`）：`ALT.out = f1.i`，`ALT.arg = f2.i`
  （`Compiler.java:113-114`）。`Machine.add` 闭包按 **先 out 后 arg** 的顺序
  展开（`Machine.java:399-403`），所以语法上靠左的分支先入队。
- `loop(f1, nongreedy)`（`Compiler.java:125`）：
  - 贪心：`ALT.out = f1.i`（继续迭代优先），跳过出口挂在 `.arg`
    （`Compiler.java:131-133`）；
  - 惰性：翻转，`ALT.arg = f1.i`（`Compiler.java:128-130`）。
- `quest` 同理（`Compiler.java:140-153`）：`(b?)` 贪心时“匹配 b”在 out，
  “跳过”在 arg。
- `cap(arg)`（`Compiler.java:81`）：左括号编号 `2k`、右括号 `2k|1`，并更新
  `prog.numCap`；整体匹配隐式占 slot 0/1，所以 `numCap` 初值为 2
  （`Prog.java:24-26`）。
- `empty(op)`（`Compiler.java:168`）：生成 `EMPTY_WIDTH`，arg 是
  `Utils.EMPTY_*` 位掩码；`^`→`EMPTY_BEGIN_TEXT(0x04)`，多行 `^`→
  `EMPTY_BEGIN_LINE(0x01)`，`$`→`EMPTY_END_LINE/END_TEXT`，`\b`→
  `EMPTY_WORD_BOUNDARY(0x10)`（常量见 `Utils.java:143-149`）。
- 单 rune 的 `RUNE` 会被特化（`Compiler.java:189-201`）：无 fold 的单字面
  改成 `RUNE1`（直接 `c == runes[0]`，`Machine.java:340`），`.` 特化成
  `RUNE_ANY_NOT_NL` / `RUNE_ANY`。

### 1.4 Prog：本例编译产物（实测 dump）

用仓库类直接 dump（`Prog.toString()`，`Prog.java:166`）得到，`start=1`，
`numCap=8`，`cond=4`（`EMPTY_BEGIN_TEXT`），`prefix=""`：

```
0   fail
1   empty 4 -> 2          # ^  (EMPTY_BEGIN_TEXT)
2   cap 2 -> 8            # group1 '('  slot 2
3   cap 4 -> 4            # group2 '('  slot 4   ┐ (a)* 循环
4   rune1 "a" -> 5        #                     │
5   cap 5 -> 6            # group2 ')'  slot 5  ┘
6   alt -> 3, 9           # 贪心：out=3(再来一次) 优先，arg=9(退出)
7   rune1 "a" -> 9        # 第二个 alternation: 裸 a
8   alt -> 6, 7           # ((a)* | a)：out=6(左分支), arg=7(右分支)
9   cap 3 -> 10           # group1 ')'  slot 3
10  cap 6 -> 12           # group3 '('  slot 6   ┐ (b?)
11  rune1 "b" -> 13       #                     │
12  alt -> 11, 13         # 贪心 quest：out=11(匹配b), arg=13(跳过)
13  cap 7 -> 14           # group3 ')'  slot 7  ┘
14  match
```

`Prog.startCond()`（`Prog.java:94`）沿开头的 `EMPTY_WIDTH/NOP/CAPTURE`
收集断言位，得到 `re2.cond = EMPTY_BEGIN_TEXT`；这是后面“何时停止搜索”的
依据。`Prog.prefix`（`Prog.java:56`）从 start 跳过 NOP/CAPTURE 收集所有
匹配必须共享的字面前缀——本例被开头的 `EMPTY_WIDTH` 挡住，前缀为空。

---

## 2. Machine：线程 NFA 的一次执行

`RE2.doExecute`（`RE2.java:294`）从 Treiber 栈取/建一个 `Machine`，
`m.init(ncap)` 后调用 `m.match(in, pos, anchor)`，取回 `matchcap`
（`Machine.submatches`，`Machine.java:153`）。真正的算法在
`Machine.match`（`Machine.java:215`）。

### 2.1 数据结构（实现细节）

- `Thread`（`Machine.java:24`）：持有 `Inst inst`（当前停在的 **消耗类**
  指令：RUNE*/MATCH）和一份 `int[] cap`。
- `Queue`（`Machine.java:33`）是 Russ Cox 的 sparse array：
  `sparse[pc]` 指向 `densePcs/denseThreads` 槽位，`contains(pc)` O(1)
  去重（`Machine.java:47`）。同一 pc 在同一位置只保留 **第一次到达** 的
  线程——这是线性时间的关键之一。
- `runq`（本位置要消费当前 rune 的线程）与 `nextq`（闭包展开后等下一 rune
  的线程），每个 rune 交换一次（`Machine.java:296-299`）。
- 线程用完进 `pool` 栈复用（`Machine.alloc/free`，
  `Machine.java:159/176`），避免逐线程分配。

### 2.2 主循环与闭包：add() 的实际流转

`add(q, pc, pos, cap, cond, t)`（`Machine.java:383`）做 ε-闭包：

- `q.contains(pc)` 命中就返回（去重，先到先得）；
- `ALT/ALT_MATCH`：递归 `add(out)` 再 `add(arg)`（`Machine.java:399-403`），
  这就是“指令优先级”的兑现点；
- `EMPTY_WIDTH`：仅当 `(inst.arg & ~cond) == 0)`（指令要求的位在当前上下文
  全部满足）才继续（`Machine.java:405-409`）。`cond` 来自
  `Utils.emptyOpContext(r1, r2)`（`Utils.java:168`），按“前一个 rune /
  后一个 rune”算出 BEGIN/END_TEXT、BEGIN/END_LINE、word boundary 等位；
- `CAPTURE`（`Machine.java:415-424`）：**原地** 写
  `cap[inst.arg] = pos`，递归闭包后立即恢复旧值 `opos`。因为 slot 写入随
  闭包“经过”该指令而发生、随回溯式恢复而撤销，单个 `cap` 数组就能表达
  各线程的捕获状态；只有当闭包抵达一个消耗类指令、需要把线程入队时，才
  `System.arraycopy(cap, 0, t.cap, 0, ncap)` 复制一份（`Machine.java:433`）；
- 落到 `RUNE*/MATCH`：把（可能新建/复用的）`Thread` 放进 `denseThreads[d]`。

`step(...)`（`Machine.java:310`）按下标顺序处理 `runq`：

- `MATCH`（`Machine.java:332`）：非 longest 模式下无条件
  `t.cap[1] = pos` 并整体拷进 `matchcap`（`Machine.java:338-340`），随后
  `free(runq, j+1)`（`Machine.java:343`）——**当前位置排在后面的同起点
  线程被直接丢弃**，`matched = true`；
- `RUNE1` 等（`Machine.java:338-352`）：rune 匹配则
  `add(nextq, i.out, nextPos, t.cap, nextCond, t)`，在新位置做闭包并入队。

### 2.3 线性时间与“较晚结束的分支为何胜出”

线性时间：每个输入位置，每条程序指令（pc）在每个队列里至多存活一次
（sparse 去重），每线程每条指令的处理是均摊 O(1)，故总成本
O(输入长度 × 程序指令数)，与输入长度成线性；不存在传统回溯的指数路径
枚举。capture 复制为 O(numCap)，也只随程序规模变化。

“较晚结束仍胜出”要分清两件事：

1. **起点最左优先**：外层每轮都在未匹配时把 `prog.start` 以当前 `pos`
   重新 seed 进 `runq`（`Machine.java:271-277`）。起点更早的线程一旦
   MATCH，longest=false 时 later 起点的探索不会改写它。
2. **同一起点内的优先序**：pc 顺序由 Compiler 固定（§1.3）。但
   leftmost-first **不是**“先到 MATCH 就立刻终止”。非 longest 模式在
   `step` 里命中 MATCH 只裁剪 **runq 中 j 之后的线程**；已经排在前面、
   此刻成功消费了 rune 并进入 `nextq` 的高优先级贪心续跑线程不受影响，
   它们在后续位置到达 MATCH 时会再次写 `matchcap`（`Machine.java:338`）。

本例的具体体现：pos=0 时 `(b?)` 的可空出口（pc14）立即 MATCH，得到空匹配
`[0,0)`；但贪心 `(a)*` 的循环体 pc4 在队列中排在它 **之前**
（顺序 4, 11, 14, 7），pc4 已先消费 `a` 进入 nextq，于是 pos=1、pos=2 的
MATCH 用 `[0,1)`、`[0,2)` 依次覆盖。最终 `aa` 胜出；而排在 pc14 之后的
裸 `a` 分支（pc7）在 pos=0 的 MATCH 发生时被 `free(runq,j+1)` 裁掉，从不
参与。这正对应 Perl 回溯里“先试 `(a)*` 贪心吃满”的结果，却是用队列顺序
+ 覆盖实现的，没有任何回溯栈。

### 2.4 实测前几轮线程集合

由 `NfaTracer`（反射驱动真实 `add/step`）在 `"aa\uD835\uDD4Fb"` 上观测，
cap 布局为 `[m0,m1, g1s,g1e, g2s,g2e, g3s,g3e]`。

pos=0，rune=`a`(0x61)，闭包后存活线程（仅消耗类指令，按队列序）：

| 线程 | 来源 | 入队时 cap |
| --- | --- | --- |
| pc4  | `(a)*` 循环体（ALT6.out 优先） | `[0,-1, 0,-1, 0,-1, -1,-1]` |
| pc11 | `(b?)` 的“匹配 b”体（ALT12.out） | `[0,-1, 0,0, -1,-1, 0,-1]` |
| pc14 | `(b?)` 跳过出口→MATCH（ALT12.arg） | `[0,-1, 0,0, -1,-1, 0,0]` |
| pc7  | 裸 `a`（ALT8.arg，最低优先） | `[0,-1, 0,-1, -1,-1, -1,-1]` |

注意 pc11 与 pc14 同时在队：`(b?)` 的两条路在同一位置闭包展开。`step`
按序处理：pc4 匹配 `a`，经 pc5(`cap5=1`)、pc6 闭包重新把 pc4 放入 nextq
（此时其 cap 为 `[0,-1, 0,-1, 1,1, -1,-1]`，group2 第一次迭代边界 [1,1]
在 **跨到 nextPos=1** 时写入）；pc11 不匹配 `a`；pc14 MATCH，提交
`[0,0,...]` 并裁掉后面的 pc7。

pos=1（第二个 `a`）：runq 存活为 `pc4, pc11, pc14`（pc7 已在 pos=0 被
裁剪）。pc4 再次匹配，cap 中 group2 变为 [2,2]，group1 仍开口；pc14 MATCH
覆盖为 `[0,1)`。

pos=2，rune=`U+1D54F`，**width=2**：pc4 要 `a`、pc11 要 `b`，均不匹配，
nextq 为空。外层循环下一轮 `runq.isEmpty()`：

- `(startCond & EMPTY_BEGIN_TEXT) != 0 && pos != 0` 成立
  （`Machine.java:245-248`），直接 break——**这就是可以停止搜索的时刻**：
  行首/文本首断言的程序一旦离开 pos=0 就不可能再有起点，无须扫到末尾。

最终 `matchcap = [0,2, 0,2, 1,2, 2,2]`：整体 `aa`、group1 `aa`、
group2 最后一次迭代 `a`（[1,2)）、group3 空（[2,2)），与公开
`Matcher.start/end/group` 完全一致（测试 `tracerAgreesWithPublicResult`、
`laterEndingGreedyBranchWins`、`captureSlotsFlowWithThreads` 固化）。

### 2.5 三种“起点寻找”路径

1. **anchored（`matches()` / `lookingAt()`）**：`Matcher.matches` 传
   `ANCHOR_BOTH`、`lookingAt` 传 `ANCHOR_START`（`Matcher.java:319/329`）。
   `match()` 开头对 `ANCHOR_START/BOTH` 且 `pos != 0` 直接返回 false
   （`Machine.java:221-223`）；seed 条件 `pos == 0 || anchor == UNANCHORED`
   （`Machine.java:271`）保证只在 pos=0 放线程；`ANCHOR_BOTH` 还要在每个
   MATCH 处检查 `atEnd`（`Machine.java:333-337`）。
2. **unanchored（`find()`）**：anchor=`UNANCHORED`，每个位置都可能 seed
   新起点；带 `EMPTY_BEGIN_TEXT` 前导条件的程序靠 §2.4 的 break 提前结束，
   普通程序则靠“已 matched + runq 排空”退出（`Machine.java:249-252`）；
   若 `ncap==0`（只要布尔答案）首个 MATCH 即可结束
   （`Machine.java:286-290`）。
3. **literal prefix 加速**：`RE2.compileImpl` 用 `Prog.prefix` 算出所有
   匹配必需的字面前缀及 `prefixComplete`、`prefixRune`
   （`RE2.java:190-199`）。runq 排空且尚未匹配、且前缀非空时，主循环用
   `in.index(re2, pos)` 一次跳到下一个前缀出现处
   （`Machine.java:253-268`）。UTF-16 输入最终走 `String.indexOf(String)`
   （`MachineInput.java:228-232`），UTF-8 走 `Utils.indexOf(byte[],...)`
   （`MachineInput.java:140`），跳过的位置根本不展开线程。注意触发判定用
   的是前瞻 rune `rune1 != prefixRune`，所以在“前缀首字符的前一位置”就会
   发起跳转；测试 `literalPrefixSkipsNonCandidatePositions` 用 `abc` 对
   `xxabc` 固化了“首批种子位置就是 2”这一行为。`^`/`\A` 前导的程序前缀
   为空（被 EMPTY_WIDTH 挡住），不吃这条加速。

---

## 3. Matcher：find 迭代、空匹配推进、group 边界与 replaceAll

### 3.1 find 的连续调用与空匹配推进

`Matcher.find()`（`Matcher.java:339`）是有状态迭代：

- 首次调用从 0 开始；之后以上一次匹配的 `groups[1]`（end）为新起点；
- 若上一次是空匹配（`groups[0] == groups[1]`），再 `start++` **nudge 一个
  UTF-16 code unit**（`Matcher.java:343-344`），保证可终止、且与 JDK 一致
  地在相邻位置产出空匹配。

实测 `a*` 在 `baab` 上的连续 find：`[0,0) "" → [1,3) "aa" → [3,3) "" →
[4,4) "" → false`（测试 `consecutiveFindNudgesPastEmptyMatch`）。
`find(int)` 会先 `reset()` 再从指定 UTF-16 偏移搜（`Matcher.java:356-363`）。

`genMatch`（`Matcher.java:367`）第一次只向引擎要 group 0（`ngroup=1`，
即 `ncap=2`），所以 find 本身不计算子组；`hasMatch=true, hasGroups=false`。

### 3.2 子组的惰性重算：loadGroup 与“多带一个字符”

首次调用 `start(g)/end(g)/group(g)`（g>0）触发 `loadGroup(g)`
（`Matcher.java:279`）：用 **相同锚点** `anchorFlag`、在
`[groups[0], min(groups[1]+1, inputLength)]` 区间内重跑一次引擎并要全部
group（`Matcher.java:297-302`）。多带一个字符是刻意的，注释给的例子
`(a)(b$)?(b)?` 对 `abc`：带上结尾 `c` 才能让 `$` 相关分支正确判定
（`Matcher.java:289-296`）。此后 `hasGroups=true` 复用结果。这意味着
capture 的真正来源仍是 Machine 的 cap slot 流转（§2.2），Matcher 只是
缓存与边界检查。

### 3.3 reset / 输入与“region”

- `reset()`（`Matcher.java:107`）清 `appendPos/hasMatch/hasGroups` 并重读
  `inputLength`；`reset(CharSequence)`（`Matcher.java:121`）连输入一起换。
  测试 `resetDiscardsMatchState` 固化 reset 后 find 重新从 0 开始。
- **当前 1.8 版本没有 `region(int,int)` API**：本仓库的 `Matcher.java`
  全文无 region 相关方法，搜索范围只能通过 `reset(input)` 换输入或
  `find(start)` 指定起点来间接限定（实现细节/与 JDK 的差异，非公开承诺）。
  引擎层 `RE2.match(MatcherInput,start,end,...)` 接受 end
  （`RE2.java:317`），但公开 Matcher 没有暴露它。

### 3.4 replaceAll 如何复用 match 结果

`Matcher.replaceAll` → `replace(repl, true)`（`Matcher.java:596`）：`reset()`
后循环 `while (find()) appendReplacement(...)`，最后 `appendTail`。
`appendReplacement`（`Matcher.java:467`）用 `start()/end()` 把
`[appendPos, start)` 的未匹配原文拷出，再解析 `$n`/`${name}`，这些引用
通过 `group(n)` 走 §3.2 的惰性重算；随后 `appendPos = end`。空匹配推进
完全复用 §3.1 的 find nudge，所以 `a*` replaceAll 到 `baab` 得到
`-b--b-`（同一测试固化），不会死循环。
Go 风格的底层另有 `RE2.replaceAllFunc`（`RE2.java:435`），它自己用
`doExecute` 迭代，并用 `input.step(searchPos)&0x7` 取“当前 rune 宽度”
保证至少前进一个 rune（`RE2.java:467-479`）；JDK 风格的 `Matcher.replace`
不走这条路径。

### 3.5 UTF-16 code unit vs rune：非 BMP 附近的 capture

Machine 消费的是 **rune（Unicode code point）**，但所有位置数字都是
输入游标单位。协调点只有一个：`MachineInput.step(pos)`
（`MachineInput.java:177-184`）对 UTF-16 输入用
`Character.codePointAt` + `Character.charCount`，返回 `rune<<3 | width`，
代理对的 `width=2`；主循环 `pos += width`（`Machine.java:292`）因此一次
跨过两个 UTF-16 单元。上下文 `context(pos)` 用
`codePointBefore/codePointAt`（`MachineInput.java:186-190`），`^/$/\b`
据此判定。

公开 `start()/end()` 暴露的正是这些游标位置，在 UTF-16 输入下即
**UTF-16 code unit 偏移**（公开语义，见 `Matcher.start/end`）。实测
`(.)` 对同一输入逐次 find：BMP 字符 `[0,1)`、`[1,2)`，代理对
U+1D54F 为 `[2,4)`（`end-start==2`，`group(1)` 是长度 2 的 Java
String），随后 `b` 为 `[4,5)`（测试
`runeWidthIsUtf16UnitsForSupplementaryCodePoint` 同时核对 tracer 的
`rune=0x1d54f, width=2`）。构造跨代理对边界的 capture（如 `(.).` 等）时，
边界永远落在 code unit 边界上（代理对两 char 之间不会被切开），因为
游标按 `width` 跳跃；UTF-8 输入路径同理，单位换成字节
（`UTF8Input.step`，`MachineInput.java:79-116`）。

### 3.6 POSIX / longest 开关在哪一层生效

- 语法层：`RE2.compilePOSIX`（`RE2.java:178`）用 `mode=POSIX(0)`
  解析（POSIX ERE 子集）并 `longest=true`；公开入口是
  `Pattern.LONGEST_MATCH`（`Pattern.java:49`），它只把 `compileImpl` 的
  `longest` 参数置真（`Pattern.java:155`），**不改变 Parser 的分支顺序**。
- 编译层：`longest` 不影响代码生成。实测 `a|aa` 在两种模式下 prog 完全
  相同（`alt -> 2, 3`，out 仍是第一分支），`Compiler` 不读 `longest`。
- 执行层（唯一生效处）：`Machine.step` 开头
  `if (longest && matched && ncap>0 && matchcap[0] < t.cap[0]) free(t)`
  （`Machine.java:321-324`）丢弃起点更晚的线程；MATCH 时只在
  `!longest || !matched || matchcap[1] < pos` 时才拷贝 cap
  （`Machine.java:338`），且 longest 模式 **不** 执行
  `free(runq,j+1)`（`Machine.java:342`），让同起点的其他分支继续跑完，
  保留结束位置最大的结果。

实测 `a|aa` 对 `xaa`：默认 leftmost-first 得 `[1,2)`（先分支），
LONGEST_MATCH 得 `[1,3)`（同起点最长），prog 相同——优先级变化完全发生在
Machine 的线程保留/覆盖策略上（测试
`longestFlagChangesPriorityInMachineOnly`）。另注意 Javadoc 明确说明
（`RE2.java:160-176`）：即便 longest，子组选择仍保留“回溯最先找到”的
次序，并非完整 POSIX 的逐级最长子匹配，这是 RE2 系的有意折中。

---

## 4. 线性时间的完整论证小结

- 程序大小固定（编译期）；每个输入位置有两个队列，sparse array 保证
  “同 pc 同位置至多一个线程”（`Machine.java:47`）。
- `add` 的 ε-闭包沿 ALT/EMPTY/CAPTURE/NOP 走到最近的消耗类指令；程序是
  DAG 式控制流（重复用回边 ALT 表示，但每个 pc 在一次闭包里被
  `contains` 去重），单轮闭包总工作量 O(程序指令数)。
- `step` 对 runq 每条存活线程 O(1)+一次闭包；每位置两轮队列，总成本
  O(n·|prog|)，空间 O(|prog| + numCap)，无指数级回溯。
- 字面前缀把“显然不可能开始”的区间用 `indexOf` 整段跳过
  （`Machine.java:253-268`），只减小常数/实际扫描量，不改变最坏界。
- ncap=0 的纯判定在首个 MATCH 即停（`Machine.java:286-290`）。

---

## 5. 观测点设计：为什么不是公开 API

`NfaTracer` 放在独立测试包 `com.google.re2j.trace`，仅被 JUnit 测试引用：

- 通过反射拿到包私有的 `RE2.prog/cond/prefix/longest`、构造
  `Machine(RE2)`，调用私有 `init/add/step/submatches`，并读
  `Queue.densePcs/denseThreads/size` 与 `Thread.cap`；
- 调度外壳仅镜像 `Machine.match` 的循环（rune 预读、队列交换、prefix
  跳转、EMPTY_BEGIN_TEXT 停止），**VM 语义全部来自生产方法**，不是第二套
  实现；
- 每轮在 `step` 之前/之后对 pc 列表和 cap 数组做深拷贝快照（池化线程会被
  原地复用，CAPTURE 闭包会原地改 slot，浅拷贝会读到后续轮次的数据）；
- 生产代码零改动，没有新增任何 public/protected 符号。

测试刻意只断言语义关键点而非日志全文：

- 最终整体 match 与三个 group 的 start/end/文本（与公开 API 双重对账）；
- 关键队列转移：pos=0 的 `[4,11,14,7]`、pos=1/2 的存活集合、pc7 在首个
  MATCH 后被裁剪；
- capture slot 在跨 rune 转移时的边界（group2 的 [1,1]、[2,2]）；
- 代理对的 `width=2` 与公开 start/end 的 UTF-16 单位；
- 前缀跳转首批种子位置、`\A` 程序 drain 后立即停止；
- find/空匹配 nudge/replaceAll、reset、longest 优先级。

---

## 6. 公开保证 vs 当前实现细节

**公开 API 保证（`Pattern`/`Matcher` Javadoc 层面）**

- leftmost-first 语义（`RE2.compile` Javadoc，`RE2.java:140-150`），
  `Pattern.LONGEST_MATCH` 切到 leftmost-longest；
- `Matcher.start/end` 为输入单位下的半开区间；对 Java `String` 即
  UTF-16 code unit；group 未参与匹配时 `start/end=-1`、`group()=null`；
- `find` 的左向迭代顺序与空匹配后前进一个字符；`appendReplacement` 的
  `$n/${name}` 替换规则；
- 线性时间（RE2 算法的根本设计目标，见类注释与 swtch 引用）。

**当前版本（1.8）实现细节，不应对外承诺**

- 指令集合与编号（`Inst` 的 11 种 op）、`RUNE1/RUNE_ANY*` 特化；
- sparse-array 队列、Thread 池、matchcap 覆盖与 `free(runq,j+1)` 的裁剪
  时机；capture slot 的编号/布局（bra=2k、ket=2k|1）；
- prefix 加速用前瞻 `rune1` 触发、底层用 `String.indexOf`；
- `MachineInput.step` 的 `rune<<3|width` 打包；
- 无 `Matcher.region` API；机器复用走 Treiber 栈（`RE2.get/put`）；
- `loadGroup` 的“多带一个字符”重算与 `hasGroups` 缓存。

---

## 7. 复现实验

```sh
# 需 JDK 8（Gradle 5.2 不支持 JDK 17+）：
export JAVA_HOME=/path/to/jdk8

./gradlew testClasses                 # 安装/编译
./gradlew test                        # 全量测试（含 NfaTraceTest 11 例）
./gradlew test --tests "com.google.re2j.trace.NfaTraceTest"
```

本仓库在 Apple Silicon 上用缓存的 Temurin JDK 8（x86_64，经 Rosetta）
验证：`testClasses` 与 `test` 均 BUILD SUCCESSFUL。
