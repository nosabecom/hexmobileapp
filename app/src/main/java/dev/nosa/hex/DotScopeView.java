package dev.nosa.hex;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;

/**
 * Android Canvas adaptation of Hex's MIT-licensed dot_scope simulation. 43 × 9 phosphor dots,
 * paired voice-driven helices, warm pearl → processing blue. See THIRD_PARTY_NOTICES.md for the
 * pinned upstream implementation and deviations.
 */
public final class DotScopeView extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final float[] history = new float[512], light = new float[43 * 9];
  private final float[] phases = {0, 1.7f, 3.1f, 4.8f};
  private final float[] speeds = {.8f, 1.25f, 1.85f, 2.6f},
      base = {.55f, .42f, .3f, .2f},
      spatial = {1.4f, 2.4f, 3.8f, 5.6f};
  private int head;
  private float input,
      envelope,
      amplitude,
      velocity,
      time,
      carrier,
      cool,
      width = 32,
      desiredWidth = 32;
  private long last;
  private float appearanceScale = 1;
  private String state = "idle";
  private boolean recoverable;

  void recoverable(boolean value) {
    recoverable = value;
    invalidate();
  }

  private final RectF plate = new RectF();
  private final Path hex = new Path(), clip = new Path();

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  public DotScopeView(Context c) {
    super(c);
    setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    setFocusable(false);
    setClickable(true);
  }

  void level(float normalized) {
    input = Math.max(0, Math.min(1, normalized));
  }

  void appearance(float scale, float opacity) {
    appearanceScale = scale;
    setAlpha(opacity);
    invalidate();
  }

  void state(String next) {
    state = next;
    desiredWidth = next.equals("recording") ? 112 : next.equals("processing") ? 76 : 32;
    last = SystemClock.elapsedRealtimeNanos();
    invalidate();
  }

  private static float smooth(float a, float b, float x) {
    float t = Math.max(0, Math.min(1, (x - a) / (b - a)));
    return t * t * (3 - 2 * t);
  }

  private float past(float delay) {
    float d = Math.min(510, delay * 240);
    int i = (int) d;
    float k = d - i;
    return history[(head + 1023 - i) % 512] * (1 - k) + history[(head + 1022 - i) % 512] * k;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    long now = SystemClock.elapsedRealtimeNanos();
    float dt = last == 0 ? 1f / 60 : Math.min(.05f, (now - last) / 1e9f);
    last = now;
    boolean reduce = !ValueAnimator.areAnimatorsEnabled();
    boolean live = state.equals("recording"), work = state.equals("processing");
    if (!reduce) time += dt;
    width += (desiredWidth - width) * (1 - (float) Math.exp(-dt / 0.075));
    cool += ((work ? 1 : 0) - cool) * (1 - (float) Math.exp(-dt / .18));
    int steps = Math.max(1, (int) (dt * 240));
    float h = dt / steps;
    for (int i = 0; i < steps; i++) {
      float target = live ? input : 0;
      envelope +=
          (target - envelope) * (1 - (float) Math.exp(-h / (target > envelope ? .022 : .15)));
      float w = (float) (2 * Math.PI * 6.5);
      velocity += (-w * w * (amplitude - envelope) - 2 * .52f * w * velocity) * h;
      amplitude += velocity * h;
      history[head] = Math.max(0, amplitude);
      head = (head + 1) % 512;
      if (!reduce) {
        for (int k = 0; k < 4; k++)
          phases[k] += h * 2 * Math.PI * speeds[k] * (.6 + 1.1 * Math.min(envelope * 1.6, 1));
        carrier += h * (work ? 5 : 2.1);
      }
    }
    float density = getResources().getDisplayMetrics().density,
        scale = density * 1.25f * appearanceScale;
    canvas.save();
    canvas.translate(getWidth() / 2f, getHeight() / 2f);
    canvas.scale(scale, scale);
    float half = width / 2;
    plate.set(-half, -13, half, 13);
    paint.setColor(0xf0111114);
    paint.setStyle(Paint.Style.FILL);
    paint.setShadowLayer(3, 0, 1, 0x66000000);
    canvas.drawRoundRect(plate, 13, 13, paint);
    paint.clearShadowLayer();
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(.6f);
    paint.setColor(0x36ffffff);
    canvas.drawRoundRect(plate, 13, 13, paint);
    paint.setStyle(Paint.Style.FILL);
    if (!live && !work && width < 33) {
      hex.reset();
      for (int k = 0; k < 6; k++) {
        double a = Math.PI * k / 3 - Math.PI / 2;
        float x = (float) Math.cos(a) * 6, y = (float) Math.sin(a) * 6;
        if (k == 0) hex.moveTo(x, y);
        else hex.lineTo(x, y);
      }
      hex.close();
      paint.setColor(0xffdbd4c4);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(.9f);
      canvas.drawPath(hex, paint);
      paint.setStyle(Paint.Style.FILL);
      if (recoverable) {
        paint.setColor(0xffdfc18b);
        canvas.drawCircle(10, -8, 2, paint);
      }
    } else {
      canvas.save();
      clip.reset();
      clip.addRoundRect(plate, 13, 13, Path.Direction.CW);
      canvas.clipPath(clip);
      float breath = reduce ? .5f : .5f + .5f * (float) Math.sin(time * 2 * Math.PI / 5.5);
      float rest = (.55f + .35f * breath) * (work ? .4f : 1);
      float tail = (float) Math.exp(-dt / .6);
      for (int col = 0; col < 43; col++) {
        float x = (col - 21) * 2.6f, xn = (x + half) / width;
        if (Math.abs(x) > half - 2) continue;
        float ends = Math.min(smooth(-half + 3, -half + 17, x), smooth(-half + 3, -half + 17, -x));
        float reach = 1 - .8f * smooth(14, Math.max(15, half), Math.abs(x)),
            a = past(Math.abs(x) / 150);
        float spread = 0;
        for (int k = 0; k < 4; k++)
          spread += a * base[k] * (float) Math.sin(2 * Math.PI * spatial[k] * xn - phases[k]);
        float helix =
            (rest + 3.45f * .55f * a * reach) * (float) Math.sin(2 * Math.PI * 1.6 * xn - carrier);
        float y = 3.45f * (float) Math.tanh((helix + 3.45f * .7f * spread * reach) / 3.45) * ends;
        float bright = (.3f + .7f * Math.min(a * 1.8f, 1)) * (.45f + .55f * ends);
        if (work) {
          float flow = (float) Math.pow(.5 + .5 * Math.cos(2 * Math.PI * (xn - time / .55)), 6);
          bright = .4f + .6f * flow;
        }
        for (int row = 0; row < 9; row++) {
          float yy = row - 4;
          float d = Math.min(Math.abs(yy - y), Math.abs(yy + y));
          float power = (float) Math.exp(-d * d / .45) * bright;
          int n = row * 43 + col;
          light[n] = Math.max(power, light[n] * tail);
          float v = Math.min(1, light[n]);
          paint.setColor(0x22c6bfae);
          canvas.drawCircle(x, yy * 2.6f, .48f, paint);
          int r = (int) (219 + (178 - 219) * cool),
              g = (int) (212 + (199 - 212) * cool),
              b = (int) (196 + (230 - 196) * cool);
          paint.setColor(Color.argb((int) (v * 45), r, g, b));
          canvas.drawCircle(x, yy * 2.6f, 1.7f, paint);
          paint.setColor(Color.argb((int) (v * 255), r, g, b));
          canvas.drawCircle(x, yy * 2.6f, .7f, paint);
        }
      }
      canvas.restore();
    }
    canvas.restore();
    if (isAttachedToWindow() && (live || work || Math.abs(width - desiredWidth) > .1))
      postInvalidateOnAnimation();
  }
}
