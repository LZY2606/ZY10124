# RE2/J 一次匹配的端到端追踪

本文基于仓库当前源码（`java/com/google/re2j`，re2j 1.8），用一个最小表达式追踪一次真实匹配，
说明 RE2/J 如何在**不回溯**的前提下得到 leftmost-first 语义、捕获组与零宽断言。文中所有
指令号、队列内容、capture slot 值均由包内测试 helper `TraceDump` 驱动真实 `Machine.match`
得到（见 `javatests/com/google/re2j/TraceDump.java`、`TraceTest.java`），不是按回溯引擎
类比推测的。

> 公开保证 vs 实现细节：`Pattern`/`Matcher` 的 API 语义（leftmost-first、捕获索引、UTF-16
> 单位、`Pattern.LONGEST_MATCH`）是公开行为；指令编号、线程在 `Queue` 中的物理排列、
> `Machine`/`Prog` 等类本身及本文新增的 `Machine.Tracer` 观测点都属于实现细节，可能在版本
> 间变化。

## 1. 最小表达式与输入

表达式（同时含 alternation、greedy repetition、可空分支、行首断言、两个捕获组）：

```
(?m)^(a|()b)a*c
```

- `(?m)` 在 `Parser` 中清掉 `RE2.ONE_LINE`（`Parser.java` 对 `m` 的处理），于是 `^`
  编译成 `BEGIN_LINE`（`Parser.parseInternal` 的 `case '^'`）：文本开头或 `\n` 之后都成立。
- `$` 不出现；`$` 的 WAS_DOLLAR 细节与本例无关。
- `$1 = (a|()b)`：两个分支，第二分支 `()` 是**可空**分支；`$2 = ()` 恒匹配空串。
- `a*` 是 greedy STAR。

输入（Java String，索引是 UTF-16 code unit）：

```
"x\uD83D\uDE00a\nac"
```

| index | 0 | 1    | 2    | 3 | 4  | 5 | 6 |
|-------|---|------|------|---|----|---|---|
| unit  | x | high | low  | a | \n | a | c |

`U+1F600`（代理对）占据索引 1–2 两个 code unit；行首位置为 0 与 5。

## 2. Parser → Simplify → Compiler → Prog 的实际产物

- `Parser.parse(expr, RE2.PERL)`（经 `RE2.compileImpl`）产出 `Regexp` AST：
  `CONCAT[BEGIN_LINE, CAPTURE(1, ALTERNATE[LITERAL"a", CAPTURE(2, EMPTY)]), STAR(LITERAL"a"),
  LITERAL"c"]`。捕获编号来自 `Parser.numCap`，开括号在 `parseLeftParen` 分配，闭括号在
  `parseRightParen` 把 `LEFT_PAREN` 伪节点改成 `CAPTURE`。
- `Simplify.simplify` 本例不改变结构（无 counted repetition）。
- `Compiler.compileRegexp` 把 AST 编成 Thompson 片段（patchlist 拼接，见 `Prog`），
  0 号固定为 `fail`，末尾 append 一个 `match`，`prog.start` 指向开头。
- `RE2` 构造后计算 `cond = prog.startCond()`（沿 NOP/CAPTURE 收集开头的 EMPTY 条件）和
  literal prefix（`prog.prefix`）。本例 `cond = EMPTY_BEGIN_LINE(0x1)`、`prefix = ""`。

`re2.prog.toString()` 打印出的实际程序（`*` 标记 start）：

```
0       fail
1*      empty 1 -> 2        // EMPTY_WIDTH, arg=EMPTY_BEGIN_LINE（即 ^）
2       cap 2 -> 8          // CAPTURE arg=2：$1 的开括号 bra（slot 2）
3       rune1 "a" -> 9      // 分支1：a
4       cap 4 -> 5          // $2 的 bra（slot 4）
5       nop -> 6            // () 空体化简为 NOP
6       cap 5 -> 7          // $2 的 ket（slot 5）
7       rune1 "b" -> 9      // 分支2：b
8       alt -> 3, 4         // 先 out=3（分支1），后 arg=4（分支2）
9       cap 3 -> 11         // $1 的 ket（slot 3）
10      rune1 "a" -> 11     // a* 循环体
11      alt -> 10, 12       // greedy：out=10（再吃一个 a），arg=12（退出）
12      rune1 "c" -> 13
13      match
```

