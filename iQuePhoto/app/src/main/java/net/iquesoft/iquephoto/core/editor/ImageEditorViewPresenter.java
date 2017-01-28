package net.iquesoft.iquephoto.core.editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.MotionEvent;

import com.arellomobile.mvp.InjectViewState;
import com.arellomobile.mvp.MvpPresenter;

import net.iquesoft.iquephoto.core.editor.enums.EditorMode;
import net.iquesoft.iquephoto.core.editor.enums.EditorTool;
import net.iquesoft.iquephoto.core.editor.model.Drawing;
import net.iquesoft.iquephoto.core.editor.model.EditorImage;
import net.iquesoft.iquephoto.core.editor.model.EditorSticker;
import net.iquesoft.iquephoto.core.editor.model.EditorText;
import net.iquesoft.iquephoto.models.Text;
import net.iquesoft.iquephoto.utils.BitmapUtil;
import net.iquesoft.iquephoto.utils.LogHelper;
import net.iquesoft.iquephoto.utils.MatrixUtil;

import java.util.ArrayList;
import java.util.List;

import rx.Observable;
import rx.subjects.PublishSubject;

import static net.iquesoft.iquephoto.core.editor.enums.EditorTool.NONE;

@InjectViewState
public class ImageEditorViewPresenter extends MvpPresenter<EditorView> {
    private float mLastX;
    private float mLastY;

    private boolean mIsImageSet;

    private EditorText mCurrentCheckedText;
    private EditorSticker mCurrentCheckedSticker;
    private EditorTool mCurrentTool = NONE;
    private EditorMode mCurrentMode = EditorMode.NONE;

    private Bitmap mImageBitmap;
    private Bitmap mSupportBitmap;

    private Paint mDrawingPaint;
    private Paint mBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint mFilterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint mAdjustPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint mOverlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Path mDrawingPath = new Path();

    private RectF mSrcRect = new RectF();
    private RectF mDstRect = new RectF();

    private Matrix mImageMatrix = new Matrix();
    private Matrix mSupportMatrix = new Matrix();
    private Matrix mTransformMatrix = new Matrix();

    private List<EditorText> mTexts = new ArrayList<>();
    private List<EditorSticker> mStickers = new ArrayList<>();
    private List<Drawing> mDrawings = new ArrayList<>();
    private List<EditorImage> mImages = new ArrayList<>();

    private EditorListener mEditorListener;

    private PublishSubject<MotionEvent> mTouchSubject = PublishSubject.create();

    ImageEditorViewPresenter(@NonNull Context context) {
        Observable<MotionEvent> touchObservable = mTouchSubject.asObservable();
        mOverlayPaint.setAlpha(150);
        initDrawingPaint();
        initMotionEventObservables(touchObservable);
    }

    void setupImage(float width, float height) {
        if (!mIsImageSet) {
            mDstRect.set(0, 0, width, height);

            LogHelper.logRect("mDstRect", mDstRect);

            mImageMatrix.reset();
            mImageMatrix.setRectToRect(mSrcRect, mDstRect, Matrix.ScaleToFit.CENTER);
            mImageMatrix.mapRect(mSrcRect);

            mTransformMatrix.set(mImageMatrix);

            LogHelper.logRect("mSrcRect", mSrcRect);
            LogHelper.logMatrix("mImageMatrix", mImageMatrix);

            mIsImageSet = true;

            getViewState().setupImage(mImageBitmap, mImageMatrix, mSrcRect);
        }
    }

    void setImageBitmap(Bitmap bitmap) {
        mImageBitmap = bitmap;

        mSrcRect.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
    }

    void viewTouched(MotionEvent event) {
        mTouchSubject.onNext(event);
    }

    void applyChanges() {
        new ImageProcessingTask().execute(mCurrentTool);
    }

    public void setFilter(ColorMatrix colorMatrix) {
        mFilterPaint.setColorFilter(new ColorMatrixColorFilter(colorMatrix));

        getViewState().filterChanged(mFilterPaint);
    }

    void changeFilterIntensity(int value) {
        mFilterPaint.setAlpha(value);

        getViewState().filterChanged(mFilterPaint);
    }

    void changeOverlayIntensity(int value) {
        mOverlayPaint.setAlpha(value);
    }

