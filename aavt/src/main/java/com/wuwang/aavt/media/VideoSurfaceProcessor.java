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
    private Observable<RenderBean> observable;
    private final Object LOCK = new Object();
    private ITextureProvider mProvider;
    RenderBean rb = new RenderBean();
    EglHelper egl = null;
    //StuCopyTexture stu = new StuCopyTexture();
    public WrapRenderer mRenderer;

    public VideoSurfaceProcessor() {
        observable = new Observable<>();
    }

    public void setTextureProvider(ITextureProvider provider) {
        this.mProvider = provider;
    }

    public void start() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                createEGL();
                //while (queueget());
                glRunning();
            }
        }).start();

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

    private void createEGL() {
        egl = new EglHelper();
        boolean ret = egl.createGLESWithSurface(new EGLConfigAttrs(), new EGLContextAttrs(), new SurfaceTexture(1));
        if (!ret) {
            //todo 错误处理
            return;
        }
    }

    private void glRunning() {
//        //用于其他的回调
        rb.egl = egl;
        rb.endFlag = false;
        AvLog.d(TAG, "Processor While Loop Entry");
        //要求数据源必须同步填充SurfaceTexture，填充完成前等待
        while (Mp4Provider.texQueue == null || Mp4Provider.texQueue.size() <= 0) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        //while (queueget()) ;

        while (getFrameEncode2()) ;
    }

    private boolean queueget() {
        while (Mp4Provider.texQueue == null || Mp4Provider.texQueue.size() <= 0) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        MyTextureFrame frame = null;
        try {
            frame = Mp4Provider.texQueue.take();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (frame.endFlg) {
            return false;
        }

        glActiveTexture(GL_TEXTURE0);
        glBindFramebuffer(GL_FRAMEBUFFER, frame.fboId);

        saveTextureIndex++;
        String out = "/sdcard/VideoEdit/pic/pic_thread_" + saveTextureIndex + ".png";
        LVTextureSave.saveToPng(frame.texId, 720, 1280, out);
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return true;
    }


    private boolean getFrameEncode2() {

        while (Mp4Provider.texQueue == null || Mp4Provider.texQueue.size() <= 0) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        MyTextureFrame frame = null;
        try {
            frame = Mp4Provider.texQueue.take();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (frame.endFlg) {
            return false;
        }

        glActiveTexture(GL_TEXTURE0);
        glBindFramebuffer(GL_FRAMEBUFFER, frame.fboId);

        saveTextureIndex++;
        String out = "/sdcard/VideoEdit/pic/pic_thread_process_" + saveTextureIndex + ".png";
        LVTextureSave.saveToPng(frame.texId, 720, 1280, out);
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        rb.sourceWidth = frame.width;
        rb.sourceHeight = frame.height;
        //接收数据源传入的时间戳
        rb.timeStamp = mProvider.getTimeStamp();
        //observable.notify(rb);
        return true;
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

