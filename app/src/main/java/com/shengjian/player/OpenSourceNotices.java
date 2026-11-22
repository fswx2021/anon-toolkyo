package com.shengjian.player;

import java.util.List;

final class OpenSourceNotices {
    record Document(String title, String asset) {}
    record Component(String name, String credit, String source, List<Document> documents) {}

    static final List<Component> COMPONENTS = List.of(
            new Component("Kazumi 2.4.5", "动画播放、弹幕与规则 · Predidit 和项目贡献者",
                    "https://github.com/Predidit/Kazumi/tree/dedb7136318a509d7f412794b2531cf725034f00",
                    List.of(license("GNU GPL v3", "kazumi-GPL-3.0.txt"))),
            new Component("media_kit、libmpv 与 FFmpeg", "动画播放引擎和原生接口",
                    "https://github.com/Predidit/media-kit/tree/cd75bcfe94ba45ebb6f6b940c68e072d079aee14",
                    List.of(license("构建来源、版本和许可说明", "mpv-notices.txt"),
                            license("GNU LGPL v3", "mpv-lgpl-3.0.txt"),
                            license("GNU GPL v3", "mpv-gpl-3.0.txt"),
                            license("Android Helper · MIT", "mpv-helper-license.txt"))),
            new Component("ech_http 0.2.4", "Bangumi 网络请求 · ech-research",
                    "https://github.com/ech-research/ech_http",
                    List.of(license("MIT", "ech_http/LICENSE"),
                            license("原生依赖与来源", "ech_http/THIRD_PARTY_NOTICES.md"),
                            license("curl 许可证", "ech_http/CURL_LICENSE"),
                            license("BoringSSL 许可证", "ech_http/BORINGSSL_LICENSE"),
                            license("Mozilla 根证书包 · MPL 2.0", "ech_http/CA_BUNDLE_LICENSE"),
                            license("Android NDK libc++ 许可证", "ech_http/LIBCXX_LICENSE"))),
            new Component("Anime4K v4.0", "画面增强着色器 · bloc97 和项目贡献者",
                    "https://github.com/bloc97/Anime4K",
                    List.of(new Document("MIT", "anime4k/LICENSE"))),
            new Component("jsoup 1.21.2", "HTML 解析 · Jonathan Hedley",
                    "https://github.com/jhy/jsoup/tree/jsoup-1.21.2",
                    List.of(license("MIT", "jsoup-license.txt"))),
            new Component("Mozilla Rhino 1.7.15.1", "部分漫画和小说来源的规则执行",
                    "https://github.com/mozilla/rhino/tree/Rhino1_7_15_1_Release",
                    List.of(license("Mozilla Public License 2.0", "rhino-license.txt"),
                            license("版权和依赖声明", "rhino-notice.txt"))),
            new Component("Java Native Access 5.17.0", "Java 原生库接口",
                    "https://github.com/java-native-access/jna/tree/5.17.0",
                    List.of(license("Apache License 2.0", "jna-apache-2.0.txt"),
                            license("双许可声明", "jna-license.txt"))),
            new Component("AndroidX 与 Jetpack Compose", "Android 界面和兼容组件",
                    "https://android.googlesource.com/platform/frameworks/support/",
                    List.of(license("Apache License 2.0", "androidx-apache-2.0.txt"))),
            new Component("Android Java 兼容库 2.1.5", "旧版 Android 上的 Java 核心接口",
                    "https://github.com/google/desugar_jdk_libs/tree/73170c345e6a762fc6a1f0301bb15218850023ef",
                    List.of(license("GNU GPL v2 与 Classpath 例外", "desugar-jdk-libs.txt")))
    );

    private static Document license(String title, String file) {
        return new Document(title, "licenses/" + file);
    }

    static Document findDocument(String asset) {
        return COMPONENTS.stream().flatMap(component -> component.documents().stream())
                .filter(document -> document.asset().equals(asset)).findFirst().orElse(null);
    }

    private OpenSourceNotices() {}
}