capture slot 布局：`[s0,e0, s1,e1, s2,e2]` 共 `prog.numCap = 6` 个；slot 0/1 是整匹配
（`Prog.numCap` 初始为 2）。`Compiler.cap(arg)` 用 `cap<<1` / `cap<<1|1` 发 bra/ket，
因此 `$1` 开/闭是 slot 2/3，`$2` 是 4/5。

指令优先级**直接编码在控制流里**：
- `ALT.out` 是优先边，`ALT.arg` 是次选边。`Compiler.alt/quest/loop` 对 greedy 与
  nongreedy 交换这两条边（nongreedy 时把 body 放 `arg`），这就是"贪婪/懒惰"的全部来源——
  运行时没有回溯，也没有"先试哪个再回退"的概念。
- `a*` 的 `alt 11 -> 10, 12`：填充队列时**先**加入"继续吃 a"的线程，**再**加入"退出吃
  c"的线程；两边都活到汇合点时，先到的线程在稀疏集合去重中获胜（见第 4 节）。
- 可空 body 的 star 被 `Compiler.star` 改写成 `(x+)?` 以保持优先级正确（本例 body 不可空，
  直接是 loop）。

## 3. Machine：线程、稀疏队列与一个 round

`Machine.match(MachineInput, pos, anchor)`（`Machine.java`）维护两条 `Queue`（`q0/q1`），
交替充当 `runq`/`nextq`。`Queue` 是稀疏集合（`sparse[pc]` + `densePcs/denseThreads`，
注释引用 Russ Cox 的 uninitialized-memory 文章）：

- `contains(pc)` O(1) 判重，`add(pc)` 追加到 dense 尾部。因此"同一位置、同一 pc 只保留一个
  线程"，而且**保留的是第一次加入的那个**（优先级最高的）。
- 每个真正吃字符的线程是一个 `Thread{Inst inst, int[] cap}`；NOP/CAPTURE/EMPTY/ALT 只是
  递归 `add` 展开，不产生线程对象。
- 线程对象从 `pool` 栈复用；一轮结束后 `free(runq)` 归还。

每个输入位置一个 round：

1. **注入 start 线程**（unanchored 时每个位置都注入；anchored 只在 pos==0）：
   `add(runq, prog.start, pos, matchcap, flag, null)`，其中 `matchcap[0]=pos`。
2. **闭包展开** `add`：沿 `ALT.out` 再 `ALT.arg` 递归；`EMPTY_WIDTH` 检查
   `(inst.arg & ~cond)==0`（want 位必须是当前上下文 have 的子集），不满足就**剪掉**；
   `CAPTURE` 临时写 `cap[arg]=pos`、递归展开、再恢复旧值；直到遇到 rune/MATCH 才 `alloc`
   并把带 **cap 快照副本**的线程 `park` 进队列。
3. **消费** `step(runq,nextq,...)`：逐个 rune 线程尝试 `matchRune(c)`（`RUNE1` 直接比较，
   `Inst.matchRune` 处理多区间类）。成功则在 `nextq` 对 `i.out` 再做一次 `add`（带下一位置
   的上下文 `nextCond`）。`MATCH` 线程命中时写 `t.cap[1]=pos`、`arraycopy` 进 `matchcap`；
   leftmost-first（`re2.longest==false`）下立刻 `free(runq, j+1)` 丢掉队尾其余线程并
   `matched=true`。
4. 交换两队列，游标前进 `width` 个**输入单位**。

线性时间的来源：每条线程每个位置最多入队一次（`contains` 去重），程序指令数为常数，所以
每位置 O(|prog|)，总计 O(|prog|·n)；capture 数组复制是 O(numCap)，与模式大小同阶。没有任何
"换一个分支重跑后缀"的回溯动作。

### 零宽 flags 从哪来

