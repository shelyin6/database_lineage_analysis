# 参与贡献

感谢你有兴趣改进 SQL 元数据查看器。

## 开发环境设置

要求：

- JDK 17
- Maven 3.6+

本地运行：

```bash
mvn spring-boot:run
```

打包：

```bash
mvn -DskipTests package
```

前端以静态文件形式打包在 `src/main/resources/static` 下。运行时不需要公共 CDN。

## 创建拉取请求前

- 修改前端后运行 `node --check src/main/resources/static/app.js`。
- 修改 Java 或构建配置后运行 `mvn -DskipTests package`。
- 不要提交真实业务 SQL 文件、SQLite 数据库、生成的 jar 包、内部报告、密码、密钥或环境相关配置。
- 保持修改范围集中。将无关重构与功能或缺陷修复拉取请求分开。

## 编码指南

- 优先采用项目现有模式，而不是新增框架级抽象。
- 保守地解析 SQL：如果来源或数据流关系存在歧义，应留空，而不是返回误导性结果。
- 保持面向用户的文本清晰简洁。

## 报告问题

报告解析器缺陷时，请提供能够重现问题的最小 SQL 片段。分享前请移除业务标识和敏感逻辑。