    void changeBrushSize(float size) {
        mDrawingPaint.setStrokeWidth(size);
    }

    void changeBrushColor(int color) {
        mDrawingPaint.setColor(color);
    }

    public void setFrame(Bitmap bitmap) {
        getViewState().frameChanged(bitmap, getSupportMatrix(bitmap));
    }

    public void setOverlay(Bitmap bitmap) {
        mSupportBitmap = bitmap;

        getViewState().overlayChanged(bitmap, getSupportMatrix(bitmap), mOverlayPaint);
    }

    void addText(Text text) {
        EditorText editorText = new EditorText(text);
        editorText.setX(mSrcRect.centerX());
        editorText.setY(mSrcRect.centerY());

        mTexts.add(editorText);

        getViewState().textAdded(mTexts);
    }

    void addSticker(Bitmap bitmap) {
        mStickers.add(new EditorSticker(bitmap, mSrcRect));

        getViewState().stickerAdded(mStickers);
    }
    
    private void initDrawingPaint() {
        mDrawingPaint = new Paint();
        mDrawingPaint.setStyle(Paint.Style.STROKE);
        mDrawingPaint.setColor(Color.BLUE);
        mDrawingPaint.setStrokeCap(Paint.Cap.ROUND);
        mDrawingPaint.setStrokeJoin(Paint.Join.ROUND);
        mDrawingPaint.setStrokeWidth(Drawing.DEFAULT_STROKE_WIDTH);
    }

    private void initMotionEventObservables(Observable<MotionEvent> touchObservable) {
        initActionDownObservable(touchObservable);
        initActionPointerDownObservable(touchObservable);
        initActionMoveObservable(touchObservable);
        initActionUpObservable(touchObservable);
    }

    private void initActionDownObservable(Observable<MotionEvent> touchObservable) {
        Observable<MotionEvent> actionDownObservable =
                touchObservable.filter(event -> event.getActionMasked() == MotionEvent.ACTION_DOWN);
        actionDownObservable.subscribe(event -> {
            switch (mCurrentTool) {
                case NONE:
                    getViewState().showOriginalImage(true);
                    break;
                case DRAWING:
                    brushActionDown(event);
                    break;
                case TEXT:
                    textActionDown(event);
                    break;
                case STICKERS:
                    stickerActionDown(event);
                    break;
            }
        });
    }

    private void initActionPointerDownObservable(Observable<MotionEvent> touchObservable) {
        Observable<MotionEvent> actionPointerDownObservable =
                touchObservable.filter(event ->
                        event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN
                );
        actionPointerDownObservable.subscribe(event -> {
            /*switch (mCurrentTool) {
                case NONE:
                    getViewState().showOriginalImage(true);
                    break;
                case DRAWING:
                    brushActionDown(event);
                    break;
                case TEXT:
                    textActionDown(event);
                    break;
                case STICKERS:
                    stickerActionDown(event);
                    break;
            }*/
        });
    }

    private void initActionMoveObservable(Observable<MotionEvent> touchObservable) {
        Observable<MotionEvent> actionMoveObservable =
                touchObservable.filter(event -> event.getActionMasked() == MotionEvent.ACTION_MOVE);
        actionMoveObservable.subscribe(event -> {
            switch (mCurrentTool) {
                case NONE:
                    break;
                case DRAWING:
                    brushActionMove(event);
                    break;
                case TEXT:
                    textActionMove(event);
                    break;
                case STICKERS:
                    stickerActionMove(event);
                    break;
            }
        });
    }

    private void initActionUpObservable(Observable<MotionEvent> touchObservable) {
        Observable<MotionEvent> actionUpObservable =
                touchObservable.filter(event -> event.getActionMasked() == MotionEvent.ACTION_UP);
        actionUpObservable.subscribe(event -> {
            mCurrentMode = EditorMode.NONE;
            switch (mCurrentTool) {
                case NONE:
                    getViewState().showOriginalImage(false);
                    break;
                case DRAWING:
                    brushActionUp();
                    break;
                case TEXT:
                    if (mCurrentCheckedText != null) {
                        mCurrentCheckedText.resetHelperFrameOpacity();
                    }
                    break;
                case STICKERS:
                    if (mCurrentCheckedSticker != null) {
                        mCurrentCheckedSticker.setStickerTouched(false);

                        getViewState().updateView();
                    }
                    break;
            }
        });
    }