`Utils.emptyOpContext(r1, r2)`（`Utils.java`）根据位置两侧 rune 计算位掩码：r1<0 给
`BEGIN_TEXT|BEGIN_LINE`，r1=='\n' 给 `BEGIN_LINE`，r2<0 给 `END_TEXT|END_LINE`，
r2=='\n' 给 `END_LINE`，并按 ASCII `isWordRune` 给出 `WORD_BOUNDARY`/`NO_WORD_BOUNDARY`。
每轮开始 `flag = (pos==0 ? emptyOpContext(-1,rune) : in.context(pos))`，字符跨过后用
`in.context(nextPos)`。`EMPTY_WIDTH` 指令的 arg（如 `0x1`）必须是当前 flags 的子集，这正是
trace 里 pos=1/3/4/6/7 的 `emptyRejected pc=1 want=0x1` 与 pos=5（`\n` 之后）放行的原因。

### capture slot 的实际流转

- 展开闭包经过 `CAPTURE` 时改的是**当前线程共享的 cap 工作数组**，递归返回即恢复
  （`Machine.add` 的 `opos = cap[arg]; ...; cap[arg]=opos`）。
- 线程在 rune/MATCH 处 `park` 时把 `cap` **复制**进该线程（`System.arraycopy`），所以不同
  分支的线程各自携带自己的边界，互不可见。
- 消费后 `add(...,"after-rune")` 到达 ket（如 pc 9）时把 `slot3=nextPos` 写进**该分支的**
  副本再 park。
- 只有到达 `MATCH` 的那份快照最终被拷进 `matchcap` 返回；未参与的组保持初始化的 -1。

## 4. 前几轮线程集合（真实 trace 摘录）

下面是 `TraceDump.run("(?m)^(a|()b)a*c", "x\uD83D\uDE00a\nac", 0, UNANCHORED, false)`
的事件流整理。记队列中线程为 `pc@(cap)`，`-` 代表已被稀疏集合占洞的槽。

**pos=0（start 注入 + 闭包）**，`runq=q0`：
- `visit 1(start) → 2(empty 通过) → cap2=0 → 8(alt)`
- alt-out：`3 rune a` park，cap=`[0,-1,0,-1,-1,-1]`
- alt-arg：`cap4=0 → 5 nop → 6 cap5=0 → 7 rune b` park，cap=`[0,-1,0,-1,0,0]`
- round 0 消费 `'x'`：3、7 都不匹配 → `nextq=[]`。

**pos=1（代理对高半区）**：新 start 线程展开到 pc1 时 `EMPTY_BEGIN_LINE` 不成立
（`emptyRejected want=0x1`），runq 空。round 1 消费整个 rune `U+1F600`：
`MachineInput.UTF16Input.step` 用 `Character.codePointAt` + `Character.charCount`，
返回 `rune<<3 | 2`，所以 `width=2`，**一轮直接到 nextPos=3**（不是 2）。

**pos=3（'a'）**、**pos=4（'\n'）**：start 的 `^` 都被拒绝；两个位置各消耗一个 rune，
无存活线程。注意 pos=4 处 `in.context` 把"下一位置 5"标记为 BEGIN_LINE，但 pos=4 本身不是
行首，所以当轮 start 仍失败。

**pos=5（'\n' 之后，行首）**，`runq=q0`：`^` 放行，闭包同 pos=0：
- park `pc=3` cap=`[5,-1,5,-1,-1,-1]`
- park `pc=7` cap=`[5,-1,5,-1,5,5]`（可空 `$2` 的两个 slot 都暂记 5）

**round 4，消费 pos5 的 'a'，到 nextPos=6，`nextq=q1`**：
- pc3 吃 'a' → `9(cap3=6) → 11 alt`：alt-out 先 park `pc=10`，alt-arg 后 park `pc=12`，
  两者 cap 都是 `[5,-1,5,6,-1,-1]`。注意此刻 slot4/5 为 -1：park 用的是分支1线程的 cap
  副本，与上面 pc7 线程的 `$2=5` 互不相干。
