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

import static android.opengl.GLES20.GL_COLOR_ATTACHMENT0;
import static android.opengl.GLES20.GL_FRAMEBUFFER;
import static android.opengl.GLES20.GL_RGBA;
import static android.opengl.GLES20.GL_TEXTURE0;
import static android.opengl.GLES20.GL_TEXTURE1;
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
    FrameBuffer sourceFrame;

    private void createEGL() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        egl = new EglHelper();
        boolean ret = egl.createGLESWithSurface(new EGLConfigAttrs(), new EGLContextAttrs(), new SurfaceTexture(1));
        if (!ret) {
            //todo 错误处理
            return;
        }
        if (mRenderer == null) {
            mRenderer = new WrapRenderer(null);
        }
        sourceFrame = new FrameBuffer();
        mRenderer.create();
        mRenderer.sizeChanged(720, 1280);
        mRenderer.setFlag(mProvider.isLandscape() ? WrapRenderer.TYPE_CAMERA : WrapRenderer.TYPE_MOVE);

    }

    private void glRunning() {
//        //用于其他的回调
        rb.egl = egl;
        rb.endFlag = false;
        AvLog.d(TAG, "Processor While Loop Entry");
        //要求数据源必须同步填充SurfaceTexture，填充完成前等待
        while (Mp4Provider.texQueue == null || Mp4Provider.texQueue.size() <= 0) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        sourceFrame.bindFrameBuffer(720, 1280);
        while (getFrameEncode2()) ;
        sourceFrame.unBindFrameBuffer();
    }


    private boolean getFrameEncode2() {
        MyTextureFrame frame = null;
        try {
            frame = Mp4Provider.texQueue.take();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (frame.endFlg) {
            return false;
        }
        createMyOwn2(frame.texId);
        GLES20.glBindTexture(GL_TEXTURE_2D, frame.texId);
        GLES20.glViewport(0, 0, 720, 1280);
        //mRenderer.draw(frame.texId);
        //glActiveTexture(GL_TEXTURE0);
        //glBindTexture(GL_TEXTURE_2D, frame.texId);
        saveTextureIndex++;
        String out = "/sdcard/VideoEdit/pic/new_process_only_" + saveTextureIndex + ".png";
        //LVTextureSave.onlySaveToPng(frame.texId, 720, 1280, out);
        //LVTextureSave.saveToPng(mFrameTemp[1], 720, 1280, out);
        LVTextureSave.saveToPng(frame.texId, 720, 1280, out);
        // LVTextureSave.saveToPngFrameBuff(frame.texId, frame.fboId,720, 1280, out);
        //unbindTexture();

        //GLES20.glViewport(0, 0, 720, 1280);
        //mRenderer.draw(frame.texId);
        String out2 = "/sdcard/VideoEdit/pic/pic_renderer_" + saveTextureIndex + ".png";
        //black
        //LVTextureSave.saveToPng(frame.texId, 720, 1280, out2);
        //LVTextureSave.saveToPng(sourceFrame.getCacheTextureId(), 720, 1280, out2);

        //rb.textureId = sourceFrame.getCacheTextureId();
        rb.textureId = frame.texId;
        rb.sourceWidth = frame.width;
        rb.sourceHeight = frame.height;
        //接收数据源传入的时间戳
        rb.timeStamp = frame.nowTimeStamp;
        observable.notify(rb);
        unbindTexture();
        return true;
    }

    private void unbindTexture(){
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }
    int mFrameTemp[];

    private int createMyOwn() {
        mFrameTemp = new int[4];
        GLES20.glGenFramebuffers(1, mFrameTemp, 0);
        GLES20.glGenTextures(1, mFrameTemp, 1);
        Log.v(TAG, "");
        GLES20.glBindTexture(GL_TEXTURE_2D, mFrameTemp[1]);
        GLES20.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 720, 1280,
                0, GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        //设置缩小过滤为使用纹理中坐标最接近的一个像素的颜色作为需要绘制的像素颜色
        GLES20.glTexParameteri(GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        //设置放大过滤为使用纹理中坐标最接近的若干个颜色，通过加权平均算法得到需要绘制的像素颜色
        GLES20.glTexParameteri(GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        //设置环绕方向S，截取纹理坐标到[1/2n,1-1/2n]。将导致永远不会与border融合
        GLES20.glTexParameteri(GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        //设置环绕方向T，截取纹理坐标到[1/2n,1-1/2n]。将导致永远不会与border融合
        GLES20.glTexParameteri(GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, mFrameTemp, 3);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFrameTemp[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_2D, mFrameTemp[1], 0);

        return GLES20.glGetError();
    }

    private int createMyOwn2(int texID) {
        mFrameTemp = new int[4];
        GLES20.glGenFramebuffers(1, mFrameTemp, 0);
        //GLES20.glGenTextures(1, mFrameTemp, 1);
        Log.v(TAG, "");
        //GLES20.glBindTexture(GL_TEXTURE_2D, mFrameTemp[1]);
        GLES20.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 720, 1280,
                0, GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        //设置缩小过滤为使用纹理中坐标最接近的一个像素的颜色作为需要绘制的像素颜色
        GLES20.glTexParameteri(GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        //设置放大过滤为使用纹理中坐标最接近的若干个颜色，通过加权平均算法得到需要绘制的像素颜色
        GLES20.glTexParameteri(GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        //设置环绕方向S，截取纹理坐标到[1/2n,1-1/2n]。将导致永远不会与border融合
        GLES20.glTexParameteri(GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        //设置环绕方向T，截取纹理坐标到[1/2n,1-1/2n]。将导致永远不会与border融合
        GLES20.glTexParameteri(GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, mFrameTemp, 3);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFrameTemp[0]);
        GLES20.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texID, 0);

        //GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
        //        GL_TEXTURE_2D, mFrameTemp[1], 0);

        return GLES20.glGetError();
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

