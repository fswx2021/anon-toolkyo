package com.shengjian.player;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class Anime4kShaders {
    private Anime4kShaders() { }

    static List<File> files(Context context, int preset) throws IOException {
        if (preset == 0) return Collections.emptyList();
        File directory = new File(context.getCacheDir(), "anime4k-v4");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Anime4K cache unavailable");
        String size = preset == 1 ? "S" : "M";
        String[] names = {"Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_" + size + ".glsl",
                "Anime4K_Upscale_CNN_x2_" + size + ".glsl"};
        List<File> shaders = new ArrayList<>(names.length);
        for (String name : names) {
            File file = new File(directory, name);
            try (InputStream asset = context.getAssets().open("anime4k/" + name)) {
                Files.copy(asset, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            shaders.add(file);
        }
        return shaders;
    }
}
