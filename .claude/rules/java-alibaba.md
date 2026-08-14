# Java 编码规约（阿里巴巴 Java 开发手册·黄山版）

以下为【强制】级条款，写代码和 review 时必须遵守；有冲突时以本文件为准。

## 命名

- 类名 UpperCamelCase；方法名/参数名/成员变量/局部变量 lowerCamelCase；常量全大写下划线分隔，不超过 5 个单词。
- 抽象类以 Abstract/Base 开头，异常类以 Exception 结尾，测试类以被测类名 + Test 结尾。
- 类型与中括号紧挨表示数组：`int[] arr`，禁止 `int arr[]`。
- POJO 布尔属性禁止加 is 前缀（如 `deleted` 而非 `isDeleted`），否则部分序列化框架取不到属性。
- 包名全小写单数形式；禁止拼音与英文混用（除 alibaba、hangzhou 等国际通用词）。
- 接口类中的方法和属性不加任何修饰符，不定义变量；Service/DAO 的实现类以 Impl 结尾。
- 枚举类名带 Enum 后缀，枚举成员全大写下划线分隔。

## 常量与变量

- 禁止魔法值（未定义的数字/字符串）直接出现在代码中，不允许任何未经预定义的常量直接出现。
- 共享常量按功能分类，不要一个 Constants 类装所有。
- long 赋值使用大写 L（`2L`），禁止小写 l。

## 集合

- 判断集合非空用 CollectionUtils.isEmpty/isNotEmpty，禁止 `size() == 0` 之外自行拼写判空逻辑遗漏 null。
- Map 遍历取 key+value 用 entrySet()，禁止 keySet() 再 get()；JDK8 优先 Map.forEach。
- ArrayList.subList 结果是视图，禁止对其做 ArrayList 强转。
- 使用 `Collection.toArray(new T[0])` 转数组；禁止无参 toArray() 后强转。
- `Arrays.asList()` 返回定长列表，禁止对其 add/remove/clear。
- 集合初始化时指定初始容量（如 `new HashMap<>(16)`），避免多次扩容。
- 禁止在 foreach 中对元素做 remove/add，删除请用 Iterator.remove() 或并发场景加锁。

## 并发

- 线程池禁止使用 Executors 创建，必须通过 ThreadPoolExecutor 显式指定核心/最大线程数、队列、拒绝策略、线程工厂（含有意义的线程名）。
- 线程资源必须由线程池提供，不允许显式 new Thread()。
- SimpleDateFormat 是线程不安全的，使用 DateTimeFormatter（JDK8+）。
- ThreadLocal 必须在 finally 中 remove()，防止内存泄漏。
- 加锁顺序保持一致，避免死锁；先获取锁再 try，在 finally 中释放。

## 异常与日志

- 禁止 catch Exception 后不做任何处理（空 catch 块）；至少记录日志或明确注释原因。
- 禁止用异常做流程控制；能用 if 判断的（如 NumberFormatException）提前校验。
- finally 块中禁止 return。
- 使用 SLF4J 的 LoggerFactory，占位符方式打印：`log.info("id={}, name={}", id, name)`，禁止字符串拼接。
- 异常日志必须打印堆栈：`log.error("xxx failed, param={}", param, e)`。
- 日志文件保存 15 天以上；禁止用 System.out.println 输出。
- 对外提供的方法必须做参数校验（NPE、边界、格式），内部高频调用方法可省略但需注释说明。

## 对象与判空

- 所有包装类对象之间值的比较使用 equals，禁止 `==`；Integer 缓存区间为 -128~127。
- 常量或确定值放在 equals 左边：`"test".equals(str)`；或使用 `Objects.equals(a, b)`。
- POJO 类所有属性使用包装类型，RPC 返回值和参数使用包装类型，局部变量使用基本类型。
- 浮点数比较禁止用 `==`；金额计算使用 BigDecimal，且必须用 String 构造函数创建：`new BigDecimal("0.1")`。
- POJO 类必须重写 toString；继承时用 super.toString()。

## MySQL / MyBatis

- 表名、字段名全小写下划线分隔；表必备三字段：id、gmt_create、gmt_modified（或项目统一的 create_time/update_time，以现有 schema 为准）。
- 表达是否概念的字段用 is_xxx，类型 unsigned tinyint。
- 禁止使用 SELECT *，必须显式列出字段。
- 禁止使用外键与级联，一律在应用层解决。
- 超过三个表禁止 join；join 字段必须类型一致且建有索引。
- count(*) 用于统计行数，不要用 count(列名) 替代。
- 分页查询若 count 为 0，直接返回，不再执行后续分页语句。
- MyBatis 中 `#{}` 用于参数绑定，`${}` 仅用于表名等且必须白名单校验（防注入）。
- resultMap 显式配置字段映射，不依赖隐式驼峰转换的边界情况。

## 工程与分层

- 分层：Controller（参数校验、组装 VO）→ Service（业务编排，@Transactional 只在此层）→ Manager（通用能力/第三方封装）→ DAO（单表操作）。
- DO / DTO / VO / Query 分开定义，禁止跨层直接传递 DO 到前端。
- 事务方法内禁止调用远程 RPC / 发消息 / 大循环；事务粒度尽量小。
- 依赖注入使用构造器注入或 @Resource，避免字段上的 @Autowired（便于测试与不可变）。
- 服务端返回统一 Result 结构，错误码集中管理，禁止直接抛原始异常给前端。

## 格式

- 缩进 4 个空格，禁止 Tab（或统一为 Tab 转 4 空格）。
- 单行不超过 120 字符。
- 方法体内空行分隔逻辑块；左大括号不换行，右大括号独占一行。
- if/for/while/switch 必须使用大括号，禁止单行省略。