    public void changeTool(EditorTool tool) {
        mCurrentTool = tool;

        if (!mTexts.isEmpty()) {
            mTexts.clear();
        }
        if (!mStickers.isEmpty()) {
            mStickers.clear();
        }
        //if (!mDrawingPath.isEmpty())
        if (!mDrawings.isEmpty()) {
            mDrawings.clear();
        }

        /*switch (mCurrentTool) {
            case VIGNETTE:
                mVignette.updateRect(mImageRect);
                break;
            case TILT_SHIFT_RADIAL:
                mRadialTiltShift.updateRect(mImageRect);
                return;
        }*/

        getViewState().toolChanged(tool);
    }

    void setEditorListener(EditorListener editorListener) {
        mEditorListener = editorListener;
    }

    void undo() {
        if (!mImages.isEmpty()) {
            mImages.remove(mImages.size() - 1);
            mEditorListener.hasChanges(mImages.size());

            getViewState().imageChanged(getAlteredBitmap());
        }
    }

    private Matrix getSupportMatrix(Bitmap bitmap) {
        float sX = mSrcRect.width() / bitmap.getWidth();
        float sY = mSrcRect.height() / bitmap.getHeight();

        LogHelper.logMatrix("mSupportMatrix before (View)", mSupportMatrix);

        mSupportMatrix.reset();
        mSupportMatrix.postScale(sX, sY);
        mSupportMatrix.postTranslate(mSrcRect.left, mSrcRect.top);

        LogHelper.logMatrix("mSupportMatrix after (View)", mSupportMatrix);

        return mSupportMatrix;
    }

    private void textActionDown(MotionEvent event) {
        for (int i = mTexts.size() - 1; i >= 0; i--) {
            EditorText editorText = mTexts.get(i);

            if (editorText.isInside(event)) {
                mCurrentCheckedText = editorText;
                mCurrentMode = EditorMode.MOVE;

                mCurrentCheckedText.setHelperFrameOpacity();

                mLastX = event.getX();
                mLastY = event.getY();

                return;
            } else if (editorText.isInDeleteHandleButton(event)) {
                mCurrentCheckedText = null;
                mCurrentMode = EditorMode.NONE;

                mTexts.remove(i);
                //invalidate();
                return;
            } else if (editorText.isInResizeAndScaleHandleButton(event)) {
                mCurrentCheckedText = editorText;

                mCurrentCheckedText.setHelperFrameOpacity();

                mLastX = editorText.getRotateAndScaleHandleDstRect().centerX();
                mLastY = editorText.getRotateAndScaleHandleDstRect().centerY();

                mCurrentMode = EditorMode.ROTATE_AND_SCALE;
                return;
            } else if (editorText.isInTransparencyHandleButton(event)) {
                mCurrentCheckedText = editorText;

                mLastX = editorText.getResizeHandleDstRect().centerX();
                mLastY = editorText.getResizeHandleDstRect().centerY();

                mCurrentMode = EditorMode.NONE;
                return;
            } else if (editorText.isInFrontHandleButton(event)) {
                EditorText temp = mTexts.remove(i);
                mTexts.add(temp);

                //invalidate();
                return;
            }
        }
        mCurrentCheckedText = null;
        mCurrentMode = EditorMode.NONE;
    }

    private void stickerActionDown(MotionEvent event) {
        for (int i = mStickers.size() - 1; i >= 0; i--) {
            EditorSticker editorSticker = mStickers.get(i);

            if (editorSticker.isInside(event)) {
                mCurrentCheckedSticker = editorSticker;
                mCurrentMode = EditorMode.MOVE;

                mCurrentCheckedSticker.setStickerTouched(true);

                mLastX = event.getX();
                mLastY = event.getY();

                return;
            } else if (editorSticker.isInDeleteHandleButton(event)) {
                mCurrentCheckedSticker = null;

                mCurrentMode = EditorMode.NONE;

                mStickers.remove(i);

                getViewState().updateView();
                return;
            } else if (editorSticker.isInScaleAndRotateHandleButton(event)) {
                mCurrentCheckedSticker = editorSticker;
                mCurrentMode = EditorMode.ROTATE_AND_SCALE;

                mCurrentCheckedSticker.setStickerTouched(true);

                mLastX = event.getX();
                mLastY = event.getY();
                return;
            } else if (editorSticker.isInFrontHandleButton(event)) {
                mCurrentMode = EditorMode.NONE;

                mStickers.add(mStickers.remove(i));

                getViewState().updateView();
                return;
            } else if (editorSticker.isInTransparencyHandleButton(event)) {

            }
        }

        mCurrentCheckedSticker = null;

        mCurrentMode = EditorMode.NONE;
    }

