package dev.nosa.hex;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** A small circular arrow, with the same plate and pearl ink as the idle bubble. */
final class RetryView extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF arc = new RectF(-6, -6, 6, 6);
  private final Path arrow = new Path();
  private float scale = 1;

  RetryView(Context context) {
    super(context);
    setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    setClickable(true);
    setFocusable(false);
    setContentDescription("Retry last transcription");
    setTooltipText("Retry last transcription");
  }

  void appearance(float size, float opacity) {
    scale = size;
    setAlpha(opacity);
    invalidate();
  }

  @Override public boolean performClick() {
    super.performClick();
    return true;
  }

  @Override protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    canvas.save();
    canvas.translate(getWidth() / 2f, getHeight() / 2f);
    float unit = getResources().getDisplayMetrics().density * 1.25f * scale;
    canvas.scale(unit, unit);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(isPressed() ? 0xf02b2b30 : 0xf0111114);
    paint.setShadowLayer(3, 0, 1, 0x66000000);
    canvas.drawCircle(0, 0, 13, paint);
    paint.clearShadowLayer();
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(.6f);
    paint.setColor(0x36ffffff);
    canvas.drawCircle(0, 0, 13, paint);
    paint.setStrokeWidth(1.35f);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    paint.setColor(0xffdbd4c4);
    canvas.drawArc(arc, -55, 325, false, paint);
    arrow.reset();
    arrow.moveTo(-2.8f, -8.8f);
    arrow.lineTo(0, -6);
    arrow.lineTo(-2.8f, -3.2f);
    canvas.drawPath(arrow, paint);
    canvas.restore();
  }
}
