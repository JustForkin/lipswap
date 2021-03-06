package com.androidexperiments.lipflip;

import android.app.Activity;
import android.app.FragmentTransaction;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.AnticipateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.Toast;

import com.androidexperiments.lipflip.data.Constants;
import com.androidexperiments.lipflip.gl.LipFlipRenderer;
import com.androidexperiments.lipflip.utils.AndroidUtils;
import com.androidexperiments.lipflip.utils.FileUtils;
import com.androidexperiments.lipflip.utils.SimpleOnSeekBarChangeListener;
import com.androidexperiments.lipflip.view.FirstTimeView;
import com.androidexperiments.lipflip.view.PaintingView;
import com.androidexperiments.shadercam.fragments.CameraFragment;
import com.androidexperiments.shadercam.gl.CameraRenderer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.ref.WeakReference;

import butterknife.Bind;
import butterknife.ButterKnife;
import butterknife.OnClick;

public class LipSwapActivity extends Activity
        implements
        CameraRenderer.OnRendererReadyListener,
        PaintingView.OnNewBitmapReadyListener
{
    private static final String TAG = LipSwapActivity.class.getSimpleName();

    private static final String TAG_CAMERA_FRAGMENT = "tag_camera_frag";

    //extras to pass stuff via intent
    public static final String EXTRA_RES_ID = "extra_res_id";
    public static final String EXTRA_URI = "extra_uri";

    //key for shared prefs
    private static final String KEY_IS_FIRST_TIME = "key_is_first_time";

    //injections! butterknife!
    @Bind(R.id.texture) TextureView mTextureView;
    @Bind(R.id.paintView) PaintingView mPaintView;
    @Bind(R.id.btn_record) ImageButton mRecordBtn;
    @Bind(R.id.btn_edit) ImageButton mEditBtn;
    @Bind(R.id.edit_container) ViewGroup mEditContainer;
    @Bind(R.id.edit_seek_gamma) SeekBar mSeekGamma;
    @Bind(R.id.edit_seek_hue) SeekBar mSeekHue;

    /**
     * handy stand-alone fragment that encapslates all of Camera2 apis and
     * handles everything neatly (mostly, kinda, sorta)
     */
    private CameraFragment mCameraFragment;

    private Bitmap mInitialBitmap;

    /**
     * renderer specific to what we want to do with the camera and interactions
     */
    private LipFlipRenderer mRenderer;

    private boolean mIsRecording = false;

    /**
     * animations for showing and hiding the edit container,
     * rather than create with code
     */
    private Animation mShowEditAnim,  mHideEditAnim;

    /**
     * Handler for receiving bitmaps from paint view
     */
    private BitmapHandler mBitmapHandler;

    /**
     * Path to the file we'll be outputting from media recorder
     */
    private File mOutputFile;

    private boolean mRestartCamera = false;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ButterKnife.bind(this);

        checkFirstTime();

        setupEditViews();
        setupCameraFragment();
    }

    private void checkFirstTime()
    {
        //get shared
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        boolean isFirstTime = prefs.getBoolean(KEY_IS_FIRST_TIME, true);

        if(isFirstTime) {
            final FirstTimeView view = new FirstTimeView(this);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);

            //add view
            RelativeLayout main = (RelativeLayout) findViewById(R.id.main_container);
            main.addView(view, params);

            //wait for touch
            view.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    view.setOnTouchListener(null);
                    view.animateOut();
                    return true;
                }
            });

            //update prefs so we NEVER SEE IT AGAIN
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean(KEY_IS_FIRST_TIME, false);
            editor.apply();
        }
    }

    private void setupEditViews()
    {
        mEditContainer.setVisibility(View.GONE);

        mShowEditAnim = AnimationUtils.loadAnimation(this, R.anim.show_from_bottom);
        mHideEditAnim = AnimationUtils.loadAnimation(this, R.anim.hide_to_bottom);

        //update image alpha
        mSeekGamma.setOnSeekBarChangeListener(new SimpleOnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if(mRenderer != null)
                    mRenderer.setGamma((float) progress / (float) seekBar.getMax());
            }
        });

        mSeekHue.setOnSeekBarChangeListener(new SimpleOnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if(mRenderer != null)
                    mRenderer.setHue((progress) / 100.f);
            }
        });
    }

    private void setupCameraFragment() {
        if(mCameraFragment == null) {
            mCameraFragment = CameraFragment.getInstance();
            mCameraFragment.setCameraToUse(CameraFragment.CAMERA_FORWARD); //pick which camera u want to use, we default to forward
            mCameraFragment.setTextureView(mTextureView); //set textureview in our inflated layout
        }

        if(getFragmentManager().findFragmentByTag(TAG_CAMERA_FRAGMENT) == null || !getFragmentManager().findFragmentByTag(TAG_CAMERA_FRAGMENT).isAdded()) {
            //add fragment to our setup and let it work its magic
            FragmentTransaction transaction = getFragmentManager().beginTransaction();
            transaction.add(mCameraFragment, TAG_CAMERA_FRAGMENT);
            transaction.commit();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        AndroidUtils.goFullscreen(this);

        mRecordBtn.setEnabled(true);

        if(!mTextureView.isAvailable())
            mTextureView.setSurfaceTextureListener(mTextureListener); //set listener to handle when its ready
        else
            setReady(mTextureView.getSurfaceTexture(), mTextureView.getWidth(), mTextureView.getHeight());
    }

    @Override
    protected void onPause()
    {
        mPaintView.setOnNewBitmapReadyListener(null);
        mTextureView.setSurfaceTextureListener(null);

        if(mIsRecording) {
            stopRecording();
        }
        else {
            //if paused, make sure we shutdown and restart cam on resume
            mRestartCamera = true;
            shutdownCamera();
        }

        super.onPause();
    }

    @OnClick(R.id.btn_record)
    public void onRecordClick()
    {
        if(mIsRecording)
        {
            mRecordBtn.setImageResource(R.drawable.btn_record);
            stopRecording();
        }
        else
        {
            mRecordBtn.setImageResource(R.drawable.btn_record_stop);
            startRecording();
        }

        // since we're gonna go to the next screen OR wait until recording actually begins,
        // lets disable button until those things happen to stop spam bug
        mRecordBtn.setEnabled(false);

        mIsRecording = !mIsRecording;
    }

    private void startRecording()
    {
        /**
         * hack for the spamming record button bug where if u tap the button quickly shit goes wrong
         * disable button on click then wait 250ms and re-enable it to stop.
         */
        mBitmapHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                mRecordBtn.setEnabled(true);
            }
        }, 250);

        mOutputFile = getFile("lipflip_");
        mRenderer.startRecording(mOutputFile);
    }

    private void stopRecording()
    {
        mRenderer.stopRecording();

        shutdownCamera();
    }

    private void shutdownCamera()
    {
        if(mRenderer != null) {
            mCameraFragment.closeCamera();

            mBitmapHandler = null;

            mRenderer.getRenderHandler().sendShutdown();
            mRenderer = null;
        }
    }

    @OnClick(R.id.btn_back)
    public void onBackClick()
    {
        this.finish();
    }

    @OnClick(R.id.btn_edit)
    public void onClickEdit()
    {
        if(mEditBtn.isActivated())
            hideEditControls();
        else
            showEditControls();

        mEditBtn.setActivated(!mEditBtn.isActivated());
    }

    /**
     * Reads the given Intent to check for any images. This can handle whether or not we passed
     * it a specific resId from a list, a URI from the camera roll or freshly taken photo,
     * or if a photo was sent to us from a separate apps ACTION_SEND intent.
     * @param intent
     */
    private void getImage(Intent intent)
    {
        int resId = intent.getIntExtra(EXTRA_RES_ID, -1);

        if(resId == -1)
        {
            Uri imageUri = intent.getParcelableExtra(EXTRA_URI);

            //null because its *hopefully* from share intent
            if(imageUri == null)
            {
                String action = intent.getAction();
                String type = intent.getType();

                if (Intent.ACTION_SEND.equals(action) && type != null && type.startsWith("image/")) {
                    imageUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                }
            }

            //by now we have either a uri from ChooserActivity or an image from another app, so
            //try and decode it!
            try {
                mInitialBitmap = decodeStream(imageUri);// BitmapFactory.decodeStream(getContentResolver().openInputStream(imageUri));
            }
            catch (FileNotFoundException e) {
                Toast.makeText(this, "File not found or is corrupted :(", Toast.LENGTH_LONG).show();
                e.printStackTrace();
                this.finish();
            }
        }
        else {
            //we dont use resIds anymore but leaving for throwbacks
            mInitialBitmap = BitmapFactory.decodeResource(getResources(), resId);
        }
    }

    private Bitmap decodeStream(Uri fileUri) throws FileNotFoundException
    {
        //exif rotation
        ExifInterface exif = null;
        int orientation = 0;// ExifInterface.ORIENTATION_UNDEFINED;
        try {
            // http://stackoverflow.com/questions/20478765/how-to-get-the-correct-orientation-of-the-image-selected-from-the-default-image
            Log.i(TAG, "file uri: " + fileUri);
            exif = new ExifInterface(FileUtils.getPath(this, fileUri));
            orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
        }
        catch (IllegalArgumentException | IOException issue) {
            //this is only responsible for potentially rotating landscape images taken on phone, seems okay
            //if it fails and we move on as normal - altho some devices lag during this process, presumably
            //because drive is downloading the large-res image when its selected?
            Log.e(TAG, "ExifData error. Typically from drive images.\n e: " + issue.getMessage());
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;

        BitmapFactory.decodeStream(getContentResolver().openInputStream(fileUri), null, opts);

        Log.d(TAG, "wh: " + opts.outWidth + ", " + opts.outHeight);
        if(opts.outHeight / 2 > 1280) { //larger than output video
            opts.inSampleSize = 2;
        }

        opts.inJustDecodeBounds = false;

        Bitmap bitmap = BitmapFactory.decodeStream(getContentResolver().openInputStream(fileUri), null, opts);

        if(orientation != ExifInterface.ORIENTATION_UNDEFINED) {
            return AndroidUtils.rotateBitmap(bitmap, orientation);
        }

        return bitmap;
    }


    private void hideEditControls()
    {
        int marginBottom = getResources().getDimensionPixelSize(R.dimen.margin_bottom);

        mEditContainer.startAnimation(mHideEditAnim);
        mEditContainer.setVisibility(View.GONE);

        TranslateAnimation recordAnim = new TranslateAnimation(0, 0, mRecordBtn.getHeight() + marginBottom, 0);
        recordAnim.setDuration(350);
        recordAnim.setStartOffset(300);
        recordAnim.setInterpolator(new OvershootInterpolator());
        mRecordBtn.startAnimation(recordAnim);
        mRecordBtn.setVisibility(View.VISIBLE);
    }

    private void showEditControls()
    {
        int marginBottom = getResources().getDimensionPixelSize(R.dimen.margin_bottom);

        TranslateAnimation recordAnim = new TranslateAnimation(0, 0, 0, mRecordBtn.getHeight() + marginBottom);
        recordAnim.setDuration(350);
        recordAnim.setInterpolator(new AnticipateInterpolator());
        mRecordBtn.startAnimation(recordAnim);
        mRecordBtn.setVisibility(View.GONE);

        mShowEditAnim.setStartOffset(300);
        mEditContainer.startAnimation(mShowEditAnim);
        mEditContainer.setVisibility(View.VISIBLE);
    }

    private void setReady(SurfaceTexture surface, int width, int height)
    {
        getImage(getIntent());

        //then setup our camera renderer
        mRenderer = new LipFlipRenderer(this, surface, mCameraFragment, width, height);

        mBitmapHandler = new BitmapHandler(this);

        mRenderer.setBitmapHandler(mBitmapHandler);
        mRenderer.setInitialBitmap(mInitialBitmap);
        mRenderer.setPaintTexture(mPaintView.getDrawingCopy());
        mRenderer.setOnRendererReadyListener(this);

        mRenderer.start();

        //now that renderer is created and ready, await new bitmaps
        mPaintView.setOnNewBitmapReadyListener(this);

        //initial config if needed
        mCameraFragment.configureTransform(width, height);
    }

    private File getFile(String prefix) {
        return new File(Constants.getStorageDir(this), prefix + System.nanoTime() + ".mp4");
    }

    /**
     * called when new bitmap available from our paint view
     * @param bitmap
     */
    @Override
    public void onNewBitmapReady(Bitmap bitmap)
    {
        //renderer isnt ready, don't push bitmap
        if(mRenderer == null)
            return;

        Bitmap copy = mPaintView.getDrawingCopy();
        mRenderer.updatePaintTexture(copy);
    }

    /**
     * clears our painted view once its pushed to GPU for masking
     */
    private void killDrawing() {
        mPaintView.clear();
    }

    private void showRecordedFile(String filePath)
    {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_FILE_PATH, filePath);
        startActivity(intent);
    }

    @Override
    public void onRendererReady()
    {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "openCamera() called. videoSize: " + mCameraFragment.getVideoSize());
                mCameraFragment.setPreviewTexture(mRenderer.getPreviewTexture());
                mCameraFragment.openCamera();

            }
        });
    }

    @Override
    public void onRendererFinished()
    {
        if(!mRestartCamera)
            runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showRecordedFile(mOutputFile.getAbsolutePath());
            }
        });

        mRestartCamera = false;
    }

    /**
     * {@link android.view.TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link android.view.TextureView}.
     */
    private TextureView.SurfaceTextureListener mTextureListener = new TextureView.SurfaceTextureListener()
    {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, final int width, final int height)
        {
            setReady(surface, width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height)
        {
            Log.d(TAG, "onSurfaceTextureSizeChanged() " + width + ", " + height);

            mCameraFragment.configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture)
        {
            Log.d(TAG, "onSurfaceTextureDestroyed()");
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture)
        {
        }
    };

    /**
     * Handler responsible for notifying UI that our update is complete
     * so we can kill the current drawing on screen
     */
    public static class BitmapHandler extends Handler
    {
        private final WeakReference<LipSwapActivity> mActivity;

        public BitmapHandler(LipSwapActivity activity) {
            mActivity = new WeakReference<>(activity);
        }
        @Override
        public void handleMessage(Message msg) {
            if(msg.what == LipFlipRenderer.MSG_UPDATE_COMPLETE)
                mActivity.get().killDrawing();
        }
    }
}
