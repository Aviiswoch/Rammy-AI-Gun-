package com.herohan.uvcapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import androidx.annotation.NonNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Shared, resolution-independent watermark artwork for preview, photos, and video. */
public final class CameraWatermark {
    public static final int TEXTURE_WIDTH = 1024;
    public static final int TEXTURE_HEIGHT = 384;
    public static final float FRAME_WIDTH_FRACTION = 0.19f;
    public static final float FRAME_MARGIN_FRACTION = 0.03f;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.getDefault());
    private static Bitmap sLogo;

    private CameraWatermark() {
    }

    @NonNull
    public static String currentDateText() {
        return DATE_FORMAT.withZone(ZoneId.systemDefault()).format(Instant.now());
    }

    @NonNull
    public static Bitmap createArtwork(@NonNull Context context, @NonNull String dateText) {
        Bitmap artwork = Bitmap.createBitmap(
                TEXTURE_WIDTH,
                TEXTURE_HEIGHT,
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(artwork);

        Paint backing = new Paint(Paint.ANTI_ALIAS_FLAG);
        backing.setColor(Color.argb(116, 7, 12, 13));
        canvas.drawRoundRect(new RectF(0, 0, TEXTURE_WIDTH, TEXTURE_HEIGHT), 64, 64, backing);

        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2);
        border.setColor(Color.argb(58, 255, 255, 255));
        canvas.drawRoundRect(new RectF(1, 1, TEXTURE_WIDTH - 1, TEXTURE_HEIGHT - 1), 64, 64, border);

        Bitmap logo = getLogo(context);
        RectF logoBounds = new RectF(28, 28, 356, 356);
        Paint logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        logoPaint.setAlpha(232);
        canvas.drawBitmap(logo, null, logoBounds, logoPaint);

        Paint brand = textPaint(82, Typeface.BOLD, Color.argb(242, 255, 255, 255));
        brand.setShadowLayer(5, 0, 2, Color.argb(180, 0, 0, 0));
        canvas.drawText("Rammy AI Gun", 390, 160, brand);

        Paint date = textPaint(52, Typeface.NORMAL, Color.argb(210, 255, 255, 255));
        date.setShadowLayer(4, 0, 2, Color.argb(180, 0, 0, 0));
        canvas.drawText(dateText, 394, 258, date);

        return artwork;
    }

    /** Burns the watermark once into a completed photo bitmap. */
    public static void drawOnPhoto(@NonNull Context context, @NonNull Bitmap photo) {
        Bitmap artwork = createArtwork(context, currentDateText());
        try {
            float outputWidth = photo.getWidth() * FRAME_WIDTH_FRACTION;
            float outputHeight = outputWidth * TEXTURE_HEIGHT / TEXTURE_WIDTH;
            float margin = Math.max(8f, photo.getWidth() * FRAME_MARGIN_FRACTION);
            RectF target = new RectF(
                    photo.getWidth() - margin - outputWidth,
                    photo.getHeight() - margin - outputHeight,
                    photo.getWidth() - margin,
                    photo.getHeight() - margin);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            new Canvas(photo).drawBitmap(artwork, null, target, paint);
        } finally {
            artwork.recycle();
        }
    }

    private static Paint textPaint(float size, int style, int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTypeface(Typeface.create("sans-serif", style));
        paint.setTextSize(size);
        paint.setColor(color);
        return paint;
    }

    private static synchronized Bitmap getLogo(Context context) {
        if (sLogo == null || sLogo.isRecycled()) {
            int resourceId = context.getResources().getIdentifier(
                    "rammy_launcher_logo",
                    "drawable",
                    context.getPackageName());
            if (resourceId == 0) {
                throw new IllegalStateException("Rammy launcher logo resource is unavailable");
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            sLogo = BitmapFactory.decodeResource(context.getResources(), resourceId, options);
            if (sLogo == null) {
                throw new IllegalStateException("Rammy launcher logo could not be decoded");
            }
        }
        return sLogo;
    }
}
