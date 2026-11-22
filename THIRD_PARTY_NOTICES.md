# 第三方声明

anon toolkyo 的项目代码采用 GNU GPL 第 3 版。各第三方组件仍适用其自身的许可。安装包内可从「设置 → 开源声明」查看随包提供的许可原文。

- **Kazumi 2.4.5** 的作者为 Predidit 和项目贡献者。动画功能使用固定版本的源码及本项目在 `integration/kazumi/` 中维护的修改。该目录也包含构建脚本，可从同版本源代码重新构建。 [Kazumi 源码与 GPL 第 3 版许可](https://github.com/Predidit/Kazumi/tree/dedb7136318a509d7f412794b2531cf725034f00)
- **libmpv、FFmpeg 和 media-kit Android Helper** 的对应源代码、原始构建脚本、补丁、架构版本和校验值载于 `app/src/main/assets/licenses/mpv-notices.txt`。可替换 `app/src/main/jniLibs/` 中相应架构的动态库再构建。 [libmpv Android v1.3.1 发布与源码](https://github.com/Predidit/libmpv-android-video-build/tree/v1.3.1)
- **ech_http 0.2.4** 采用 MIT 许可。随库分发的 libcurl、BoringSSL、Mozilla 根证书包和 NDK libc++ 的许可证与来源记录见 `app/src/main/assets/licenses/ech_http/`。 [ech_http 源码](https://github.com/ech-research/ech_http)
- **Anime4K v4.0** 的作者为 bloc97 和项目贡献者，采用 MIT 许可。色器旁附许可证全文。 [Anime4K 源码](https://github.com/bloc97/Anime4K)
- **jsoup 1.21.2** 采用 MIT 许可。**Rhino 1.7.15.1** 主要采用 MPL 2.0，附有依赖与版权声明。**JNA 5.17.0** 在本项目中按 Apache 2.0 许可使用。对应许可证在 `app/src/main/assets/licenses/`。 [jsoup 源码](https://github.com/jhy/jsoup/tree/jsoup-1.21.2) · [Rhino 源码](https://github.com/mozilla/rhino/tree/Rhino1_7_15_1_Release) · [JNA 源码](https://github.com/java-native-access/jna/tree/5.17.0)
- **AndroidX、Jetpack Compose 和 Gradle Wrapper** 采用 Apache 2.0 许可。**Android Java NIO 兼容库 2.1.5** 采用 GNU GPL 第 2 版并附 Classpath 例外。Flutter 及 Dart 依赖的许可汇总随安装包提供。 [AndroidX 源码](https://android.googlesource.com/platform/frameworks/support/) · [NIO 兼容库源码](https://github.com/google/desugar_jdk_libs/tree/73170c345e6a762fc6a1f0301bb15218850023ef) · [Flutter 源码](https://github.com/flutter/flutter)
- Windows 源规则调试器使用 **TwelveMonkeys ImageIO 3.12.0** 和 **JSON-java 20260814**。BSD 3 条款及公有领域声明原文见 `desktop/licenses/`。 [TwelveMonkeys 对应版本与许可](https://github.com/haraldk/TwelveMonkeys/blob/twelvemonkeys-3.12.0/LICENSE.txt) · [JSON-java 对应版本与许可](https://github.com/stleary/JSON-java/blob/20260814/LICENSE)

公开版内置的图标、状态插图和离线番剧封面是本项目绘制的唱片、拨片和几何图案。资源可用 Pillow 重新生成，绘图代码位于 `tools/generate_public_art.py`。联网番剧图片由 Bangumi 提供，图片权利归各自权利方。 [Bangumi](https://bangumi.tv/)
