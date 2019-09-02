/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wuwang.aavt.media;

import android.annotation.TargetApi;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Build;
import android.util.Log;

import com.wuwang.aavt.core.IObserver;
import com.wuwang.aavt.core.Observable;
import com.wuwang.aavt.core.Renderer;
import com.wuwang.aavt.egl.EGLConfigAttrs;
import com.wuwang.aavt.egl.EGLContextAttrs;
import com.wuwang.aavt.egl.EglHelper;
import com.wuwang.aavt.gl.FrameBuffer;
import com.wuwang.aavt.log.AvLog;
import com.wuwang.aavt.media.hard.LVTextureSave;
import com.wuwang.aavt.utils.GpuUtils;

import static android.opengl.GLES20.GL_RGBA;
import static android.opengl.GLES20.GL_TEXTURE_2D;
import static android.opengl.GLES20.glBindTexture;
import static android.opengl.GLES20.glCheckFramebufferStatus;
import static android.opengl.GLES20.glFinish;
import static android.opengl.GLES20.glTexImage2D;

/**
 * VideoSurfaceProcessor 视频流图像处理类，以{@link ITextureProvider}作为视频流图像输入。通过设置{@link IObserver}
 * 来接收处理完毕的{@link RenderBean}，并做相应处理，诸如展示、编码等。
 *
 * @author wuwang
 * @version v1.0 2017:10:27 08:37
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
public class VideoSurfaceProcessor {

    private String TAG = getClass().getSimpleName();

    private boolean mGLThreadFlag = false;
    private Thread mGLThread;
    private WrapRenderer mRenderer, sharedRenderer;
    private Observable<RenderBean> observable;
    private final Object LOCK = new Object();

    private ITextureProvider mProvider;
    private EglHelper egl, sharedEgl;
    private int mInputSurfaceTextureId;
    private int canSave = 0;
    private int sharedTextureId = -1;
    FrameBuffer sourceFrame = new FrameBuffer();
    private SurfaceTexture mInputSurfaceTexture;
    private SurfaceTexture forEglSurfaceTexture = new SurfaceTexture(1);

    public VideoSurfaceProcessor() {
        observable = new Observable<>();

        new Thread(new Runnable() {
            @Override
            public void run() {
                Log.v(TAG, "share opengl stu, not shareContex has data");
                sharedEgl = new EglHelper();
                boolean ret = sharedEgl.createGLESWithSurface(new EGLConfigAttrs(), new EGLContextAttrs(), forEglSurfaceTexture);
                if (!ret) {
                    //todo 错误处理
                    return;
                }
                sharedTextureId = GpuUtils.createTextureID(false);
                if(EGL14.eglGetError()<0){
                    Log.v(TAG, "XXXXXXXXXyayayay ,get opengl err");
                }

                Log.v(TAG, "get two texture shareId:" + sharedTextureId + " runderer: " + mInputSurfaceTextureId);
                if (sharedRenderer == null) {
                    sharedRenderer = new WrapRenderer(null);
                }
                sharedRenderer.create();
                sharedRenderer.sizeChanged(720, 1280);
                sharedRenderer.setFlag(mProvider.isLandscape() ? WrapRenderer.TYPE_CAMERA : WrapRenderer.TYPE_MOVE);
                while (true) {
                    while (canSave == 0) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    if(EGL14.eglGetError()<0){
                        Log.v(TAG, "XXXXXXXXXyayayay ,get opengl err");
                    }

                    sharedEgl.makeCurrent();
                    if(EGL14.eglGetError()<0){
                        Log.v(TAG, "XXXXXXXXXyayayay ,get opengl err");
                    }

                    GLES20.glActiveTexture(sharedTextureId);
                    //Log.v(TAG, "bind teximage to save !!!!!!!!!!!!!!!!!!");
                    //GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, sharedTextureId);
                    glBindTexture(GL_TEXTURE_2D, sharedTextureId);
                    //glBindFramebuffer(GL_FRAMEBUFFER, fboHandle);
                    if(EGL14.eglGetError()<0){
                        Log.v(TAG, "XXXXXXXXXyayayay ,get opengl err");
                    }

                    sourceFrame.bindFrameBuffer();
                    //GLES20.glViewport(0, 0, 720, 1280);
                    //sharedRenderer.draw(sharedTextureId);
                    //sharedRenderer.draw(sharedTextureId);
                    //GLES20.glViewport(0, 0, mSourceWidth, mSourceHeight);
                    if(EGL14.eglGetError()<0){
                        Log.v(TAG, "XXXXXXXXXyayayay ,get opengl err");
                    }

                    String path = "/sdcard/VideoEdit/pic/yaooya_father_" + rundererSaveIndex++ + ".png";
                    LVTextureSave.saveToPng(mInputSurfaceTextureId, 720, 1280, path);
                    sourceFrame.unBindFrameBuffer();
                    EGL14.eglMakeCurrent(egl.getDisplay(), EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    // glFinish();
                    canSave = 0;
                }
            }
        }).start();
    }

    public void setTextureProvider(ITextureProvider provider) {
        this.mProvider = provider;
    }

    public void start() {
        synchronized (LOCK) {
            if (!mGLThreadFlag) {
                if (mProvider == null) {
                    return;
                }
                mGLThreadFlag = true;
                mGLThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        glRun();
                    }
                });
                mGLThread.start();
                try {
                    LOCK.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void stop() {
        synchronized (LOCK) {
            if (mGLThreadFlag) {
                mGLThreadFlag = false;
                mProvider.close();
                try {
                    LOCK.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void setRenderer(Renderer renderer) {
        mRenderer = new WrapRenderer(renderer);
    }

    private void glRun() {
        while (EglHelper.shareContex == null) {
            //Log.v(TAG, "share opengl stu, shareContex is null");
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        Log.v(TAG, "share opengl stu, --shareContex has data");
        egl = new EglHelper();
        boolean ret = egl.createGLESWithSurface(new EGLConfigAttrs(), new EGLContextAttrs(), forEglSurfaceTexture);
        if (!ret) {
            //todo 错误处理
            return;
        }
        mInputSurfaceTextureId = GpuUtils.createTextureID(true);
        mInputSurfaceTexture = new SurfaceTexture(mInputSurfaceTextureId);

        Point size = mProvider.open(mInputSurfaceTexture);
        AvLog.d(TAG, "Provider Opened . data size (x,y)=" + size.x + "/" + size.y);
        if (size.x <= 0 || size.y <= 0) {
            //todo 错误处理
            destroyGL(egl);
            synchronized (LOCK) {
                LOCK.notifyAll();
            }
            return;
        }
        int mSourceWidth = size.x;
        int mSourceHeight = size.y;
        synchronized (LOCK) {
            LOCK.notifyAll();
        }
        //要求数据源提供者必须同步返回数据大小
        if (mSourceWidth <= 0 || mSourceHeight <= 0) {
            error(1, "video source return inaccurate size to SurfaceTextureActuator");
            return;
        }

        if (mRenderer == null) {
            mRenderer = new WrapRenderer(null);
        }
        mRenderer.create();
        mRenderer.sizeChanged(mSourceWidth, mSourceHeight);
        mRenderer.setFlag(mProvider.isLandscape() ? WrapRenderer.TYPE_CAMERA : WrapRenderer.TYPE_MOVE);

        //用于其他的回调
        RenderBean rb = new RenderBean();
        rb.egl = egl;
        rb.sourceWidth = mSourceWidth;
        rb.sourceHeight = mSourceHeight;
        rb.endFlag = false;
        rb.threadId = Thread.currentThread().getId();
        AvLog.d(TAG, "Processor While Loop Entry");
        //要求数据源必须同步填充SurfaceTexture，填充完成前等待
        while (!mProvider.frame() && mGLThreadFlag) {
            mInputSurfaceTexture.updateTexImage();
            mInputSurfaceTexture.getTransformMatrix(mRenderer.getTextureMatrix());
            AvLog.d(TAG, "timestamp:" + mInputSurfaceTexture.getTimestamp());
            sourceFrame.bindFrameBuffer(mSourceWidth, mSourceHeight);
            GLES20.glViewport(0, 0, mSourceWidth, mSourceHeight);

            if (sharedTextureId > 0) {
                sharedRenderer.draw(sharedTextureId);
            }
            mRenderer.draw(mInputSurfaceTextureId);
            sourceFrame.unBindFrameBuffer();

            //String path2 = "/sdcard/VideoEdit/pic/shared_runder_" + rundererSaveIndex++ + ".png";
            //LVTextureSave.saveToPng(sharedTextureId, 720, 1280, path2);
            glBindTexture(GL_TEXTURE_2D, 3);
            EGL14.eglMakeCurrent(egl.getDisplay(), EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            canSave = 1;
            while (canSave == 1) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            egl.makeCurrent();
            //sharedEgl.makeCurrent();
            //glFinish();
            rb.textureId = sourceFrame.getCacheTextureId();
            //接收数据源传入的时间戳
            rb.timeStamp = mProvider.getTimeStamp();
            rb.textureTime = mInputSurfaceTexture.getTimestamp();
            sharedTextureId = rb.textureId;
            //String path = "/sdcard/VideoEdit/pic/runder_" + rundererSaveIndex++ + ".png";
            //LVTextureSave.saveToPng(rb.textureId, 720, 1280, path);

            observable.notify(rb);
        }
        AvLog.d(TAG, "out of gl thread loop");
        synchronized (LOCK) {
            rb.endFlag = true;
            observable.notify(rb);
            mRenderer.destroy();
            destroyGL(egl);
            LOCK.notifyAll();
            AvLog.d(TAG, "gl thread exit");
        }
    }

    private int rundererSaveIndex = 0;

    private void destroyGL(EglHelper egl) {
        mGLThreadFlag = false;
        EGL14.eglMakeCurrent(egl.getDisplay(), EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroyContext(egl.getDisplay(), egl.getDefaultContext());
        EGL14.eglTerminate(egl.getDisplay());
    }

    public void addObserver(IObserver<RenderBean> observer) {
        observable.addObserver(observer);
    }

    protected void error(int id, String msg) {

    }
}

