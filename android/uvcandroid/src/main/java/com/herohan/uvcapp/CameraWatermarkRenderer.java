package com.herohan.uvcapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.SystemClock;

import com.serenegiant.opengl.GLDrawer2D;
import com.serenegiant.opengl.GLHelper;

/** Cached OpenGL watermark used only while drawing to the MediaCodec recording surface. */
final class CameraWatermarkRenderer {
    private static final long DATE_CHECK_INTERVAL_MS = 60_000L;

    private final Context mContext;
    private final float[] mMvpMatrix = new float[16];
    private final float[] mTextureMatrix = new float[16];
    private GLDrawer2D mDrawer;
    private int mTextureId;
    private String mDateText;
    private long mNextDateCheckMs;

    CameraWatermarkRenderer(Context context) {
        mContext = context.getApplicationContext();
        Matrix.setIdentityM(mTextureMatrix, 0);
        // Android Bitmap rows are top-to-bottom; OpenGL texture coordinates are bottom-to-top.
        Matrix.translateM(mTextureMatrix, 0, 0f, 1f, 0f);
        Matrix.scaleM(mTextureMatrix, 0, 1f, -1f, 1f);
        mDrawer = new GLDrawer2D(false);
        mTextureId = GLHelper.initTex(GLES20.GL_TEXTURE_2D, GLES20.GL_LINEAR);
        refreshArtwork(true);
    }

    void draw(int frameWidth, int frameHeight) {
        if (mDrawer == null || mTextureId == 0 || frameWidth <= 0 || frameHeight <= 0) return;
        refreshArtwork(false);

        float widthFraction = CameraWatermark.FRAME_WIDTH_FRACTION;
        float heightFraction = widthFraction
                * ((float) frameWidth / (float) frameHeight)
                * ((float) CameraWatermark.TEXTURE_HEIGHT / CameraWatermark.TEXTURE_WIDTH);
        float marginX = CameraWatermark.FRAME_MARGIN_FRACTION;
        float marginY = CameraWatermark.FRAME_MARGIN_FRACTION
                * ((float) frameWidth / (float) frameHeight);

        Matrix.setIdentityM(mMvpMatrix, 0);
        Matrix.translateM(
                mMvpMatrix,
                0,
                1f - 2f * marginX - widthFraction,
                -1f + 2f * marginY + heightFraction,
                0f);
        Matrix.scaleM(mMvpMatrix, 0, widthFraction, heightFraction, 1f);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        mDrawer.setMvpMatrix(mMvpMatrix, 0);
        mDrawer.draw(mTextureId, mTextureMatrix, 0);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    void release() {
        if (mDrawer != null) {
            mDrawer.release();
            mDrawer = null;
        }
        if (mTextureId != 0) {
            GLHelper.deleteTex(mTextureId);
            mTextureId = 0;
        }
    }

    private void refreshArtwork(boolean force) {
        long nowMs = SystemClock.elapsedRealtime();
        if (!force && nowMs < mNextDateCheckMs) return;
        mNextDateCheckMs = nowMs + DATE_CHECK_INTERVAL_MS;
        String dateText = CameraWatermark.currentDateText();
        if (!force && dateText.equals(mDateText)) return;

        Bitmap artwork = CameraWatermark.createArtwork(mContext, dateText);
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureId);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, artwork, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            mDateText = dateText;
        } finally {
            artwork.recycle();
        }
    }
}