    private void brushActionDown(MotionEvent event) {
        Log.i("Drawing", "Brush down");
        mLastX = event.getX();
        mLastY = event.getY();

        mDrawingPath.reset();

        mDrawingPath.moveTo(mLastX, mLastY);

        getViewState().updateDrawing(mDrawingPaint, mDrawingPath);
    }

    private void brushActionMove(MotionEvent event) {
        Log.i("Drawing", "Brush move");

        float dX = event.getX() + mLastX;
        float dY = event.getY() + mLastY;

        mDrawingPath.quadTo(mLastX, mLastY, dX / 2, dY / 2);

        mLastX = event.getX();
        mLastY = event.getY();

        getViewState().updateDrawing(mDrawingPaint, mDrawingPath);
    }

    private void brushActionUp() {
        Log.i("Drawing", "Brush up");

        mDrawingPath.lineTo(mLastX, mLastY);
        mDrawings.add(new Drawing(new Paint(mDrawingPaint), new Path(mDrawingPath), null));

        mDrawingPath.reset();

        getViewState().updateDrawing(mDrawings);
    }

    private void textActionMove(MotionEvent event) {
        if (mCurrentCheckedText != null) {
            switch (mCurrentMode) {
                case MOVE:
                    float distanceX = event.getX() - mLastX;
                    float distanceY = event.getY() - mLastY;

                    float newX = mCurrentCheckedText.getX() + distanceX;
                    float newY = mCurrentCheckedText.getY() + distanceY;

                    mCurrentCheckedText.setX(newX);
                    mCurrentCheckedText.setY(newY);

                    mLastX = event.getX();
                    mLastY = event.getY();

                    break;
                case ROTATE_AND_SCALE:
                    mCurrentCheckedText.updateRotateAndScale(
                            getDeltaX(event),
                            getDeltaY(event)
                    );

                    mLastX = event.getX();
                    mLastY = event.getY();

                    break;
            }

            getViewState().updateView();
        }
    }

    private void stickerActionMove(MotionEvent event) {
        if (mCurrentCheckedSticker != null) {
            switch (mCurrentMode) {
                case MOVE:
                    mCurrentCheckedSticker.actionMove(
                            getDeltaX(event),
                            getDeltaY(event)
                    );

                    mLastX = event.getX();
                    mLastY = event.getY();

                    break;
                case ROTATE_AND_SCALE:
                    mCurrentCheckedSticker.updateRotateAndScale(
                            getDeltaX(event),
                            getDeltaY(event)
                    );

                    mLastX = event.getX();
                    mLastY = event.getY();

                    break;
            }

            getViewState().updateView();
        }
    }

    private Bitmap getAlteredBitmap() {
        if (!mImages.isEmpty()) {
            return mImages.get(mImages.size() - 1).getBitmap();
        }

        return mImageBitmap;
    }

    private float getDeltaX(MotionEvent event) {
        return event.getX() - mLastX;
    }

    private float getDeltaY(MotionEvent event) {
        return event.getY() - mLastY;
    }

    private class ImageProcessingTask extends AsyncTask<EditorTool, Void, Bitmap> {
        private int mImageHeight;
        private int mImageWidth;

        private Bitmap mBitmap;
        private Canvas mCanvas;

        private EditorTool mExecutedTool;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();

            mEditorListener.imageProcessingStarted();
        }

