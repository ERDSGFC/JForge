# 发布到 Maven Central

本文档说明如何把 JForge（`io.github.erdsgfc`）发布到 Maven Central。

## 概览：两条路线

对于 `io.github.*` 这类 groupId，Maven Central 现在主推 **Central Portal**（central.sonatype.com，用 GitHub 账号登录即可自动验证命名空间）；经典 **OSSRH**（s01.oss.sonatype.org）仍可用。

- 本项目根 POM 当前配置的是 **OSSRH 路线**（`distributionManagement` 指向 `s01.oss.sonatype.org`）。
- 若改用 Central Portal，需替换发布插件（见下文"Central Portal 路线"）。

两条路线**二选一**，其余（GPG、source/javadoc、签名）相同。

---

## 一、前置准备

### 1. Sonatype 账号

**Central Portal（推荐）**：注册 https://central.sonatype.com，**用 GitHub 账号登录**。GitHub 用户名与 groupId 匹配（`io.github.erdsgfc`）时，命名空间自动验证，无需人工审批。登录后在 **User / Generate User Token** 生成 token（username + password 形式）。

**OSSRH（经典）**：到 https://issues.sonatype.org 创建申请 `io.github.erdsgfc` 的 ticket（历史流程，需人工审批）。

### 2. GPG 密钥

发布到 Central 的每个制品都必须有 GPG 签名。

```bash
# 生成密钥（RSA 4096，建议设置过期时间；牢记 passphrase）
gpg --full-generate-key

# 查看密钥 ID（8 位十六进制，如 ABCDEF12）
gpg --list-secret-keys --keyid-format=long

# 上传公钥到 keyserver（Central 校验签名时会查询）
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --keyserver keyserver.ubuntu.com --recv-keys <KEY_ID>   # 验证
```

> 若用 gpg-agent 管理 passphrase（推荐），发布时无需在配置里明文写 passphrase。

### 3. 本机环境

```bash
java -version    # JDK 25
mvn -version     # Maven 3.9+
gpg --version
```

---

## 二、本地配置 `~/.m2/settings.xml`

```xml
<settings>
  <servers>
    <!-- Central Portal token（用户名/密码 是 token，不是 Sonatype 账号密码） -->
    <server>
      <id>central</id>
      <username>TOKEN_USERNAME</username>
      <password>TOKEN_PASSWORD</password>
    </server>
    <!-- 经典 OSSRH 凭证（用中央仓库账号 + token） -->
    <server>
      <id>ossrh</id>
      <username>OSSRH_USERNAME</username>
      <password>OSSRH_TOKEN</password>
    </server>
  </servers>

  <profiles>
    <profile>
      <id>gpg</id>
      <properties>
        <gpg.keyname>YOUR_GPG_KEY_ID</gpg.keyname>
        <!-- 不用 gpg-agent 时才需要；用 gpg-agent 则删掉这行 -->
        <gpg.passphrase>YOUR_GPG_PASSPHRASE</gpg.passphrase>
      </properties>
    </profile>
  </profiles>
</settings>
```

`gpg.keyname` 对应 `gpg --list-secret-keys` 显示的密钥 ID。

---

## 三、项目配置（已就绪，核对即可）

根 POM 已配置好，发布前核对：

| 项 | 位置 | 核对点 |
|---|---|---|
| `<licenses>` | 根 POM | Apache-2.0 |
| `<developers>` | 根 POM | id/name = erdsgfc |
| `<scm>` | 根 POM | **GitHub 仓库地址是否与实际一致**（当前按 `github.com/erdsgfc/jforge` 填写） |
| `<distributionManagement>` | 根 POM | OSSRH s01 快照 + release 地址 |
| `release` profile | 根 POM | `mvn -Prelease deploy` 时附加 source/javadoc + GPG 签名 |
| 版本号 | 根 POM | 当前 `1.0-SNAPSHOT`，发布前改成正式版本 |

---

## 四、发布流程

### 路线 A：Central Portal（推荐，io.github.* 新项目默认）

**1. 改 pom 用 Central 发布插件**（替换 OSSRH 的 `distributionManagement` 路线）：

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.sonatype.central</groupId>
      <artifactId>central-publishing-maven-plugin</artifactId>
      <version>最新版（查 Maven 中央仓库）</version>
      <extensions>true</extensions>
      <configuration>
        <publishingServerId>central</publishingServerId>
        <autoPublish>true</autoPublish>   <!-- true=上传后自动发布；false=在 Portal UI 手动发布 -->
      </configuration>
    </plugin>
  </plugins>
</build>
```

> 同时删除或保留 OSSRH 的 `<distributionManagement>` 均可（Portal 插件不用它）。

**2. 执行发布：**

```bash
# 版本号去掉 -SNAPSHOT（发布正式版时）
mvn versions:set -DnewVersion=1.0.0

# 本地全量验证（编译 + 测试 + 打包）
mvn clean verify

# 上传到 Portal 并（autoPublish=true 时）自动发布
mvn deploy
```

**3. 若 `autoPublish=false`**：登录 https://central.sonatype.com → Deployments → 找到该次部署 → **Publish**。

### 路线 B：经典 OSSRH（当前 pom 配置）

**1. 执行发布：**

```bash
# 版本号去掉 -SNAPSHOT
mvn versions:set -DnewVersion=1.0.0

# 本地全量验证
mvn clean verify

# 签名并上传到 OSSRH staging
mvn -Prelease deploy
```

**2. 在 Nexus UI 发布 staging：**

1. 登录 https://s01.oss.sonatype.org （用 Sonatype 账号）
2. 左侧 **Staging Repositories**，找到本次上传的 staging repo
3. 先 **Close**（校验签名/坐标/完整性），校验失败则查看日志修复后重来
4. 校验通过后 **Release** —— 制品进入 Maven Central

> 想自动化 Close/Release 可加 `nexus-staging-maven-plugin`，这里不赘述。

---

## 五、验证发布

- 发布后 10 分钟 ~ 2 小时，制品在中央仓库出现：
  - 搜索：https://search.maven.org/ 或 https://central.sonatype.com/artifact/io.github.erdsgfc/jforge-core
- 校验制品完整性（本地下载验证）：
  ```bash
  mvn dependency:get -Dartifact=io.github.erdsgfc:jforge-core:1.0.0
  ```
- 每个制品应有：主 jar + sources jar + javadoc jar + `.asc` 签名文件 + `.pom`。

---

## 六、常见问题

| 问题 | 原因 / 处理 |
|---|---|
| `401 Unauthorized` | `settings.xml` 里 server 的 id（`central`/`ossrh`）与 pom/插件里不一致，或 token 过期 |
| `Missing GPG signature` | 公钥未上传 keyserver，或 `gpg.keyname` 配错 |
| `Javadoc 报错导致失败` | JDK 25 的 javadoc 较严格；修 `@link`/`@param` 或调 javadoc 插件配置 |
| `SCM 信息不符` | Central 校验 `pom.xml` 的 `<scm>` 与仓库一致；更新为实际地址 |
| 发布后搜索不到 | 等待同步（最慢数小时）；确认 staging 已 Release/Publish |
| `io.github.erdsgfc` 命名空间未验证 | 用 GitHub 账号登录 Central Portal 重新关联 |

---

## 版本号约定

- 开发中：`1.0-SNAPSHOT`（根 POM）
- 发布：`mvn versions:set -DnewVersion=X.Y.Z`，发布后 `versions:set -DnewVersion=X.Y.(Z+1)-SNAPSHOT` 回到开发态
- 版本规范遵循 [SemVer](https://semver.org/lang/zh-CN/)
