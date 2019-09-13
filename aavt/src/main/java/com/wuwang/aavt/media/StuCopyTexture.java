package com.wuwang.aavt.media;

import android.graphics.SurfaceTexture;

import com.wuwang.aavt.egl.EGLConfigAttrs;
import com.wuwang.aavt.egl.EGLContextAttrs;
import com.wuwang.aavt.egl.EglHelper;

import static android.opengl.GLES20.GL_FRAMEBUFFER;
import static android.opengl.GLES20.GL_TEXTURE0;
import static android.opengl.GLES20.GL_TEXTURE_2D;
import static android.opengl.GLES20.glActiveTexture;
import static android.opengl.GLES20.glBindFramebuffer;
import static android.opengl.GLES20.glBindTexture;

public class StuCopyTexture {
    private WrapRenderer mRenderer;

    private void createEGL() {
        EglHelper egl = new EglHelper();
        boolean ret = egl.createGLESWithSurface(new EGLConfigAttrs(), new EGLContextAttrs(), new SurfaceTexture(1));
        if (!ret) {
            //todo 错误处理
            return;
        }
    }

    public void pullSave() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                createEGL();
                while (queueget());
            }
        }).start();
    }

    private int saveTextureIndex = 0;
    private boolean queueget() {
        while (VideoSurfaceProcessor.texQueue == null || VideoSurfaceProcessor.texQueue.size() <=0){
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        MyTextureFrame frame = null;
        try {
            frame = VideoSurfaceProcessor.texQueue.take();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if(frame.endFlg){
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
}
