package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.graphics.Bitmap;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screensaver. With travel photos cached by Home (InfoFeed): full-screen photos, one every PHOTO_MS,
 * crossfading with a slow zoom, the place and country bottom left and the clock top right. No network
 * here: it only shows what Home already downloaded. Two screen-sized bitmaps at most (~8 MB).
 * Without photos: the thin clock and date drifting over a slow ambient glow, as before.
 */
public class ClockDream extends DreamService {

    private static final long MOVE_MS = 60_000L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private View content;
    private GradientDrawable glow;
    private int colorIndex;
    private int glowColor;

    private static final long PHOTO_MS = 45_000L;
    private static final long FADE_MS = 2_500L;

    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private List<File> photos = Collections.emptyList();
    private int photoIndex;
    private ImageView front, back;
    private View caption;
    private TextView place, country;

    private final Runnable photoTick = new Runnable() {
        @Override
        public void run() {
            nextPhoto();
            handler.postDelayed(this, PHOTO_MS);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            moveClock();
            shiftGlow();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);

        content = findViewById(R.id.dreamContent);
        View ambient = findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);

        front = findViewById(R.id.dreamPhotoA);
        back = findViewById(R.id.dreamPhotoB);
        caption = findViewById(R.id.dreamCaption);
        place = findViewById(R.id.dreamPlace);
        country = findViewById(R.id.dreamCountry);
        photos = InfoFeed.walls(this);
        Collections.shuffle(photos, random);
        if (!photos.isEmpty()) {
            ambient.setVisibility(View.GONE);
            findViewById(R.id.dreamScrim).setVisibility(View.VISIBLE);
            ((TextView) findViewById(R.id.dreamClock)).setTextSize(72);
        }
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        content.setAlpha(0f);
        if (!photos.isEmpty()) {
            content.post(() -> {
                // Photos change, so the clock can stay put top right without burning in.
                View parent = (View) content.getParent();
                float dp = getResources().getDisplayMetrics().density;
                content.setX(parent.getWidth() - content.getWidth() - 64 * dp);
                content.setY(40 * dp);
                content.animate().alpha(1f).setDuration(1200).start();
            });
            handler.post(photoTick);
            return;
        }
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.postDelayed(tick, MOVE_MS);
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(photoTick);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(photoTick);
        decoder.shutdownNow();
        super.onDetachedFromWindow();
    }

    /** Decode the next photo off the main thread, then crossfade it in over the current one. */
    private void nextPhoto() {
        File file = photos.get(photoIndex++ % photos.size());
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        decoder.execute(() -> {
            Bitmap bmp = WallpaperSize.decode(file, dm.widthPixels, dm.heightPixels);
            String[] pc = InfoFeed.placeCountry(InfoFeed.caption(file));
            if (bmp != null) handler.post(() -> showPhoto(bmp, pc));
        });
    }

    private void showPhoto(Bitmap bmp, String[] pc) {
        ImageView incoming = back, outgoing = front;
        front = incoming;
        back = outgoing;
        incoming.animate().cancel();
        incoming.setImageBitmap(bmp);
        incoming.bringToFront();
        // keep scrim, caption and clock above the photos
        findViewById(R.id.dreamScrim).bringToFront();
        caption.bringToFront();
        content.bringToFront();
        // Slow zoom towards a random point while the photo is up (cheap: one hardware-layer transform).
        incoming.setPivotX(incoming.getWidth() * (0.25f + 0.5f * random.nextFloat()));
        incoming.setPivotY(incoming.getHeight() * (0.3f + 0.4f * random.nextFloat()));
        incoming.setScaleX(1f);
        incoming.setScaleY(1f);
        incoming.setAlpha(0f);
        incoming.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        incoming.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> {
            outgoing.setImageDrawable(null);   // let the old bitmap go
            outgoing.setAlpha(0f);
            incoming.setLayerType(View.LAYER_TYPE_NONE, null);
            incoming.animate().scaleX(1.08f).scaleY(1.08f).setDuration(PHOTO_MS)
                    .setInterpolator(new LinearInterpolator()).start();
        }).start();
        caption.animate().cancel();
        caption.animate().alpha(0f).setDuration(caption.getAlpha() > 0 ? 600 : 0).withEndAction(() -> {
            place.setText(pc[0]);
            country.setText(pc[1].isEmpty() ? "" : "\uD83D\uDCCD  " + pc[1]);
            caption.animate().alpha(1f).setDuration(1400).setStartDelay(600).start();
        }).start();
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        }).start();
    }

    /** A random spot that keeps the whole clock on screen, with the glow centred behind it. */
    private void placeClock() {
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x = maxX * (0.1f + 0.8f * random.nextFloat());
        float y = maxY * (0.1f + 0.8f * random.nextFloat());
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    private void shiftGlow() {
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, PALETTE[colorIndex]);
        anim.setDuration(8000);
        anim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        anim.start();
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