- pc7 要 'b'，输入 'a' → 死亡（可空分支 `$2` 的线程**在分支体 'b' 处整体消失**，空组的
  临时写入随之丢弃；这解释了最终 `$2=-1`）。
- 物理 dense 布局：`[-,-,pc10,pc12]`（前两个槽来自尚未占洞的 add 序列，无线程对象）。

**round 5，消费 pos6 的 'c'，到 nextPos=7**：pc10 要 'a' 死亡；pc12 吃 'c' →
`13 match` park，cap=`[5,-1,5,6,-1,-1]`。

**round 6，pos=7，rune=EOF（width=0）**：队列里 MATCH 线程执行：
`t.cap[1]=7` → matchWin cap=`[5,7,5,6,-1,-1]`，`free(runq,1)` 发出 `pruneTail from=1`
（同位置后注入的 start 线程等被丢弃），主循环 `matched` 后 break。

结果：整匹配 `[5,7)="ac"`，`$1=[5,6)="a"`，`$2` 未参与（-1，公开 API 上
`group(2)==null`）。

### 为什么"较晚结束的分支"仍可能胜出 / 何时可以停止

- 引擎**并行**持有所有活分支，先死的分支不影响仍在跑的其他分支；优先级只在两点生效：
  (a) 同一位置同一 pc 的汇合去重保留先加入者；(b) MATCH 在 leftmost-first 下立即剪尾。
- 经典例子 `^(a|ab)c` 打 `"abc"`（trace 见 `TraceTest.threadQueueDedupKeepsFirstPriority`）：
  吃完 'a' 到 pos1，alt 展开让**分支1 的延续 pc8**先入队、**分支2 的 body pc5**后入队；
  pos1 吃 'b' 时 pc8 要 'c' 死亡，pc5 吃 'b' 继续，最终它到 MATCH。"结束得晚"的分支2
  之所以胜出，是因为分支1 在 'b' 处已死——而不是因为引擎回退去重试分支2。
- 可以停止搜索的判据都在 `Machine.match` 的循环里：
  1. runq 空且 `startCond` 含 `EMPTY_BEGIN_TEXT` 且 pos≠0（错过了唯一锚点）；
  2. runq 空且已经 `matched`（同起点的替代都探索完了）；
  3. runq 空、有 literal prefix 且下一个 rune≠`prefixRune`：调 `in.index` 找前缀，找不到
     就 break；
  4. `ncap==0 && matched`（只要有没有匹配，不要边界）；
  5. 当前 rune width==0（EOF 轮）处理完即 break。

## 5. anchored / unanchored / literal prefix 的起点寻找路径

- **anchored start / both**：`RE2.match(...,anchor,...)` → `doExecute` → `Machine.match`
  开头即检查 `(anchor==ANCHOR_START||ANCHOR_BOTH) && pos!=0 → return false`；主循环只在
  `pos==0` 注入 start 线程（`if (!matched && (pos==0 || anchor==UNANCHORED))`）。
  `ANCHOR_BOTH` 额外在 `step` 的 MATCH 分支要求 `atEnd`。
- **unanchored**：**每个**游标位置都注入一次 start 线程（同一段代码的 `UNANCHORED`
  分支），即朴素的"逐位置尝试"；`EMPTY_BEGIN_TEXT/LINE` 等指令在闭包里把不合要求的位置
  剪掉（本例 `^` 只让 0、5 通过）。
- **literal prefix 加速**：`RE2.compileImpl` 用 `Prog.prefix` 沿 start 的 NOP/CAPTURE 与
  单 rune `RUNE1`（且非 fold-case）链收集必需前缀，算出 `prefix/prefixRune/prefixComplete`。
  加速只发生在 `runq.isEmpty()`（当前位置所有线程都死了）且 `rune1 != prefixRune` 时：
  `UTF16Input.index` 直接委托 `String.indexOf(prefix,pos)`（UTF-8 路径是
  `Utils.indexOf(byte[],...)`），跳过中间位置。trace 中 `fooa?c` 打 `"zzzzfooc"` 的第一条
  事件就是 `prefixSkip pos=0 +4`：0..3 连起始线程都不注入，直接从 4 开始跑。
  若 `prefixComplete`（前缀即整个正则），匹配判定进一步退化（见 RE2 相关短路路径）。

