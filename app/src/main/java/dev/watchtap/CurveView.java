package dev.watchtap;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.List;

/**
 * 简易折线图。
 *
 * 不引第三方图表库：模块只有 30KB，加库会膨胀好几倍，
 * 而需求只是「看趋势」，自己画足够。
 *
 * 特性：
 *   - 自动缩放 Y 轴到数据范围
 *   - 底部画时间刻度（首/中/尾）
 *   - 数据点稀疏时画点，密集时只画线
 */
final class CurveView extends View {

    private List<History.Point> mData;
    private int mColor = 0xFF60A5FA;

    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float mDp;

    CurveView(Context ctx) {
        super(ctx);
        mDp = ctx.getResources().getDisplayMetrics().density;

        mLine.setStyle(Paint.Style.STROKE);
        mLine.setStrokeWidth(1.6f * mDp);
        mLine.setColor(mColor);
        mLine.setStrokeJoin(Paint.Join.ROUND);
        mLine.setStrokeCap(Paint.Cap.ROUND);

        mFill.setStyle(Paint.Style.FILL);
        mFill.setColor(0x2260A5FA);

        mGrid.setStyle(Paint.Style.STROKE);
        mGrid.setStrokeWidth(1f);
        mGrid.setColor(0xFF1F2937);

        mText.setColor(0xFF6B7280);
        mText.setTextSize(9 * mDp);

        mDot.setStyle(Paint.Style.FILL);
        mDot.setColor(mColor);
    }

    void setData(List<History.Point> data, int color) {
        mData = data;
        mColor = color;
        mLine.setColor(color);
        mDot.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float padL = 30 * mDp, padR = 8 * mDp, padT = 8 * mDp, padB = 16 * mDp;
        float plotW = w - padL - padR;
        float plotH = h - padT - padB;

        // 网格线
        for (int i = 0; i <= 3; i++) {
            float y = padT + plotH * i / 3f;
            canvas.drawLine(padL, y, w - padR, y, mGrid);
        }

        if (mData == null || mData.size() < 2) {
            canvas.drawText("暂无足够数据", padL + 8 * mDp, padT + plotH / 2, mText);
            return;
        }

        long tMin = Long.MAX_VALUE, tMax = Long.MIN_VALUE;
        float vMin = Float.MAX_VALUE, vMax = -Float.MAX_VALUE;
        for (History.Point p : mData) {
            if (p.ts < tMin) tMin = p.ts;
            if (p.ts > tMax) tMax = p.ts;
            if (p.value < vMin) vMin = p.value;
            if (p.value > vMax) vMax = p.value;
        }
        if (tMax == tMin) tMax = tMin + 1;
        if (vMax == vMin) {
            vMax = vMin + 1;
            vMin = vMin - 1;
        }

        // 留 10% 余量
        float range = vMax - vMin;
        vMin -= range * 0.1f;
        vMax += range * 0.1f;

        float sx = plotW / (tMax - tMin);
        float sy = plotH / (vMax - vMin);

        Path line = new Path();
        Path fill = new Path();
        boolean first = true;
        float lastX = 0, lastY = 0;

        for (History.Point p : mData) {
            float x = padL + (p.ts - tMin) * sx;
            float y = padT + plotH - (p.value - vMin) * sy;
            if (first) {
                line.moveTo(x, y);
                fill.moveTo(x, padT + plotH);
                fill.lineTo(x, y);
                first = false;
            } else {
                line.lineTo(x, y);
                fill.lineTo(x, y);
            }
            lastX = x;
            lastY = y;
        }
        fill.lineTo(lastX, padT + plotH);
        fill.close();

        canvas.drawPath(fill, mFill);
        canvas.drawPath(line, mLine);

        // 数据点：稀疏时画
        if (mData.size() <= 60) {
            for (History.Point p : mData) {
                float x = padL + (p.ts - tMin) * sx;
                float y = padT + plotH - (p.value - vMin) * sy;
                canvas.drawCircle(x, y, 1.8f * mDp, mDot);
            }
        }

        // Y 轴标签（最大/最小）
        canvas.drawText(fmt(vMax), 2 * mDp, padT + 8 * mDp, mText);
        canvas.drawText(fmt(vMin), 2 * mDp, padT + plotH, mText);

        // X 轴时间标签
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
        canvas.drawText(f.format(new java.util.Date(tMin)), padL, h - 3 * mDp, mText);
        String end = f.format(new java.util.Date(tMax));
        canvas.drawText(end, w - padR - mText.measureText(end), h - 3 * mDp, mText);
    }

    private String fmt(float v) {
        if (v >= 1000) return String.valueOf((int) v);
        return String.valueOf(Math.round(v * 10) / 10f);
    }
}
