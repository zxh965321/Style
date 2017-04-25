package com.yalin.style;

import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.os.UserManagerCompat;
import android.support.v4.view.GestureDetectorCompat;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.ViewConfiguration;

import com.yalin.style.event.SystemWallpaperSizeChangedEvent;
import com.yalin.style.event.WallpaperActivateEvent;
import com.yalin.style.event.WallpaperDetailOpenedEvent;
import com.yalin.style.render.RenderController;
import com.yalin.style.render.StyleBlurRenderer;

import net.rbgrn.android.glwallpaperservice.GLWallpaperService;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import javax.inject.Inject;

/**
 * YaLin 2016/12/30.
 */
public class StyleWallpaperService extends GLWallpaperService {

    private static final String TAG = "StyleWallpaperService";

    private boolean mInitialized = false;
    private BroadcastReceiver mUnlockReceiver;

    @Override
    public Engine onCreateEngine() {
        return new StyleWallpaperEngine();
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (UserManagerCompat.isUserUnlocked(this)) {
            initialize();
        } else if (VERSION.SDK_INT >= 24) {
            mUnlockReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    initialize();
                    unregisterReceiver(this);
                }
            };
            IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
            registerReceiver(mUnlockReceiver, filter);
        }
    }

    private void initialize() {

        mInitialized = true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mInitialized) {
            //todo
        } else {
            unregisterReceiver(mUnlockReceiver);
        }
    }

    public class StyleWallpaperEngine extends GLEngine implements
            StyleBlurRenderer.Callbacks,
            RenderController.Callbacks {
        private static final long TEMPORARY_FOCUS_DURATION_MILLIS = 3000;

        private StyleBlurRenderer mRenderer;

        @Inject
        RenderController mRenderController;

        GestureDetectorCompat mGestureDetector;

        private Handler mMainThreadHandler = new Handler();

        private boolean mVisible = true;

        // is MainActivity visible
        private boolean mWallpaperDetailMode = false;

        // is last double tab valid
        private boolean mValidDoubleTap = false;

        private BroadcastReceiver mEngineUnlockReceiver;

        private boolean mWallpaperActivate = false;

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);

            mRenderer = new StyleBlurRenderer(StyleWallpaperService.this, this);
            mRenderer.setIsPreview(isPreview());

            setEGLContextClientVersion(2);
            setEGLConfigChooser(8, 8, 8, 0, 0, 0);
            setRenderer(mRenderer);
            setRenderMode(RENDERMODE_WHEN_DIRTY);
            requestRender();

            StyleApplication.getInstance().getApplicationComponent()
                    .inject(this);

            mRenderController.setComponent(mRenderer, this);

            mGestureDetector
                    = new GestureDetectorCompat(StyleWallpaperService.this, mGestureListener);

            if (!isPreview()) {
                if (UserManagerCompat.isUserUnlocked(getApplicationContext())) {
                    activateWallpaper();
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    mEngineUnlockReceiver = new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            activateWallpaper();
                            unregisterReceiver(this);
                        }
                    };
                    IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
                    registerReceiver(mEngineUnlockReceiver, filter);
                }
            }

            EventBus.getDefault().register(this);
        }

        @Override
        public void onDestroy() {
            EventBus.getDefault().unregister(this);
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    if (mRenderer != null) {
                        mRenderer.destroy();
                    }
                }
            });
            mRenderController.destroy();

            if (!isPreview()) {
                deactivateWallpaper();
                if (mEngineUnlockReceiver != null) {
                    unregisterReceiver(mEngineUnlockReceiver);
                }
            }
            super.onDestroy();
        }

        private void activateWallpaper() {
            mWallpaperActivate = true;
            EventBus.getDefault().postSticky(new WallpaperActivateEvent(true));
        }

        private void deactivateWallpaper() {
            if (mWallpaperActivate) {
                EventBus.getDefault().postSticky(new WallpaperActivateEvent(false));
            }
        }

        @Subscribe
        public void onEventMainThread(final WallpaperDetailOpenedEvent e) {
            if (e.isWallpaperDetailOpened() == mWallpaperDetailMode) {
                return;
            }

            mWallpaperDetailMode = e.isWallpaperDetailOpened();
            cancelDelayedBlur();
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    mRenderer.setIsBlurred(!e.isWallpaperDetailOpened(), true);
                }
            });
        }

        @Subscribe
        public void onEventMainThread(WallpaperDetailViewport e) {
            requestRender();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            if (!isPreview()) {
                EventBus.getDefault().postSticky(
                        new SystemWallpaperSizeChangedEvent(width, height));
            }
            mRenderController.reloadCurrentWallpaper();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            mVisible = visible;
            mRenderController.setVisible(visible);
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xOffsetStep,
                                     float yOffsetStep, int xPixelOffset, int yPixelOffset) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep,
                    yOffsetStep, xPixelOffset, yPixelOffset);
            mRenderer.setNormalOffsetX(xOffset);
        }

        @Override
        public void onTouchEvent(MotionEvent event) {
            super.onTouchEvent(event);
            mGestureDetector.onTouchEvent(event);
            delayBlur();
        }

        @Override
        public Bundle onCommand(String action, int x, int y, int z, Bundle extras,
                                boolean resultRequested) {
            if (WallpaperManager.COMMAND_TAP.equals(action) && mValidDoubleTap) {
                queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        mRenderer.setIsBlurred(!mRenderer.isBlurred(), false);
                        delayBlur();
                    }
                });
                mValidDoubleTap = false;
            }
            return super.onCommand(action, x, y, z, extras, resultRequested);
        }

        @Override
        public void queueEventOnGlThread(Runnable runnable) {
            queueEvent(runnable);
        }

        @Override
        public void requestRender() {
            if (mVisible) {
                super.requestRender();
            }
        }

        private void cancelDelayedBlur() {
            mMainThreadHandler.removeCallbacks(mBlurRunnable);
        }

        private void delayBlur() {
            if (mWallpaperDetailMode || mRenderer.isBlurred()) {
                return;
            }
            cancelDelayedBlur();
            mMainThreadHandler.postDelayed(mBlurRunnable, TEMPORARY_FOCUS_DURATION_MILLIS);
        }

        private final Runnable mBlurRunnable = new Runnable() {
            @Override
            public void run() {
                queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        mRenderer.setIsBlurred(true, false);
                    }
                });
            }
        };

        private final Runnable mDoubleTapTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                mValidDoubleTap = false;
            }
        };

        private final GestureDetector.OnGestureListener mGestureListener
                = new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (mWallpaperDetailMode) {
                    return true;
                }

                mValidDoubleTap = true;
                mMainThreadHandler.removeCallbacks(mDoubleTapTimeoutRunnable);
                int timeout = ViewConfiguration.getTapTimeout();
                mMainThreadHandler.postDelayed(mDoubleTapTimeoutRunnable, timeout);
                return true;
            }
        };
    }
}