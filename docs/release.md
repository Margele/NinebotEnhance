# 发布说明

Ninebot Enhance 的首个发布版本为 **1.0.0**，Git 发布标签为 `v1.0.0`。

## 版本与签名

- 项目名：`Ninebot Enhance`。
- 包名：`dev.ichinomiya.ninebotenhance`。
- `version.properties` 与 `ipc/Protocol.java` 中的版本名称、版本代码必须一致，构建脚本会自动核对。
- 当前发布版本为 `1.1.13`，Android `versionCode=51`，使用原签名覆盖安装；改动见 `CHANGELOG.md`。
- 发布使用固定签名，公开 SHA-256 指纹保存在 `release-signing-certificate.txt`。
- 私钥与密码保存在 Git 忽略的 `signing/`，后续更新继续使用同一密钥。

## 构建与检查

配置 JDK、Android SDK 后，在项目根目录执行：

```sh
python scripts/build.py --jdk "$JAVA_HOME" --sdk "$ANDROID_HOME"
./gradlew :app:assembleDebug --offline
```

PowerShell 使用 `$env:JAVA_HOME`、`$env:ANDROID_HOME` 和 `gradlew.bat`。Gradle 离线构建要求本机已有对应 Gradle 和 AGP 缓存；首次构建可去掉 `--offline`。

发布脚本使用本机 SDK 和仓库固定依赖，无需在线解析 Maven 依赖；它执行 Java 主机测试、资源及 DEX 编译、签名、对齐，以及 APK 身份、版本、Xposed 作用域、必要类和第三方许可证检查。主机测试覆盖几何与输入边界、授权和会话生命周期、车辆启动条件、编码统计与采集调度。

设备验证应分别关注车辆投屏、本地模拟，以及实际使用的授权后端、息屏保持、应用恢复、输入法和预览操作。“无（投屏）”还需验证系统选择器的单应用 / 全屏授权、拒绝、取消后迟到结果、锁屏终止、旋转 / 折叠时的尺寸更新及停止重开。录屏与独立虚拟屏模式切换后应恢复各自界面和配置。日志中的采集或编码 FPS 不能作为仪表接收帧率或显示时延的证明。

关于与日志需验证亮暗主题、四个底部按钮在小屏和大字体下的可见性、离线许可证阅读，以及至少一个接收应用能读取分享的 TXT 文件。检查长日志的文件末尾、中文和 emoji、导出超时后的重试，以及分享过程中关闭日志窗口或旋转手机不会触发迟到弹窗。当前主机测试覆盖大于 1 MiB 的日志保真、UID 所有权、路径限制、过期与清理；这些测试不替代 Android 跨应用 URI 授权实测。

## GitHub Actions

仓库：[Margele/NinebotEnhance](https://github.com/Margele/NinebotEnhance)，初始可见性为 **Private**。

### 自动构建与发布

仅保留 `Build and Release`（`build-release.yml`）一个工作流。每次分支 push、PR 更新和手动运行都会构建，不设置文档路径过滤。工作流安装 JDK 21、SDK 36.1 和 Build Tools 37.0.0，运行发布脚本测试、APK 构建及 Java 主机断言，再验证 Gradle Debug 构建。

推送 `v<版本>` 标签（或在 Actions 里选该标签手动运行）使用固定发布签名，构建成功后为该标签创建 GitHub Release，上传 APK、源码 ZIP、`BUILD-INFO.json` 和 `SHA256SUMS.txt`。Artifacts 同时保留 30 天。版本名称包含 `-` 时标记为预发布版本，例如 `1.1.0-beta.1`。

所有分支（含 `main`）的 push 和 PR 使用临时测试签名，只构建并上传保留 7 天的 CI 附件；文件名带 `-ci`，不能覆盖正式签名的安装。推送 `main` 不会发版。

### 发布新版

1. 把 `CHANGELOG.md` 顶部的 `## 未发布` 段改成 `## <版本>`（发布脚本把它作为 Release 正文；没有段落时退回固定说明），更新 `version.properties` 和 `Protocol.java` 中的版本名称并递增版本代码，改 README 里的 APK 文件名，提交并推送 `main`。
2. 在该提交上打轻量标签并推送：`git tag v<版本> && git push origin v<版本>`。发布脚本要求标签名与 `version.properties` 一致，不一致直接失败。
3. 等待 `Build and Release` 的发布 job 完成，从仓库 Releases 页面下载新版本。

同标签已存在完整 Release 时，本次仍执行构建，但跳过重复发布，保留原附件。只有草稿或附件不完整时会报错，避免悄悄替换已发布内容。版本与源码取自标签指向的提交。

签名材料存于只允许 `v*` 标签（和 `main`）部署的 GitHub `release` 环境，包含 `RELEASE_KEYSTORE_BASE64` 和 `RELEASE_KEYSTORE_PASSWORD` 两个加密 Secrets。密钥只在发布 job 的临时目录还原，构建后清理，不进入仓库、缓存或构建附件。普通 CI 使用只读仓库权限；发布 job 才申请写入 Release 所需的权限。所有外部 Actions 固定到完整提交 SHA。

## 本地源码归档

首发前可以从当前提交导出源码，无需提前创建标签：

```sh
git archive --format=zip --prefix=NinebotEnhance/ --output=dist/NinebotEnhance-1.0.0-source.zip HEAD
```

源码包只包含该提交的项目文件，不包含 Git 历史、构建缓存、本机配置、签名私钥或旧构建产物。确认归档中的版本与 APK 一致，并将源码 ZIP 的 SHA-256 加入 `SHA256SUMS.txt`。保留根目录 `LICENSE` 与 `THIRD_PARTY_NOTICES.md`，打包的项目许可证和第三方声明须与源码版本一致。正式发布时，由发布工作流按推送的标签归档同一提交。

## 发布文件

| 文件 | 用途 |
| --- | --- |
| `NinebotEnhance-1.0.0.apk` | 已签名的安装包 |
| `NinebotEnhance-1.0.0-source.zip` | 对应标签的源码 |
| `SHA256SUMS.txt` | 安装包与源码包校验值 |

`artifact-checks.json` 用于本地核对构建结果；`build-verification.json` 包含本机工具路径和命令记录，不作为下载附件。第三方声明和许可证随源码及 APK 保留。

日常修改推送到 `main` 或通过 PR 合并后只自动构建；只有推送 `v<版本>` 标签才生成 Release。
