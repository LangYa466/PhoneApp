# PhoneApp — 项目约定

## 自动发布约定

`master` 分支的 commit message 以 `release:` 开头时，GitHub Actions
(`.github/workflows/release.yml`) 会自动：

1. 用 JDK 17 + Gradle 9.4.1 跑 `gradle :app:assembleRelease`
2. 从 `app/build.gradle` 的 `versionName` 解析版本号
3. 创建 tag `v<versionName>` 并发布 Release，附上 APK
   (`Phone-<versionName>.apk`)，Release body 用 commit message 原文

### 触发方式

```
git commit -m "release: 1.6 新增 XXX"
```

### 不触发的写法

任何其它前缀 (`feat:`, `fix:`, `refactor:` …) 都不会触发 release，
正常 push 即可。

### 注意

- 发版前必须先在 `app/build.gradle` 把 `versionName` (以及一般同时改
  `versionCode`) 更新到目标版本，否则 tag 会和上一次重复，
  `softprops/action-gh-release` 会失败。

## 签名

release 构建走环境变量驱动的 `signingConfigs.release`，本地构建不设
`ANDROID_KEYSTORE_FILE` 时自动回退为不签名 (不会报错)。

GitHub Actions 用 repo secrets 注入:
- `ANDROID_KEYSTORE_BASE64` — keystore 文件 base64
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

本地 keystore 在 `~/.android/phoneapp-release.jks` (10000 天有效,
alias `phoneapp`)，**不进 git**。换机/丢失需要重新生成并更新所有
secrets — 但 applicationId 一旦发布过, 换签名等于换 app, 用户必须
卸载重装, 谨慎。本地想签名可以:

```
ANDROID_KEYSTORE_FILE=~/.android/phoneapp-release.jks \
ANDROID_KEYSTORE_PASSWORD=... \
ANDROID_KEY_ALIAS=phoneapp \
ANDROID_KEY_PASSWORD=... \
gradle :app:assembleRelease
```
