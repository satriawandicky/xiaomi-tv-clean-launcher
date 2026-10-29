package com.gathin.tvhome;

/**
 * How big a theme's wallpaper may be and what size it is decoded at (#166). A 1 GB TV cannot afford a
 * picture decoded at full size: a 65535x2159 image is about 283 MB even in RGB_565.
 */
final class WallpaperSize {
    private WallpaperSize() { }

    /** 4K UHD (3840x2160, 8.3 M pixels) and a little more; anything bigger is refused. */
    static final long MAX_PIXELS = 8_400_000L;

    static boolean accepted(int width, int height) {
        return width > 0 && height > 0 && (long) width * height <= MAX_PIXELS;
    }

    /** The largest power-of-two sample size that still covers the screen in both directions. */
    static int sampleSize(int width, int height, int screenW, int screenH) {
        int sample = 1;
        while (width / (sample * 2) >= screenW && height / (sample * 2) >= screenH) sample *= 2;
        return sample;
    }

    /**
     * {inDensity, inTargetDensity} that scale a (sampled) picture down to what CENTER_CROP shows: the
     * side that limits the cover lands on the screen size. Null when it is already that small.
     */
    /**
     * Decodes a picture at the size CENTER_CROP shows on a screen of screenW x screenH, in RGB_565 (half the
     * memory, no alpha needed). Null if it cannot be read or is over the size limit.
     */
    static android.graphics.Bitmap decode(java.io.File file, int screenW, int screenH) {
        try {
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
            int w = opts.outWidth, h = opts.outHeight;
            if (w <= 0 || h <= 0) return null;
            int sample = sampleSize(w, h, screenW, screenH);
            // The limit applies to what is decoded, so a 12 MP photo still works once it is sampled down.
            if (!accepted(w / sample, h / sample)) return null;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            int[] scale = coverScale(w / sample, h / sample, screenW, screenH);
            if (scale != null) {
                opts.inScaled = true;
                opts.inDensity = scale[0];
                opts.inTargetDensity = scale[1];
            }
            opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
            return android.graphics.BitmapFactory.decodeFile(file.getPath(), opts);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    static int[] coverScale(int width, int height, int screenW, int screenH) {
        if ((long) screenW * height >= (long) screenH * width) {   // width limits the cover
            return width > screenW ? new int[]{width, screenW} : null;
        }
        return height > screenH ? new int[]{height, screenH} : null;
    }
}
