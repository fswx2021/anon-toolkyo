package com.shengjian.player;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

final class AndroidReaderSources {
    private static ReaderSources instance;

    private AndroidReaderSources() {
    }

    static synchronized ReaderSources get(Context context) {
        if (instance == null) {
            Context application = context.getApplicationContext();
            File catalog = new File(application.getFilesDir(), "reader-sources.json");
            instance = new ReaderSources(catalog, application.getAssets()::open);
            ReaderSources.setBrowserLoader(new AndroidReaderBrowser(application));
        }
        return instance;
    }

    static ReaderSources.ImportPlan prepareFile(Context context, Uri uri, int kind) throws IOException {
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("请从系统文件选择器选择书源文件");
        }
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("无法读取书源文件");
            }
            return get(context).prepareStream(input, kind);
        } catch (SecurityException denied) {
            throw new IOException("文件访问权限已失效，请重新选择书源文件", denied);
        }
    }

    static ReaderSources.ImportResult importFile(Context context, Uri uri, int kind) throws IOException {
        return get(context).apply(prepareFile(context, uri, kind));
    }
}
