package com.alexmercerind.mediakitandroidhelper;

import android.content.Context;

/**
 * JNI declarations for media-kit's MIT-licensed Android helper.
 * See assets/licenses/mpv-notices.txt for its source and license.
 */
public final class MediaKitAndroidHelper {
    static {
        System.loadLibrary("mediakitandroidhelper");
    }

    private MediaKitAndroidHelper() { }

    private static native void setApplicationContextNative(Context context);

    public static void setApplicationContextJava(Context context) {
        setApplicationContextNative(context.getApplicationContext());
    }

    public static native long newGlobalObjectRef(Object object);

    public static native void deleteGlobalObjectRef(long reference);
}
