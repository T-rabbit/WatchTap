package dev.watchtap;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;

import java.util.Locale;

/** Shared colors and SDK-only presentation primitives for WatchTap. */
final class WatchUi {
    static final int BG = 0xFF0B1117;
    static final int SURFACE = 0xFF141D27;
    static final int SURFACE_ALT = 0xFF192532;
    static final int BORDER = 0xFF263544;
    static final int TEXT = 0xFFF1F5F9;
    static final int SECONDARY = 0xFFADBECE;
    static final int MUTED = 0xFF8193A5;
    static final int ACCENT = 0xFF7DE2C3;
    static final int ACCENT_DARK = 0xFF122F2B;
    static final int BLUE = 0xFF8EBBFF;
    static final int VIOLET = 0xFFB7AAFF;
    static final int AMBER = 0xFFF4C97B;
    static final int ROSE = 0xFFFF9CAD;

    private WatchUi() {
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static Drawable surface(Context context, int color, int radiusDp, int borderColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, Math.max(0, radiusDp)));
        if (borderColor != 0) {
            drawable.setStroke(Math.max(1, dp(context, 1)), borderColor);
        }
        return drawable;
    }

    static Drawable ripple(Context context, int color, int radiusDp, int borderColor) {
        Drawable content = surface(context, color, radiusDp, borderColor);
        Drawable mask = surface(context, 0xFFFFFFFF, radiusDp, 0);
        return new RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), content, mask);
    }

    static View icon(Context context, String name, int color, int sizeDp) {
        return new IconView(context, name, color, sizeDp);
    }

    static void button(Button button, boolean primary) {
        Context context = button.getContext();
        int textColor = primary ? BG : TEXT;
        int disabledTextColor = (textColor & 0x00FFFFFF) | 0x80000000;
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setLetterSpacing(0f);
        button.setIncludeFontPadding(false);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(new ColorStateList(
                new int[][] { new int[] { -android.R.attr.state_enabled }, new int[0] },
                new int[] { disabledTextColor, textColor }));
        button.setBackgroundTintList(null);
        button.setBackground(ripple(context, primary ? ACCENT : SURFACE_ALT, 14,
                primary ? 0 : BORDER));
        // Remove the platform elevation animator; the background supplies touch feedback.
        button.setStateListAnimator(null);
        button.setElevation(0f);
        button.setTranslationZ(0f);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        button.setPadding(dp(context, 16), 0, dp(context, 16), 0);
    }

    /** Decorative only: the enclosing action owns its accessibility label. */
    private static final class IconView extends View {
        private final String name;
        private final int desiredSize;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        IconView(Context context, String name, int color, int sizeDp) {
            super(context);
            this.name = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
            desiredSize = Math.max(1, dp(context, sizeDp > 0 ? sizeDp : 24));
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.7f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            setFocusable(false);
            setFocusableInTouchMode(false);
            setClickable(false);
            setLongClickable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setContentDescription(null);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = desiredSize + getPaddingLeft() + getPaddingRight();
            int height = desiredSize + getPaddingTop() + getPaddingBottom();
            setMeasuredDimension(
                    resolveSize(Math.max(width, getSuggestedMinimumWidth()), widthMeasureSpec),
                    resolveSize(Math.max(height, getSuggestedMinimumHeight()), heightMeasureSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth() - getPaddingLeft() - getPaddingRight();
            float height = getHeight() - getPaddingTop() - getPaddingBottom();
            if (width <= 0 || height <= 0) {
                return;
            }
            float scale = Math.min(width, height) / 24f;
            int saved = canvas.save();
            canvas.translate(getPaddingLeft() + (width - 24f * scale) / 2f,
                    getPaddingTop() + (height - 24f * scale) / 2f);
            canvas.scale(scale, scale);
            drawIcon(canvas);
            canvas.restoreToCount(saved);
        }

        private void drawIcon(Canvas canvas) {
            path.reset();
            switch (name) {
                case "watch":
                    canvas.drawRoundRect(5.8f, 6.2f, 18.2f, 17.8f, 3f, 3f, paint);
                    path.moveTo(8.2f, 6.2f);
                    path.lineTo(8.9f, 2.5f);
                    path.lineTo(15.1f, 2.5f);
                    path.lineTo(15.8f, 6.2f);
                    path.moveTo(8.2f, 17.8f);
                    path.lineTo(8.9f, 21.5f);
                    path.lineTo(15.1f, 21.5f);
                    path.lineTo(15.8f, 17.8f);
                    path.moveTo(12f, 9f);
                    path.lineTo(12f, 12f);
                    path.lineTo(14.5f, 13.4f);
                    canvas.drawPath(path, paint);
                    canvas.drawLine(18.2f, 10.2f, 19.5f, 10.2f, paint);
                    break;
                case "moon":
                    path.moveTo(20.1f, 13.7f);
                    path.cubicTo(18.8f, 14.6f, 17.1f, 14.9f, 15.5f, 14.5f);
                    path.cubicTo(12.1f, 13.7f, 10f, 10.3f, 10.8f, 6.9f);
                    path.cubicTo(11f, 5.7f, 11.6f, 4.6f, 12.4f, 3.7f);
                    path.cubicTo(7.8f, 3.5f, 4f, 7.1f, 4f, 11.8f);
                    path.cubicTo(4f, 16.4f, 7.7f, 20.1f, 12.3f, 20.1f);
                    path.cubicTo(15.9f, 20.1f, 19f, 17.6f, 20.1f, 13.7f);
                    path.close();
                    canvas.drawPath(path, paint);
                    break;
                case "bell":
                    path.moveTo(6.5f, 9.5f);
                    path.cubicTo(6.5f, 6.3f, 8.5f, 4.3f, 12f, 4.3f);
                    path.cubicTo(15.5f, 4.3f, 17.5f, 6.3f, 17.5f, 9.5f);
                    path.lineTo(17.5f, 14f);
                    path.lineTo(19.5f, 17f);
                    path.lineTo(4.5f, 17f);
                    path.lineTo(6.5f, 14f);
                    path.close();
                    path.moveTo(9.8f, 20f);
                    path.cubicTo(10.7f, 21.3f, 13.3f, 21.3f, 14.2f, 20f);
                    canvas.drawPath(path, paint);
                    canvas.drawLine(12f, 2.5f, 12f, 4.3f, paint);
                    break;
                case "heart":
                    path.moveTo(12f, 20f);
                    path.lineTo(4.6f, 12.8f);
                    path.cubicTo(1.3f, 9.5f, 3.4f, 4.5f, 7.1f, 4.5f);
                    path.cubicTo(9.1f, 4.5f, 10.8f, 5.7f, 12f, 7.2f);
                    path.cubicTo(13.2f, 5.7f, 14.9f, 4.5f, 16.9f, 4.5f);
                    path.cubicTo(20.6f, 4.5f, 22.7f, 9.5f, 19.4f, 12.8f);
                    path.close();
                    canvas.drawPath(path, paint);
                    break;
                case "sync":
                    path.moveTo(4.1f, 9f);
                    path.cubicTo(5.3f, 5.4f, 9.2f, 3.2f, 12.9f, 3.7f);
                    path.cubicTo(16.1f, 4.1f, 18.8f, 6.2f, 19.9f, 9f);
                    path.moveTo(19.9f, 4.8f);
                    path.lineTo(19.9f, 9f);
                    path.lineTo(15.7f, 9f);
                    path.moveTo(19.9f, 15f);
                    path.cubicTo(18.7f, 18.6f, 14.8f, 20.8f, 11.1f, 20.3f);
                    path.cubicTo(7.9f, 19.9f, 5.2f, 17.8f, 4.1f, 15f);
                    path.moveTo(4.1f, 19.2f);
                    path.lineTo(4.1f, 15f);
                    path.lineTo(8.3f, 15f);
                    canvas.drawPath(path, paint);
                    break;
                case "settings":
                    drawSettings(canvas);
                    break;
                case "tools":
                    path.moveTo(14.7f, 6.3f);
                    path.cubicTo(14.3f, 6.7f, 14.3f, 7.3f, 14.7f, 7.7f);
                    path.lineTo(16.3f, 9.3f);
                    path.cubicTo(16.7f, 9.7f, 17.3f, 9.7f, 17.7f, 9.3f);
                    path.lineTo(21.1f, 5.9f);
                    path.cubicTo(22.1f, 9.1f, 20.3f, 12.5f, 17.1f, 13.5f);
                    path.cubicTo(15.8f, 13.9f, 14.5f, 13.8f, 13.3f, 13.3f);
                    path.lineTo(6.4f, 20.2f);
                    path.cubicTo(5.6f, 21f, 4.2f, 21f, 3.4f, 20.2f);
                    path.cubicTo(2.6f, 19.4f, 2.6f, 18f, 3.4f, 17.2f);
                    path.lineTo(10.3f, 10.3f);
                    path.cubicTo(9.1f, 7.2f, 10.7f, 3.7f, 13.8f, 2.6f);
                    path.cubicTo(15.1f, 2.1f, 16.5f, 2.2f, 17.8f, 2.7f);
                    path.close();
                    canvas.drawPath(path, paint);
                    break;
                case "arrow":
                    canvas.drawLine(4f, 12f, 20f, 12f, paint);
                    path.moveTo(14f, 6f);
                    path.lineTo(20f, 12f);
                    path.lineTo(14f, 18f);
                    canvas.drawPath(path, paint);
                    break;
                case "shield":
                    path.moveTo(12f, 2.8f);
                    path.lineTo(20f, 6.2f);
                    path.lineTo(20f, 11.5f);
                    path.cubicTo(20f, 16f, 16.9f, 19.5f, 12f, 21.3f);
                    path.cubicTo(7.1f, 19.5f, 4f, 16f, 4f, 11.5f);
                    path.lineTo(4f, 6.2f);
                    path.close();
                    path.moveTo(8.7f, 12f);
                    path.lineTo(11f, 14.4f);
                    path.lineTo(15.6f, 9.8f);
                    canvas.drawPath(path, paint);
                    break;
                case "code":
                    path.moveTo(8f, 6f);
                    path.lineTo(2.5f, 12f);
                    path.lineTo(8f, 18f);
                    path.moveTo(16f, 6f);
                    path.lineTo(21.5f, 12f);
                    path.lineTo(16f, 18f);
                    canvas.drawPath(path, paint);
                    canvas.drawLine(13.8f, 4.5f, 10.2f, 19.5f, paint);
                    break;
                case "check":
                    path.moveTo(5f, 12.5f);
                    path.lineTo(9.5f, 17f);
                    path.lineTo(19.5f, 6.5f);
                    canvas.drawPath(path, paint);
                    break;
                case "chevron":
                    path.moveTo(9f, 6f);
                    path.lineTo(15f, 12f);
                    path.lineTo(9f, 18f);
                    canvas.drawPath(path, paint);
                    break;
                case "refresh":
                    path.moveTo(19.6f, 8.8f);
                    path.cubicTo(18.2f, 5.2f, 14.8f, 3.4f, 11.3f, 3.8f);
                    path.cubicTo(7.1f, 4.2f, 3.9f, 7.7f, 3.9f, 12f);
                    path.cubicTo(3.9f, 16.6f, 7.6f, 20.3f, 12.2f, 20.3f);
                    path.cubicTo(15.8f, 20.3f, 18.9f, 17.9f, 20f, 14.6f);
                    path.moveTo(19.6f, 4.5f);
                    path.lineTo(19.6f, 8.8f);
                    path.lineTo(15.3f, 8.8f);
                    canvas.drawPath(path, paint);
                    break;
                case "log":
                    canvas.drawRoundRect(5f, 3f, 19f, 21f, 2f, 2f, paint);
                    canvas.drawLine(8.5f, 8f, 15.5f, 8f, paint);
                    canvas.drawLine(8.5f, 12f, 15.5f, 12f, paint);
                    canvas.drawLine(8.5f, 16f, 13.5f, 16f, paint);
                    break;
                case "download":
                    canvas.drawLine(12f, 3.5f, 12f, 14.8f, paint);
                    path.moveTo(7.5f, 10.3f);
                    path.lineTo(12f, 14.8f);
                    path.lineTo(16.5f, 10.3f);
                    path.moveTo(4.5f, 15.5f);
                    path.lineTo(4.5f, 20f);
                    path.lineTo(19.5f, 20f);
                    path.lineTo(19.5f, 15.5f);
                    canvas.drawPath(path, paint);
                    break;
                default:
                    // Unknown decorative names have no glyph or accessibility announcement.
                    break;
            }
        }

        private void drawSettings(Canvas canvas) {
            for (int tooth = 0; tooth < 8; tooth++) {
                for (int corner = 0; corner < 4; corner++) {
                    float offset;
                    switch (corner) {
                        case 0: offset = -22.5f; break;
                        case 1: offset = -12f; break;
                        case 2: offset = 12f; break;
                        default: offset = 22.5f; break;
                    }
                    float radius = corner == 0 || corner == 3 ? 7.2f : 9.2f;
                    double angle = Math.toRadians(-90f + tooth * 45f + offset);
                    float x = 12f + radius * (float) Math.cos(angle);
                    float y = 12f + radius * (float) Math.sin(angle);
                    if (tooth == 0 && corner == 0) {
                        path.moveTo(x, y);
                    } else {
                        path.lineTo(x, y);
                    }
                }
            }
            path.close();
            canvas.drawPath(path, paint);
            canvas.drawCircle(12f, 12f, 3.2f, paint);
        }
    }
}
