package com.jeteezntmax.toolbox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.ArrayList;

/**
 * 提示列表（功能提示悬浮窗的"正文"部分）。
 *
 * · 只有正文在这里；最上面那行标题由 HudTitleView 单独一个窗口画（它同时是拖动条）
 * · 每行彩虹渐变，色相一直流转（约 11 秒一圈）
 * · 对齐跟着位置走：在屏幕左半边 = 靠左，右半边 = 靠右
 *   （上下半屏那套"字号递增/递减"按作者要求撤掉了，现在字号统一）
 * · 这一层是 **触摸穿透** 的，不挡游戏里的按键
 */
public class FeatureHudView extends View {

    private final Paint pTx = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private float textSize, rowH, pad, fontSp = 12f;
    private boolean alignRight = false;
    private float hueBase = 0f;
    private final ArrayList<String> lines = new ArrayList<String>();

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        public void run() {
            hueBase = (hueBase + 1.6f) % 360f;
            invalidate();
            if (getVisibility() == VISIBLE && isAttachedToWindow()) h.postDelayed(this, 50);
        }
    };

    public FeatureHudView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        pTx.setFakeBoldText(true);
        applyFont();
    }

    private float dp(float v) { return v * density; }
    private float sp(float v) { return v * getResources().getDisplayMetrics().scaledDensity; }

    private void applyFont() {
        textSize = sp(fontSp);
        rowH = dp(fontSp * 1.85f);
        pad = dp(Math.max(2f, fontSp * 0.25f));
        pTx.setTextSize(textSize);
        pTx.setShadowLayer(dp(fontSp * 0.25f), 0, dp(1), 0xE6000000);
    }

    public void setFontSp(float v) {
        if (v < 8f) v = 8f;
        if (v > 24f) v = 24f;
        if (Math.abs(v - fontSp) < 0.01f) return;
        fontSp = v;
        applyFont();
        requestLayout();
        invalidate();
    }

    /** 位置决定左右对齐（按当前屏幕尺寸算，转屏后外面会再调一次） */
    public void setLayoutMode(boolean alignRight) {
        if (this.alignRight == alignRight) return;
        this.alignRight = alignRight;
        requestLayout();
        invalidate();
    }

    public void setLines(ArrayList<String> ls) {
        lines.clear();
        if (ls != null) {
            for (String s : ls) if (s != null && !s.trim().isEmpty()) lines.add(s.trim());
        }
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int maxW = getResources().getDisplayMetrics().widthPixels - (int) dp(12);
        float w = dp(20), hgt = pad * 2;
        for (String s : lines) {
            w = Math.max(w, pTx.measureText(s) + pad * 2 + dp(6));
            hgt += rowH;
        }
        setMeasuredDimension(resolveSize((int) Math.min(w, maxW), wSpec), resolveSize((int) hgt, hSpec));
    }

    @Override
    protected void onDraw(Canvas cv) {
        if (lines.isEmpty()) return;
        float W = getWidth(), y = pad;
        float[] hsv = new float[3];
        pTx.setTextSize(textSize);
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i);
            float tw = pTx.measureText(s);
            float x = alignRight ? Math.max(pad, W - pad - tw) : pad;
            float base = y + rowH * 0.72f;
            hsv[0] = (hueBase + i * 26f) % 360f;
            hsv[1] = 0.80f;
            hsv[2] = 1f;
            int c1 = Color.HSVToColor(hsv);
            hsv[0] = (hsv[0] + 46f) % 360f;
            int c2 = Color.HSVToColor(hsv);
            pTx.setShader(new LinearGradient(x, 0, x + tw, 0, c1, c2, Shader.TileMode.CLAMP));
            cv.drawText(s, x, base, pTx);
            pTx.setShader(null);
            y += rowH;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        h.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View v, int vis) {
        super.onVisibilityChanged(v, vis);
        h.removeCallbacks(tick);
        if (vis == VISIBLE) h.post(tick);
    }
}
