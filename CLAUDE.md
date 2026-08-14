# 项目开发约束

## 技术栈
- 后端：Java 17 + Spring Boot 3.2 + MyBatis-Plus 3.5 + PostgreSQL 14/PostGIS 3
- 前端：Vue 3 + TypeScript + Vite 5 + Pinia + Element Plus + Cesium 1.115
- 数据准备：Python 3.10 + psycopg2

## 通用规则
- 每次任务只创建/修改我明确指定的文件，禁止重构无关代码
- 禁止自行新增依赖，需要新依赖时先向我确认
- 所有代码注释使用中文
- 新增或修改接口时，同步更新 docs/requirements.md 的接口清单章节

## Java 编码规约
- 写 Java / SQL / MyBatis 代码前必须阅读 @.claude/rules/java-alibaba.md（阿里巴巴 Java 开发手册·黄山版【强制】级条款），冲突时以该文件为准

## 后端约束
- Controller 统一返回 `R<T>`，禁止直接返回实体或 Map
- 分层严格：controller → service → mapper，Controller 禁止注入 Mapper
- entity 与 vo/dto 分离，entity 不出 service 层
- 异常统一由 GlobalExceptionHandler 处理，业务异常抛 BizException
- 空间字段用 PostGIS 函数处理，禁止在 Java 侧做几何运算

## 前端约束
- 一律 `<script setup lang="ts">`
- Cesium Viewer 单例管理，禁止在组件内直接 new Viewer
- 设备点位渲染必须用 Primitive API（BillboardCollection / LabelCollection），
  禁止 Entity 循环 add —— 设备数超 200 时 Entity 会导致逐帧重建卡顿
- 建筑点选用 scene.pick 获取 Cesium3DTileFeature，通过 setColor 高亮，
  禁止销毁重建 tileset
- 全局只注册一个 ScreenSpaceEventHandler，按 picked 对象类型分发
- API 请求统一走 src/api/request.ts 封装
