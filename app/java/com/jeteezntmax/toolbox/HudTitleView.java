package com.jeteezntmax.toolbox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * 提示悬浮窗的「标题条」—— 也是拖动把手。
 *
 * 原来是单独一个 24dp 的小圆点当把手，太不起眼（作者说看不清）✗。
 * 现在把**自定义的那行标题**单独做成一个窗口：加粗 + 淡背景 + 一点点透明，
 * **拖这一行就能移动整块提示**（列表那层仍然是触摸穿透，不挡游戏按键）。
 */
public class HudTitleView extends View {

    public interface DragHost { void onDrag(int dx, int dy); }

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTx = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private float fontSp = 12f;
    private String title = "Cometa-游戏加速：停用";
    private float hueBase = 0f;
    private boolean pressed = false, alignRight = true;
    private DragHost host;
    private float downX, downY;

    public HudTitleView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        pBg.setStyle(Paint.Style.FILL);
        pTx.setFakeBoldText(true);                     // 加粗
        pTx.setShadowLayer(dp(3), 0, dp(1), 0xE6000000);
        pTx.setTextSize(sp(fontSp));
    }

    private float dp(float v) { return v * density; }
    private float sp(float v) { return v * getResources().getDisplayMetrics().scaledDensity; }

    public void setFontSp(float v) {
        if (v < 8f) v = 8f;
        if (v > 24f) v = 24f;
        fontSp = v;
        pTx.setTextSize(sp(fontSp * 1.12f));
        requestLayout();
        invalidate();
    }

    public void setTitle(String t) {
        if (t == null) t = "";
        if (t.equals(title)) return;
        title = t;
        requestLayout();
        invalidate();
    }

    public void setHue(float h) { hueBase = h; }

    public void setAlign(boolean right) { if (alignRight != right) { alignRight = right; invalidate(); } }

    public void setDragHost(DragHost h) { host = h; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int maxW = getResources().getDisplayMetrics().widthPixels - (int) dp(12);
        int w = (int) Math.min(pTx.measureText(title) + dp(16), maxW);
        int h = (int) (dp(9) + pTx.getTextSize() * 1.7f);
        setMeasuredDimension(resolveSize(w, wSpec), resolveSize(h, hSpec));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float W = getWidth(), H = getHeight();
        RectF box = new RectF(dp(0.5f), dp(0.5f), W - dp(0.5f), H - dp(0.5f));
        // 一点点不透明度：淡背景，看着像"可以拖的一行"
        pBg.setColor(pressed ? 0x66000000 : 0x40000000);
        cv.drawRoundRect(box, dp(8), dp(8), pBg);

        float tw = pTx.measureText(title);
        float x = alignRight ? Math.max(dp(8), W - dp(8) - tw) : dp(8);
        float base = H / 2f + pTx.getTextSize() * 0.36f;
        float[] hsv = { (hueBase) % 360f, 0.80f, 1f };
        int c1 = Color.HSVToColor(hsv);
        hsv[0] = (hsv[0] + 46f) % 360f;
        int c2 = Color.HSVToColor(hsv);
        pTx.setShader(new LinearGradient(x, 0, x + tw, 0, c1, c2, Shader.TileMode.CLAMP));
        pTx.setAlpha(pressed ? 255 : 235);             // 一点点透
        cv.drawText(title, x, base, pTx);
        pTx.setShader(null);
        pTx.setAlpha(255);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX(); downY = e.getRawY();
                pressed = true; invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (host != null) {
                    host.onDrag((int) (e.getRawX() - downX), (int) (e.getRawY() - downY));
                    downX = e.getRawX(); downY = e.getRawY();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pressed = false; invalidate();
                return true;
        }
        return true;
    }
}
