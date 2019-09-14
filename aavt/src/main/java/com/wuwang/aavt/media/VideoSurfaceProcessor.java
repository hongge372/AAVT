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
import com.wuwang.aavt.utils.GpuUtils;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;

import static android.opengl.GLES20.GL_FRAMEBUFFER;
import static android.opengl.GLES20.GL_TEXTURE0;
import static android.opengl.GLES20.GL_TEXTURE_2D;
import static android.opengl.GLES20.glActiveTexture;
import static android.opengl.GLES20.glBindFramebuffer;
import static android.opengl.GLES20.glBindTexture;
import static android.opengl.GLES20.glCopyTexSubImage2D;

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
    private Observable<RenderBean> observable;
    private final Object LOCK = new Object();

    private ITextureProvider mProvider;

    public VideoSurfaceProcessor() {
        observable = new Observable<>();
        StuCopyTexture stu = new StuCopyTexture();
        stu.pullSave();
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
        //mRenderer = new WrapRenderer(renderer);
    }

    private int saveTextureIndex = 1;

    private void glRun() {

//        Point size = mProvider.open(mInputSurfaceTexture);
//
//        //用于其他的回调
        RenderBean rb = new RenderBean();
//        rb.egl = egl;
//        rb.sourceWidth = mSourceWidth;
//        rb.sourceHeight = mSourceHeight;
        rb.endFlag = false;
        rb.threadId = Thread.currentThread().getId();
        AvLog.d(TAG, "Processor While Loop Entry");
        //要求数据源必须同步填充SurfaceTexture，填充完成前等待
        while (!mProvider.frame() && mGLThreadFlag) {

            //接收数据源传入的时间戳
            rb.timeStamp = mProvider.getTimeStamp();
            //rb.textureTime = mInputSurfaceTexture.getTimestamp();
            observable.notify(rb);
        }
        AvLog.d(TAG, "out of gl thread loop");
        MyTextureFrame frame = new MyTextureFrame();
        frame.endFlg = true;
//        try {
//            texQueue.put(frame);
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
        synchronized (LOCK) {
//            rb.endFlag = true;
//            observable.notify(rb);
//            destroyGL(egl);
            LOCK.notifyAll();
            AvLog.d(TAG, "gl thread exit");
        }
    }


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