        @Override
        protected Bitmap doInBackground(EditorTool... editorTools) {
            mBitmap = getAlteredBitmap().copy(getAlteredBitmap().getConfig(), true);

            mCanvas = new Canvas(mBitmap);

            mImageHeight = mBitmap.getHeight();
            mImageWidth = mBitmap.getWidth();

            mExecutedTool = editorTools[0];

            Log.i("ImageProcessing", "Image Processing Started with \"" + mExecutedTool.name() + "\".");

            switch (mExecutedTool) {
                case NONE:
                    break;
                case FILTERS:
                    mCanvas.drawBitmap(mBitmap, 0, 0, mFilterPaint);
                    break;
                case OVERLAY:
                    mCanvas.drawBitmap(
                            mSupportBitmap,
                            getSupportMatrix(mSupportBitmap),
                            mOverlayPaint
                    );
                    break;
                case TEXT:
                    drawTexts(mCanvas);
                    break;
                case DRAWING:
                    for (Drawing drawing : mDrawings) {
                        drawing.getPaint().setStrokeWidth(
                                drawing.getPaint().
                                        getStrokeWidth() / MatrixUtil.getScale(mImageMatrix)
                        );
                        mCanvas.drawPath(drawing.getPath(), drawing.getPaint());
                    }
                    break;
                case STICKERS:
                    drawStickers(mCanvas);
                    break;
                case FRAMES:
                    mCanvas.drawBitmap(
                            mSupportBitmap,
                            getSupportMatrix(mSupportBitmap),
                            mBitmapPaint
                    );
                    break;
                case VIGNETTE:
                    //mVignette.prepareToDraw(mCanvas, mImageMatrix);
                    //mVignette.draw(mCanvas);
                    break;
                case TRANSFORM_STRAIGHTEN:
                    mCanvas.drawBitmap(mBitmap, mTransformMatrix, mBitmapPaint);
                    break;
                default:
                    mCanvas.drawBitmap(mBitmap, 0, 0, mAdjustPaint);
                    break;
                /*case TEXT:
                    drawTexts(mCanvas);
                    break;
                case DRAWING:
                    break;
                case TILT_SHIFT_RADIAL:
                    break;
                case VIGNETTE:
                    // TODO: Draw vignette on image with original size.
                    mEditorVignette.prepareToDraw(mCanvas, mMatrix);
                    mEditorVignette.draw(mCanvas);
                    break;

                case TRANSFORM_STRAIGHTEN:
                    mCanvas.save(Canvas.CLIP_SAVE_FLAG);
                    mCanvas.setMatrix(getTransformStraightenMatrix(mTransformStraightenValue));
                    mCanvas.drawBitmap(mBitmap, 0, 0,
//                            getTransformStraightenMatrix(mTransformStraightenValue),
                            mImagePaint);
                    mCanvas.restore();
                    break;*/
            }

            return mBitmap;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            super.onPostExecute(bitmap);
            mImages.add(new
                    EditorImage(mExecutedTool, bitmap)
            );

            Log.i("ImageProcessingTask", "Image Processing Finished with \"" + mExecutedTool.name() + "\"");

            mEditorListener.hasChanges(mImages.size());
            mEditorListener.imageProcessingFinished();

            getViewState().imageChanged(getAlteredBitmap());
        }

        private void drawStickers(Canvas canvas) {
            for (EditorSticker sticker : mStickers) {
                sticker.prepareToDraw(mImageMatrix);
                sticker.draw(canvas);
            }

            Log.i("ImageProcessing", "Draws Stickers in background (count " + mStickers.size() + ")");
        }

        private void drawTexts(Canvas canvas) {
            for (EditorText text : mTexts) {
                text.prepareToDraw(mImageMatrix);
                text.draw(canvas);
            }
        }

        private Matrix getSupportMatrix(@NonNull Bitmap bitmap) {
            float height = bitmap.getHeight();
            float width = bitmap.getWidth();

            float sX = mImageWidth / width;
            float sY = mImageHeight / height;

            LogHelper.logBitmap("Overlay or Frame", bitmap);
            LogHelper.logMatrix("mSupportMatrix before (InBackground)", mSupportMatrix);

            mSupportMatrix.reset();
            mSupportMatrix.postScale(sX, sY);
            mSupportMatrix.postTranslate(0, 0);

            LogHelper.logMatrix("mSupportMatrix after (InBackground)", mSupportMatrix);

            return mSupportMatrix;
        }
    }
}