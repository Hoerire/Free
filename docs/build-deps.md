# 编译期依赖：`libs/hidden-stubs.jar`

`libs/` 里只剩一个 jar：`hidden-stubs.jar`，它只参与编译，**不进 dex、不进 APK**
（`build.gradle.kts` 里是 `compileOnly`）。原因是它占位的类（`android.view.InputChannel`、
`InputEventReceiver` 等 framework 隐藏 API）在设备上由 bootclasspath 提供；打进 APK 会与
运行时的真实类同名，导致类加载失败。

AndroidX 的 `preference` / `fragment` 两个 jar 曾经也需要：系统设置页由模块自己的
`PreferenceFragmentCompat` 子类渲染。该页面已经移除（设置界面完全改由模块 App 的 Compose 实现，
系统设置里只保留一条跳转入口），因此这两个 jar 不再需要，构建也不再依赖它们。

## 历史：那两个 jar 怎么来的（现已不需要）

需要时从 Google Maven 取 AAR、解出 `classes.jar` 即可：

```sh
curl -sL -o /tmp/preference.aar \
  https://dl.google.com/android/maven2/androidx/preference/preference/1.2.1/preference-1.2.1.aar
unzip -p /tmp/preference.aar classes.jar > libs/preference-classes.jar

curl -sL -o /tmp/fragment.aar \
  https://dl.google.com/android/maven2/androidx/fragment/fragment/1.8.9/fragment-1.8.9.aar
unzip -p /tmp/fragment.aar classes.jar > libs/fragment-classes.jar
```

**不要**把这类产物提交进仓库：体积大、随 AAR 版本变化，且一旦有人把 `compileOnly` 改成
`implementation`，APK 里就会出现与系统重名的类。

## 注意事项

- 引用 `hidden-stubs.jar` 里的类必须保持 `compileOnly`；改成 `implementation` 会让 APK 里出现重复类。
- 若将来又要 hook 系统设置页的偏好树，需要重新引入上面那两个 jar，并在 CI 里补回生成步骤。
