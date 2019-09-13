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
    public static int maxQueueSize = 8;
    public static LinkedBlockingQueue<MyTextureFrame> texQueue = null;

    private boolean mGLThreadFlag = false;
    private Thread mGLThread;
    private WrapRenderer mRenderer;
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
        mRenderer = new WrapRenderer(renderer);
    }

    private int saveTextureIndex = 1;

    private void glRun() {
        texQueue = new LinkedBlockingQueue<MyTextureFrame>(maxQueueSize);
        EglHelper egl = new EglHelper();
        boolean ret = egl.createGLESWithSurface(new EGLConfigAttrs(), new EGLContextAttrs(), new SurfaceTexture(1));
        if (!ret) {
            //todo 错误处理
            return;
        }
        int mInputSurfaceTextureId = GpuUtils.createTextureID(true);
        SurfaceTexture mInputSurfaceTexture = new SurfaceTexture(mInputSurfaceTextureId);

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
        FrameBuffer sourceFrame = new FrameBuffer();
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
            mRenderer.draw(mInputSurfaceTextureId);
            sourceFrame.unBindFrameBuffer();
            rb.textureId = sourceFrame.getCacheTextureId();
            saveTextureIndex++;
            //String out = "/sdcard/VideoEdit/pic/pic_orig_" + saveTextureIndex + ".png";
            //LVTextureSave.saveToPng(rb.textureId, 720, 1280, out);
            //int newId = GpuUtils.createTextureID(false);
            //copyToNew(rb.textureId, newId);
            MyTextureFrame textureFrame = copyToNew(rb.textureId, sourceFrame.mFrameTemp[0]);
            try {
                texQueue.put(textureFrame);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            //接收数据源传入的时间戳
            rb.timeStamp = mProvider.getTimeStamp();
            rb.textureTime = mInputSurfaceTexture.getTimestamp();
            observable.notify(rb);
        }
        AvLog.d(TAG, "out of gl thread loop");
        MyTextureFrame frame = new MyTextureFrame();
        frame.endFlg = true;
        try {
            texQueue.put(frame);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        synchronized (LOCK) {
            rb.endFlag = true;
            observable.notify(rb);
            mRenderer.destroy();
            destroyGL(egl);
            LOCK.notifyAll();
            AvLog.d(TAG, "gl thread exit");
        }
    }

    private int createTexture() {
        int[] textureIds = new int[1];
        //创建纹理
        GLES20.glGenTextures(1, textureIds, 0);
        if (textureIds[0] == 0) {
            return 0;
        }
        //绑定纹理
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0]);
        //环绕（超出纹理坐标范围）  （s==x t==y GL_REPEAT 重复）
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
        //过滤（纹理像素映射到坐标点）  （缩小、放大：GL_LINEAR线性）
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        return textureIds[0];
    }

    private MyTextureFrame copyToNew(int oldTex, int oldFboId) {
        int[] fbos = new int[1];
        GLES20.glGenFramebuffers(1, fbos, 0);
        int fboId = fbos[0];
        glBindFramebuffer(GL_FRAMEBUFFER, fboId);
        int newTexId = createTexture();
        //绑定纹理和fbo
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, newTexId, 0);
        // 设置内存大小
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 720, 1280,
                0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        //6. 检测是否绑定从成功
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e("zzz", "glFramebufferTexture2D error");
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        //copy new
        glBindFramebuffer(GL_FRAMEBUFFER, oldFboId);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, newTexId);
        glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0,
                0, 0, 720, 1280);
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        saveTextureIndex++;
        String out = "/sdcard/VideoEdit/pic/pic_new_" + saveTextureIndex + ".png";
        LVTextureSave.saveToPng(newTexId, 720, 1280, out);
        MyTextureFrame texFrame = new MyTextureFrame();
        texFrame.texId = newTexId;
        texFrame.fboId = fboId;
        return texFrame;
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

