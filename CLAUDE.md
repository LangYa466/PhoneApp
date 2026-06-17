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
- 当前 release 构建未配置签名，产物是未签名 APK。需要签名时在
  workflow 里加 `signingConfigs` + secrets。
