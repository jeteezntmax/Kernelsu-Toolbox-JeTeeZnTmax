package com.jeteezntmax.toolbox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

/**
 * 「已打开的功能」提示列表 —— 默认贴在屏幕最右上角，一行一个功能。
 *
 * 行的顺序由外面决定（按字数从多到少排 —— 也就是最长的在最上面），
 * 这样一段文字看起来是顺着下来的，不会参差不齐。
 *
 * 整块可以拖（拖到哪记到哪）；外面给个开关，关掉就整块藏起来。
 */
public class FeatureHudView extends View {

    public interface DragHost { void onDrag(int dx, int dy); }

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBd = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTx = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTitle = new Paint(Paint.ANTI_ALIAS_FLAG);   // 最上面那行自定义标题
    private float hueBase = 0f;          // 彩虹渐变起始色相
    private boolean titleMode = false;   // 第一行是"标题"（画得大一点）
    private boolean alignRight = false;  // 在屏幕右半边 → 靠右对齐
    private boolean topHalf = true;      // 在屏幕上半边 → 从上到下越来越小
    private final Handler h = new Handler(Looper.getMainLooper());
    /** 让彩虹一直闪：每 50ms 色相往前挪一点（约 11 秒一圈），只在可见时跑 */
    private final Runnable tick = new Runnable() {
        public void run() {
            hueBase = (hueBase + 1.6f) % 360f;
            invalidate();
            if (getVisibility() == VISIBLE && isAttachedToWindow()) h.postDelayed(this, 50);
        }
    };

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
    private final float density;
    private float textSize, rowH, pad, fontSp = 12f;
    private final ArrayList<String> lines = new ArrayList<String>();

    public FeatureHudView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        applyFont();
        /* 不要底框：只留文字，所以给个黑影保证在任何画面上都看得清 */
        pTx.setFakeBoldText(true);
        pTitle.setFakeBoldText(true);
        hueBase = 0f;
        applyFont();
    }

    /** 字号可调（WebUI 里选，默认 12） */
    public void setFontSp(float v) {
        if (v < 8f) v = 8f;
        if (v > 24f) v = 24f;
        if (Math.abs(v - fontSp) < 0.01f) return;
        fontSp = v;
        applyFont();
        requestLayout();
        invalidate();
    }

    private void applyFont() {
        textSize = sp(fontSp);
        rowH = dp(fontSp * 1.9f);
        pad = dp(Math.max(2f, fontSp * 0.25f));
        pTx.setTextSize(textSize);
        pTitle.setTextSize(sp(fontSp * 1.12f));
        pTx.setShadowLayer(dp(fontSp * 0.25f), 0, dp(1), 0xE6000000);
        pTitle.setShadowLayer(dp(fontSp * 0.3f), 0, dp(1), 0xE6000000);
    }

    private float dp(float v) { return v * density; }
    private float sp(float v) { return v * getResources().getDisplayMetrics().scaledDensity; }

    public void setTitleMode(boolean on) { titleMode = on; }

    /**
     * 位置决定排版：
     *   上半屏 → 从上到下字号越来越小；下半屏 → 越来越大
     *   左半边 → 靠左对齐；右半边 → 靠右对齐
     * 位置是按【当前屏幕尺寸】算的，所以横竖屏切换后由外面重新算一遍传进来。
     */
    public void setLayoutMode(boolean alignRight, boolean topHalf) {
        if (this.alignRight == alignRight && this.topHalf == topHalf) return;
        this.alignRight = alignRight;
        this.topHalf = topHalf;
        requestLayout();
        invalidate();
    }

    /** 第 i 行的字号比例（最大那行是 1.0，最小 0.62） */
    private float scaleOf(int i, int n) {
        if (n <= 1) return 1f;
        float k = 0.38f * i / (n - 1);
        return topHalf ? (1f - k) : (0.62f + k);
    }

    private float lineH(float base, int i, int n) {
        return base * scaleOf(i, n) * 1.95f;
    }

    public void setLines(ArrayList<String> ls) {
        lines.clear();
        if (ls != null) for (String s : ls) if (s != null && !s.trim().isEmpty()) lines.add(s.trim());
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int maxW = getResources().getDisplayMetrics().widthPixels - (int) dp(12);
        int n = Math.max(lines.size(), 1);
        float w = dp(20), h = pad * 2;
        for (int i = 0; i < lines.size(); i++) {
            float sc = scaleOf(i, n);
            boolean title = (i == 0 && titleMode);
            float fs = textSize * sc * (title ? 1.12f : 1f);
            Paint pp = title ? pTitle : pTx;
            pp.setTextSize(fs);
            w = Math.max(w, pp.measureText(lines.get(i)) + pad * 2 + dp(6));
            h += lineH(textSize, i, n) + (title ? dp(2) : 0);
        }
        setMeasuredDimension(resolveSize((int) Math.min(w, maxW), wSpec), resolveSize((int) h, hSpec));
    }

    @Override
    protected void onDraw(Canvas cv) {
        if (lines.isEmpty()) return;
        float W = getWidth(), y = pad;
        int n = lines.size();
        float[] hsv = new float[3];
        for (int i = 0; i < n; i++) {
            String s = lines.get(i);
            boolean title = (i == 0 && titleMode);
            float sc = scaleOf(i, n);
            float fs = textSize * sc * (title ? 1.12f : 1f);
            Paint pp = title ? pTitle : pTx;
            pp.setTextSize(fs);
            float rowH = lineH(textSize, i, n) + (title ? dp(2) : 0);
            float tw = pp.measureText(s);
            float x = alignRight ? Math.max(pad, W - pad - tw) : pad;
            float base = y + rowH * 0.72f;
            hsv[0] = (hueBase + i * 26f) % 360f;
            hsv[1] = 0.80f;
            hsv[2] = 1f;
            int c1 = Color.HSVToColor(hsv);
            hsv[0] = (hsv[0] + 46f) % 360f;
            int c2 = Color.HSVToColor(hsv);
            pp.setShader(new LinearGradient(x, 0, x + tw, 0, c1, c2, Shader.TileMode.CLAMP));
            cv.drawText(s, x, base, pp);
            pp.setShader(null);
            y += rowH;
        }
    }

    /* ---------- 拖动 ---------- */
    private DragHost host;
    private float downX, downY;
    public void setDragHost(DragHost h) { host = h; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX(); downY = e.getRawY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (host != null) {
                    host.onDrag((int) (e.getRawX() - downX), (int) (e.getRawY() - downY));
                    downX = e.getRawX(); downY = e.getRawY();
                }
                return true;
        }
        return true;
    }
}