## 6. Matcher：连续 find、空匹配推进、reset、group 装载、replaceAll

`Matcher`（`Matcher.java`）是有状态游标，所有匹配经 `genMatch → RE2.match → doExecute`。

- **连续 `find()`**：无参 `find` 以 `groups[1]`（上次结束位置）为新起点；上次是空匹配
  （`groups[0]==groups[1]`）时 **nudge：`start++`**（一个 UTF-16 code unit，见
  `MatcherTraceApiTest.emptyNudgeAdvancesOneUtf16UnitAcrossSurrogatePair`：对
  `"x\uD83D\uDE00y"` 上的 `a?` 空匹配，find 依次落在 0,1,2,3,4，中间可以落在代理对内部；
  Machine 的 `step` 仍按整 rune 读取，落在高代理项位置时 `codePointAt` 照样读出整个
  rune）。
- **genMatch 只要整体边界**：以 `ngroup=1` 调 `RE2.match`，`doExecute(...,2)` 只追踪 2 个
  slot；`hasGroups=false`。
- **group 延迟装载**：`start(g)/end(g)/group(g)` 触发 `loadGroup`，必要时**重新执行一次**
  完整捕获匹配（`pattern.re2().match(...,groups[0], groups[1]+1, anchorFlag, groups,
  1+groupCount)`），end 多带一个字符以处理可选组边界；结果缓存在 `groups[]`，
  `hasGroups=true`。这就是"find 便宜、问子组才付全 capture 成本"的复用方式。
- **reset / reset(input)**：清 `appendPos/hasMatch/hasGroups`、重设 `inputLength`；
  `reset(byte[])` 走 UTF-8 输入，`substring` 按字节切再按 UTF-8 解码。
- **replaceAll/replaceFirst**：`Matcher.replace` 就是 `reset(); while(find())
  appendReplacement(...); appendTail(...)`，完全复用 find 的 `groups[]`；`$n`/`${name}`
  在 `appendReplacementInternal` 里再调 `group(n)`（进而可能触发 loadGroup 重跑）。
  行为差异值得注意：re2j **不**像 JDK 那样压掉与非空匹配相邻的空匹配，`a*` 打 `"ab"`
  replaceAll("X") 得到 `"XXbX"`（[0,1) 的 "a"、[1,1) 与 [2,2) 的空匹配都产出替换）；
  底层 Go 风格 `RE2.allMatches` 则用 `prevMatchEnd` 忽略紧邻的空匹配（另一套 API）。

## 7. 代理对与公开 start/end 的单位

- 工作引擎里位置的单位由 `MachineInput` 决定。`UTF16Input.step(pos)`：
  `rune=Character.codePointAt(str,pos)`，`width=Character.charCount(rune)`（BMP=1，
  增补平面=2），打包成 `rune<<3|width`；`context` 用 `codePointBefore/codePointAt`。
  因此游标以 UTF-16 code unit 为步长，但每个 round 消费的是**整个 rune**。
- capture slot 存的就是这个游标值，故对 `CharSequence` 输入它们等于 Java String 索引。
  `Matcher.start/end` 直接返回 `groups[2g]/groups[2g+1]`，无任何换算——单位是 **UTF-16
  code unit**。测试 `captureBoundariesAreUtf16CodeUnits`：模式 `(\uD83D\uDE00+)` 打
  `"a\uD83D\uDE00b"`，`start(1)=1, end(1)=3`（跨度 2 个 unit、1 个 code point）。
- UTF-8 输入（`Matcher.reset(byte[])` 或 Go 风格 API）下 slot 是**字节**偏移；`Matcher`
  的 `substring` 对 UTF-8 走 `new String(bytes,start,len,UTF_8)`。同一份 Machine 代码，
  单位随 `MachineInput` 实现而变。

## 8. POSIX / longest 开关在哪一层改优先级

