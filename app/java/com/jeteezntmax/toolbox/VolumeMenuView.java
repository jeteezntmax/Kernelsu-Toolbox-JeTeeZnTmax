package com.jeteezntmax.toolbox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

/**
 * 音量键呼出的「游戏加速」菜单（画布画的，浮在所有应用上面）。
 *
 *  游戏加速              com.x.y
 *  ──────────────────────────────
 *  总开关                开
 *  CPU 锁频              2.99G
 *  调速器                performance
 *  线程绑定              超大核
 *  刷新率                120Hz
 *  ──────────────────────────────
 *  [应用]   [还原]   [关闭]
 *
 * 点参数那一行 = 循环切档；点按钮 = 触发回调。
 * 值列表由外面（PerfService）从模块脚本里读出来喂进来。
 */
public class VolumeMenuView extends View {

    public interface OnAction {
        void onCycle(String key, String value);   // 参数行切档
        void onToggle(String key, String value);  // 总开关
        void onApply();
        void onRestore();
        void onBoost();
        void onClose();
    }

    private final Paint pBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBd = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTx = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLab = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pVal = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBtn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBtnTx = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float density, rowH, fs, fsSmall;
    private String title = "游戏加速";
    private String appName = "";
    private boolean enabled = false;
    /* 参数行：key / 显示名 / 当前值 / 候选值(逗号分隔) */
    private final ArrayList<String[]> rows = new ArrayList<String[]>();
    private final ArrayList<RectF> hit = new ArrayList<RectF>();

    public VolumeMenuView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        fs = sp(13.5f);
        fsSmall = sp(11f);
        rowH = dp(34);
        pBg.setColor(0xF2070A0F);
        pBg.setStyle(Paint.Style.FILL);
        pBd.setColor(0x66FFFFFF);
        pBd.setStyle(Paint.Style.STROKE);
        pBd.setStrokeWidth(dp(1));
        pTx.setColor(0xFFFFFFFF); pTx.setTextSize(fs); pTx.setFakeBoldText(true);
        pLab.setColor(0xFFB9C3CF); pLab.setTextSize(fs);
        pVal.setColor(0xFF6FB4FF); pVal.setTextSize(fs); pVal.setFakeBoldText(true);
        pBtn.setColor(0x22FFFFFF); pBtn.setStyle(Paint.Style.FILL);
        pBtnTx.setColor(0xFFE8ECF2); pBtnTx.setTextSize(sp(12.5f));
    }

    private float dp(float v) { return v * density; }
    private float sp(float v) { return v * getResources().getDisplayMetrics().scaledDensity; }

    public void setTitle(String t) { title = t; invalidate(); }
    public void setApp(String a) { appName = a == null ? "" : a; invalidate(); }
    public void setEnabled(boolean e) { enabled = e; invalidate(); }

    /** rows: 每一项 = {key, 显示名, 当前值, "候选1,候选2,..."} */
    public void setRows(ArrayList<String[]> r) {
        rows.clear();
        rows.addAll(r);
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = (int) Math.min(getResources().getDisplayMetrics().widthPixels - dp(20), dp(286));
        int h = (int) (dp(44) + rowH * (rows.size() + 1) + dp(54));
        setMeasuredDimension(resolveSize(w, wSpec), resolveSize(h, hSpec));
    }

    /** 第 i 行的顶部（i 从 0 开始，0 = 总开关） */
    private float rowTop(int i) { return dp(44) + i * rowH; }
    /** 文字基线 = 行顶 + 行的 2/3（真正居中；以前直接把行边界当基线，所以看着像穿模） */
    private float baseline(float top) { return top + rowH * 0.66f; }

    @Override
    protected void onDraw(Canvas cv) {
        float W = getWidth(), H = getHeight();
        RectF box = new RectF(dp(0.5f), dp(0.5f), W - dp(0.5f), H - dp(0.5f));
        cv.drawRoundRect(box, dp(12), dp(12), pBg);
        cv.drawRoundRect(box, dp(12), dp(12), pBd);

        cv.drawText(title, dp(16), dp(28), pTx);
        if (!appName.isEmpty()) {
            pLab.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(appName, W - dp(16), dp(28), pLab);
            pLab.setTextAlign(Paint.Align.LEFT);
        }
        cv.drawLine(dp(12), dp(38), W - dp(12), dp(38), pBd);
        /* 总开关 */
        float t0 = rowTop(0);
        cv.drawText("总开关", dp(16), baseline(t0), pLab);
        String st = enabled ? "开" : "关";
        cv.drawText(st, W - dp(16) - pVal.measureText(st), baseline(t0), enabled ? pBtnTx : pLab);
        /* 参数行 */
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            float top = rowTop(i + 1);
            cv.drawLine(dp(12), top - dp(6), W - dp(12), top - dp(6), pBd);
            cv.drawText(r[1], dp(16), baseline(top), pLab);
            String v = r[2] == null || r[2].isEmpty() ? "不动" : r[2];
            cv.drawText(v, W - dp(16) - pVal.measureText(v), baseline(top), pVal);
        }
        float lastTop = rowTop(rows.size());
        cv.drawLine(dp(12), lastTop + rowH - dp(6), W - dp(12), lastTop + rowH - dp(6), pBd);
        /* 按钮 */
        float by = H - dp(42), bw = (W - dp(32) - dp(30)) / 4f;
        String[] names = {"应用", "还原", "清后台", "关闭"};
        hit.clear();
        for (int i = 0; i < 4; i++) {
            RectF b = new RectF(dp(16) + i * (bw + dp(10)), by, dp(16) + i * (bw + dp(10)) + bw, by + dp(28));
            cv.drawRoundRect(b, dp(9), dp(9), pBtn);
            float tw = pBtnTx.measureText(names[i]);
            cv.drawText(names[i], b.centerX() - tw / 2f, b.centerY() + fs * 0.36f, pBtnTx);
            hit.add(b);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
        float x = e.getX(), y = e.getY(), W = getWidth(), H = getHeight();
        /* 按钮 */
        for (int i = 0; i < hit.size() && i < 4; i++) {
            if (hit.get(i).contains(x, y)) {
                if (i == 0) cb.onApply();
                else if (i == 1) cb.onRestore();
                else if (i == 2) cb.onBoost();
                else cb.onClose();
                return true;
            }
        }
        /* 总开关 */
        float ty = rowTop(0);
        if (y >= ty && y <= ty + rowH) {
            cb.onToggle("enabled", enabled ? "0" : "1");
            return true;
        }
        /* 参数行 */
        for (int i = 0; i < rows.size(); i++) {
            float ry = rowTop(i + 1);
            if (y >= ry && y <= ry + rowH) {
                String[] r = rows.get(i);
                String[] opts = (r[3] == null ? "" : r[3]).split(",");
                if (opts.length == 0 || opts.length == 1 && opts[0].isEmpty()) return true;
                int cur = 0;
                for (int k = 0; k < opts.length; k++) if (opts[k].equals(r[2])) cur = k;
                String next = opts[(cur + 1) % opts.length];
                cb.onCycle(r[0], next);
                return true;
            }
        }
        return true;
    }

    private OnAction cb;
    public void setOnAction(OnAction a) { cb = a; }
}
