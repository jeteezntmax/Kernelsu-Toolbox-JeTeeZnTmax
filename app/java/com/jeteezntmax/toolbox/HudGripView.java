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

    private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private DragHost host;
    private float downX, downY;

    public HudGripView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        pDot.setStyle(Paint.Style.FILL);
    }

    private float dp(float v) { return v * density; }

    public void setDragHost(DragHost h) { host = h; }

    private boolean pressed = false;

    @Override
    protected void onDraw(Canvas cv) {
        // 平时几乎看不见（一个很淡的小点），按住拖动时才亮一点 —— 作者嫌原来那三个点太丑
        float r = pressed ? dp(2.7f) : dp(2.1f);
        pDot.setColor(pressed ? 0x99E8ECF2 : 0x40E8ECF2);
        cv.drawCircle(getWidth() / 2f, getHeight() / 2f, r, pDot);
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
