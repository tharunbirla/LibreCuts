package com.librecuts.editor.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class DrawingOverlayView extends View {
    private Paint drawPaint;
        private Path drawPath;

            public DrawingOverlayView(Context context, AttributeSet attrs) {
                    super(context, attrs);
                            setupDrawing();
                                }

                                    private void setupDrawing() {
                                            drawPath = new Path();
                                                    drawPaint = new Paint();
                                                            drawPaint.setColor(0xFFFF6600); // Personalist Turuncu rengi
                                                                    drawPaint.setAntiAlias(true);
                                                                            drawPaint.setStrokeWidth(8f);
                                                                                    drawPaint.setStyle(Paint.Style.STROKE);
                                                                                            drawPaint.setStrokeJoin(Paint.Join.ROUND);
                                                                                                    drawPaint.setStrokeCap(Paint.Cap.ROUND);
                                                                                                        }

                                                                                                            @Override
                                                                                                                protected void onDraw(Canvas canvas) {
                                                                                                                        super.onDraw(canvas);
                                                                                                                                canvas.drawPath(drawPath, drawPaint);
                                                                                                                                    }

                                                                                                                                        @Override
                                                                                                                                            public boolean onTouchEvent(MotionEvent event) {
                                                                                                                                                    float touchX = event.getX();
                                                                                                                                                            float touchY = event.getY();

                                                                                                                                                                    switch (event.getAction()) {
                                                                                                                                                                                case MotionEvent.ACTION_DOWN:
                                                                                                                                                                                                drawPath.moveTo(touchX, touchY);
                                                                                                                                                                                                                break;
                                                                                                                                                                                                                            case MotionEvent.ACTION_MOVE:
                                                                                                                                                                                                                                            drawPath.lineTo(touchX, touchY);
                                                                                                                                                                                                                                                            break;
                                                                                                                                                                                                                                                                        case MotionEvent.ACTION_UP:
                                                                                                                                                                                                                                                                                        // Çizim tamamlandığında eylem tetiklenebilir
                                                                                                                                                                                                                                                                                                        break;
                                                                                                                                                                                                                                                                                                                    default:
                                                                                                                                                                                                                                                                                                                                    return false;
                                                                                                                                                                                                                                                                                                                                            }
                                                                                                                                                                                                                                                                                                                                                    invalidate();
                                                                                                                                                                                                                                                                                                                                                            return true;
                                                                                                                                                                                                                                                                                                                                                                }
                                                                                                                                                                                                                                                                                                                                                                }
                                                                                                                                                                                                                                                                                                                                                                