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

import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Log;

import com.wuwang.aavt.core.IObserver;
import com.wuwang.aavt.gl.BaseFilter;
import com.wuwang.aavt.gl.LazyFilter;
import com.wuwang.aavt.utils.MatrixUtils;

import static android.opengl.GLES20.GL_COLOR_ATTACHMENT0;
import static android.opengl.GLES20.GL_FRAMEBUFFER;
import static android.opengl.GLES20.GL_RGBA;
import static android.opengl.GLES20.GL_TEXTURE_2D;
import static android.opengl.GLES20.glActiveTexture;
import static android.opengl.GLES20.glBindFramebuffer;
import static android.opengl.GLES20.glBindTexture;

/**
 * SurfaceShower 用于将RenderBean展示到指定的Surface上
 *
 * @author wuwang
 * @version v1.0 2017:10:27 08:53
 */
public class SurfaceShower implements IObserver<RenderBean> {
    private String TAG = getClass().getName();
    private EGLSurface mShowSurface;
    private boolean isShow = false;
    private BaseFilter mFilter;
    private Object mSurface;
    private int mWidth;
    private int mHeight;
    private int mMatrixType = MatrixUtils.TYPE_CENTERCROP;
    private OnDrawEndListener mListener;

    public void setOutputSize(int width, int height) {
        this.mWidth = width;
        this.mHeight = height;
    }

    /**
     * 设置输出的Surface
     *
     * @param surface {@link android.view.Surface}、{@link android.graphics.SurfaceTexture}或{@link android.view.TextureView}
     */
    public void setSurface(Object surface) {
        this.mSurface = surface;
    }

    /**
     * 设置矩阵变换类型
     *
     * @param type 变换类型，{@link MatrixUtils#TYPE_FITXY},{@link MatrixUtils#TYPE_FITSTART},{@link MatrixUtils#TYPE_CENTERCROP},{@link MatrixUtils#TYPE_CENTERINSIDE}或{@link MatrixUtils#TYPE_FITEND}
     */
    public void setMatrixType(int type) {
        this.mMatrixType = type;
    }

    public void open() {
        isShow = true;
    }

    public void close() {
        isShow = false;
    }

    @Override
    public void onCall(RenderBean rb) {
        if (true) {
            if (rb.endFlag && mShowSurface != null) {
                rb.egl.destroySurface(mShowSurface);
                mShowSurface = null;
            } else if (isShow && mSurface != null) {
                //createMyOwn2(rb.textureId);
                GLES20.glBindTexture(GL_TEXTURE_2D, rb.textureId);
                GLES20.glViewport(0, 0, mWidth, mHeight);
                String out = "/sdcard/VideoEdit/pic/pic_shower_" + saveTextureIndex + ".png";
                LVTextureSave.saveToPng(rb.textureId, 720, 1280, out);
                saveTextureIndex++;
            }
            return;
        }
        if (rb.endFlag && mShowSurface != null) {
            rb.egl.destroySurface(mShowSurface);
            mShowSurface = null;
        } else if (isShow && mSurface != null) {
            if (mShowSurface == null) {
                mShowSurface = rb.egl.createWindowSurface(mSurface);
                mFilter = new LazyFilter();
                mFilter.create();
                mFilter.sizeChanged(rb.sourceWidth, rb.sourceHeight);
                MatrixUtils.getMatrix(mFilter.getVertexMatrix(), mMatrixType, rb.sourceWidth, rb.sourceHeight,
                        mWidth, mHeight);
            }
            //rb.egl.makeCurrent(mShowSurface);
            //createMyOwn2(rb.textureId);
            GLES20.glBindTexture(GL_TEXTURE_2D, rb.textureId);
            GLES20.glViewport(0, 0, mWidth, mHeight);
            String out = "/sdcard/VideoEdit/pic/pic_shower_" + saveTextureIndex + ".png";
            LVTextureSave.saveToPng(rb.textureId, 720, 1280, out);
            saveTextureIndex++;
//            mFilter.draw(rb.textureId);
//            if(mListener!=null){
//                mListener.onDrawEnd(mShowSurface,rb);
//            }
//            rb.egl.swapBuffers(mShowSurface);
            glBindTexture(GL_TEXTURE_2D, 0);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        }
    }

    private int mFrameTemp[] = new int[4];

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

    private int saveTextureIndex = 0;

    /**
     * 设置单帧渲染完成监听器
     *
     * @param listener 监听器
     */
    public void setOnDrawEndListener(OnDrawEndListener listener) {
        this.mListener = listener;
    }

    public interface OnDrawEndListener {
        /**
         * 渲染完成通知
         *
         * @param surface 渲染的目标EGLSurface
         * @param bean    渲染用的资源
         */
        void onDrawEnd(EGLSurface surface, RenderBean bean);
    }

}