- 语法层：`RE2.compile(expr)` 用 `PERL` 标志；`RE2.compilePOSIX(expr)` 用
  `mode=POSIX(=0)`、`longest=true`。POSIX 模式没有 `PERL_X`，所以 `(?:...)`、`*?`、
  `(?i)` 等语法直接解析失败（`TraceTest` 里 longest 用例必须写 `(a+)|(a+ b+)` 而不是
  `(?:...)`）。公开 API 的 `Pattern.LONGEST_MATCH` 仍走 PERL 语法，只把 `longest`
  置位（`Pattern.compile` → `RE2.compileImpl(..., (flags&LONGEST_MATCH)!=0)`）。
- **Parser/Simplify/Compiler 对两种模式生成完全相同的指令**：优先级边的布局不变，
  `Prog` 也不变。变化只发生在 `RE2.longest` 这个布尔，并且只在 **`Machine`** 里被读取
  （全仓库 `longest` 的运行时使用点只有 `Machine.step`/`match`）：
  1. `step` 开头：`if (longest && matched && ncap>0 && matchcap[0] < t.cap[0]) free(t)`
     ——已找到更左起点的匹配后，起点更靠右的线程直接丢弃（leftmost 仍优先）；
  2. MATCH 处：只有 `!longest || !matched || matchcap[1] < pos` 才拷贝结果，即同起点下
     **结束位置更长才替换**；
  3. MATCH 处**不**做 `free(runq,j+1)` 剪尾：所有同起点候选继续跑完，因此 trace 里
     longest 用例会出现多次 `matchWin(replace=true)`（`"aaa"`→`"aaa "`→…→`"aaa bbb"`），
     且没有任何 `pruneTail`。
- 它不是"换一种正则语法"或"在编译器里重排分支"，而是执行层的两条策略差异：
  结果选择（最长 end）+ 不提前剪枝。子表达式内部的并列平局仍按线程顺序（first）决定，
  这正是 `RE2.compilePOSIX` Javadoc 所说与严格 POSIX 子表达式最长规则的偏离。

## 9. 观测点、helper 与测试

- 生产代码只增加了一个**包私有**抽象类 `Machine.Tracer`（全部空默认方法）、`Machine`
  上的包私有 `setTracer`，以及 `Queue.id` 和若干被 `if (tracer != null)` 守卫的调用点
  （`Machine.java`）。没有新增任何公开方法/字段；默认（tracer==null）行为与开销仅有一次
  空指针判断。线程进入池化缓存的正常路径不受影响；helper 显式 `new Machine(re2)` 绕过
  `RE2.get()/put()` 的 Treiber 栈来安装 tracer。
- `TraceDump`（`javatests`，包私有 final）把事件收敛成不可变值对象
  `TraceDump.Event`（数组字段全部防御性拷贝），`Trace.render()` 仅供人工查看。
- `TraceTest` 断言的是**语义事件**而非整段日志：最终 matchcap、`^` 被拒位置集合、
  pos5 park 的 pc 顺序 [3,7] 与 slot[4,5]、a* 轮 nextq 的活跃 pc [10,12]、ket slot3=6、
  `^(a|ab)c` 的 [8,5] 入队序与无剪尾、matchWin 后的 `pruneTail(from=1)`、prefix
  `+4` 跳转、longest 下多次替换且无剪尾、代理对轮 nextPos=3。
- `MatcherTraceApiTest` 覆盖公开面：连续 find 与空匹配 nudge、代理对中 nudge、
  `start/end` 的 UTF-16 单位、`group(2)=null`/start=-1、`reset()` 与 `reset(input)`、
  `replaceAll` 对 `$1` 的复用与空匹配行为、`Pattern.LONGEST_MATCH` 的公开结果。

构建与运行（需 JDK 8；仓库 wrapper 为 Gradle 5.2）：

```
./gradlew testClasses
./gradlew test --tests "com.google.re2j.TraceTest" --tests "com.google.re2j.MatcherTraceApiTest"
./gradlew test          # 除基线即失败的 GWTTest（GWT2.9 在当前 JDK/架构下的环境问题）外全绿
```
