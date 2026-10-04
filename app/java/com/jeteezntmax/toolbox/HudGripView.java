package com.jeteezntmax.toolbox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * 提示悬浮窗的「拖动把手」。
 *
 * 为什么要有它：文字那一层现在是 **FLAG_NOT_TOUCHABLE**（触摸穿透），
 * 这样它在游戏里就不会挡住屏幕上的按键 / 摇杆 ✗ 不影响了 ✓；
 * 但穿透的窗口自己也收不到触摸，就没法拖了 —— 所以单独放一个 24dp 的小把手，
 * 只在它上面才接收触摸，拖它就等于拖整块提示（位置一起存）。
 */
public class HudGripView extends View {

    public interface DragHost { void onDrag(int dx, int dy); }

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private DragHost host;
    private float downX, downY;

    public HudGripView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        pBg.setColor(0x66000000);
        pBg.setStyle(Paint.Style.FILL);
        pDot.setColor(0xCC9AA7B4);
        pDot.setStyle(Paint.Style.FILL);
    }

    private float dp(float v) { return v * density; }

    public void setDragHost(DragHost h) { host = h; }

    @Override
    protected void onDraw(Canvas cv) {
        float W = getWidth(), H = getHeight();
        RectF r = new RectF(dp(2), dp(2), W - dp(2), H - dp(2));
        cv.drawRoundRect(r, dp(7), dp(7), pBg);
        // 三个小点，看起来像个"把手"
        float cx = W / 2f, cy = H / 2f, gap = dp(4.2f);
        cv.drawCircle(cx, cy - gap, dp(1.5f), pDot);
        cv.drawCircle(cx, cy, dp(1.5f), pDot);
        cv.drawCircle(cx, cy + gap, dp(1.5f), pDot);
    }

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
