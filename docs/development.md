# 开发与发布

[返回项目首页](../README.md)

## 源码位置

| 路径 | 内容 |
| --- | --- |
| [`app/`](../app/) | Android 宿主、四个栏目、播放与阅读引擎 |
| [`integration/kazumi/`](../integration/kazumi/) | 动画集成的补丁、Flutter 入口和完整 APK 构建脚本 |
| [`desktop/`](../desktop/) | Windows 源规则调试器和离线验证 |
| [`tools/`](../tools/) | Android 打包、验签与发布工具 |

动画集成使用固定版本 Kazumi 2.4.5，应用本项目的补丁，再由 `anon_anime.dart` 注册动画功能。[Kazumi 对应源码](https://github.com/Predidit/Kazumi/tree/dedb7136318a509d7f412794b2531cf725034f00) · [集成入口](../integration/kazumi/runtime/lib/anon_anime.dart)

## Android 构建与测试

安装 Git、Python 3、JDK 17、Android SDK 和 Flutter 3.50.1。设置 `ANDROID_HOME` 和 `FLUTTER_ROOT`，也可使用工作区中的 `build/android-sdk`、`build/flutter-sdk`。首次构建需要联网获取依赖。

在项目根目录运行完整 APK 构建和 Android 宿主测试。宿主测试使用 Robolectric。

```powershell
python integration/kazumi/build_android.py
.\gradlew.bat :app:testReleaseUnitTest
```

如有获批的弹弹play开放平台应用凭证，可在自行构建时设置 `ANON_TOOL_DANDANPLAY_APP_ID` 和 `ANON_TOOL_DANDANPLAY_APP_SECRET`。[弹弹play开放平台](https://doc.dandanplay.com/open/)

## Windows 源规则调试器

将调试器 JAR 与启动脚本放在同一个文件夹，使用 Java 17 或更新版本启动。可导入来源，逐步检查作品搜索、目录、正文和图片。点「载入示例」可离线体验。调试器将来源保存在用户目录下的 `.shengjian/workbench/sources.json`。[启动脚本](../打开源规则调试器.cmd)

PC 与 Android 共用导入器和阅读引擎。构建入口为 `desktop/build.ps1`。当前脚本的桌面编译包含依赖 `SharedPreferences` 的共享类，重新打包时需将 Android SDK 的 `android.jar` 加入编译类路径。[桌面构建脚本](../desktop/build.ps1)

已有 JAR 时，可在项目根目录运行离线验证。第二条命令用 Windows 系统 ICU 检查 Android 正则兼容性。[离线验证源码](../desktop/src/main/java/com/shengjian/player/ReaderVerification.java) · [正则验证脚本](../desktop/verify-android-regex.ps1)

```powershell
java -cp shengjian-reader-workbench.jar com.shengjian.player.ReaderVerification
.\desktop\verify-android-regex.ps1
```

## 发布新版

1. 提高 `versionCode`、更新 `versionName`，然后在 `tools/releases/版本号.md` 写好更新说明。[版本配置](../app/build.gradle) · [发布说明](../tools/releases/)
2. 用上一版的正式 APK 验证覆盖升级签名。打包工具会跑测试，校验包名、版本和签名，并在 `build/releases/v版本号/` 生成 APK、`update.json` 和 `SHA256SUMS.txt`。[打包工具](../tools/release_android.py)

   ```powershell
   python tools/release_android.py --repository fswx2021/anon-toolkyo --gitee-repository fswx2021/anon-toolkyo-updates --previous "上一版.apk"
   ```

   Windows 已初始化构建缓存时可加 `--use-cache`。覆盖安装须沿用原签名。如使用单独的签名库，请在本机设置 `ANON_TOOL_RELEASE_KEYSTORE`、`ANON_TOOL_RELEASE_STORE_PASSWORD`、`ANON_TOOL_RELEASE_KEY_ALIAS` 和 `ANON_TOOL_RELEASE_KEY_PASSWORD`，将签名库和密码与源码分开保存。

3. 提交源码并推送对应的 `v版本号` 标签。在 GitHub 创建 Release，上传三个附件后发布正式版。也可保留上述参数并加 `--apk "已生成的安装包.apk" --create-draft`，使用已登录的 Git Credential Manager 创建草稿。[GitHub 发布页](https://github.com/fswx2021/anon-toolkyo/releases)
4. 在 Gitee 的公开发布仓库创建同名正式版，上传同一份 APK、更新清单、校验文件和从发布标签生成的对应源码 ZIP。发布后核对公开下载与 SHA-256。[Gitee 发布页](https://gitee.com/fswx2021/anon-toolkyo-updates/releases) · [Gitee 创建发行版](https://help.gitee.com/repository/release/create)

如需部署自有 HTTPS 镜像，构建时加 `--mirror-base https://example.org/updates/`。先上传生成的镜像版本目录，再更新镜像根目录的 `update.json`。镜像地址应直接返回文件。
