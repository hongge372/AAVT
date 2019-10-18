package com.wuwang.aavt.media;

import android.annotation.TargetApi;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.opengl.GLES20;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import com.wuwang.aavt.egl.EGLConfigAttrs;
import com.wuwang.aavt.egl.EGLContextAttrs;
import com.wuwang.aavt.egl.EglHelper;
import com.wuwang.aavt.gl.FrameBuffer;
import com.wuwang.aavt.log.AvLog;
import com.wuwang.aavt.media.av.AvException;
import com.wuwang.aavt.media.hard.HardMediaData;
import com.wuwang.aavt.media.hard.IHardStore;
import com.wuwang.aavt.utils.GpuUtils;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_FRAMEBUFFER;
import static android.opengl.GLES20.GL_NEAREST;
import static android.opengl.GLES20.GL_RGBA;
import static android.opengl.GLES20.GL_TEXTURE0;
import static android.opengl.GLES20.GL_TEXTURE_2D;
import static android.opengl.GLES20.GL_TEXTURE_MAG_FILTER;
import static android.opengl.GLES20.GL_TEXTURE_MIN_FILTER;
import static android.opengl.GLES20.GL_TEXTURE_WRAP_S;
import static android.opengl.GLES20.GL_TEXTURE_WRAP_T;
import static android.opengl.GLES20.glActiveTexture;
import static android.opengl.GLES20.glBindFramebuffer;
import static android.opengl.GLES20.glBindTexture;
import static android.opengl.GLES20.glCopyTexSubImage2D;
import static android.opengl.GLES20.glCullFace;
import static android.opengl.GLES20.glTexParameteri;

/**
 * @author wuwang
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
public class Mp4Provider implements ITextureProvider {

    private final String TAG = getClass().getSimpleName();
    private String mPath;
    private MediaExtractor mExtractor;
    private MediaCodec mVideoDecoder;
    private int mVideoDecodeTrack = -1;
    private int mAudioDecodeTrack = -1;
    private Point mVideoSize = new Point();
    private Semaphore mFrameSem;
    private static final int TIME_OUT = 1000;
    private final Object Extractor_LOCK = new Object();
    private long mVideoStopTimeStamp;
    private boolean isVideoExtractorEnd = false;
    private boolean isUserWantToStop = false;
    private Semaphore mDecodeSem;

    private boolean videoProvideEndFlag = false;

    private IHardStore mStore;

    private long nowTimeStamp = -1;
    private MediaCodec.BufferInfo videoDecodeBufferInfo = new MediaCodec.BufferInfo();
    private int mAudioEncodeTrack = -1;
    private long mVideoTotalTime = -1;

    public Mp4Provider() {

    }

    public void setInputPath(String path) {
        this.mPath = path;
    }

    private boolean extractMedia() {
        if (mPath == null || !new File(mPath).exists()) {
            //文件不存在
            return false;
        }
        try {
            MediaMetadataRetriever mMetRet = new MediaMetadataRetriever();
            mMetRet.setDataSource(mPath);
            mVideoTotalTime = Long.valueOf(mMetRet.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            mExtractor = new MediaExtractor();
            mExtractor.setDataSource(mPath);
            int trackCount = mExtractor.getTrackCount();
            for (int i = 0; i < trackCount; i++) {
                MediaFormat format = mExtractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("audio")) {
                    mAudioDecodeTrack = i;
                } else if (mime.startsWith("video")) {
                    mVideoDecodeTrack = i;
                    int videoRotation = 0;
                    String rotation = mMetRet.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                    if (rotation != null) {
                        videoRotation = Integer.valueOf(rotation);
                    }
                    if (videoRotation % 180 != 0) {
                        mVideoSize.y = format.getInteger(MediaFormat.KEY_WIDTH);
                        mVideoSize.x = format.getInteger(MediaFormat.KEY_HEIGHT);
                    } else {
                        mVideoSize.x = format.getInteger(MediaFormat.KEY_WIDTH);
                        mVideoSize.y = format.getInteger(MediaFormat.KEY_HEIGHT);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public void setStore(IHardStore store) {
        this.mStore = store;
    }

    private int saveTextureIndex;

    private boolean videoDecodeStep() {
        int mInputIndex = mVideoDecoder.dequeueInputBuffer(TIME_OUT);
        if (mInputIndex >= 0) {
            ByteBuffer buffer = CodecUtil.getInputBuffer(mVideoDecoder, mInputIndex);
            buffer.clear();
            synchronized (Extractor_LOCK) {
                mExtractor.selectTrack(mVideoDecodeTrack);
                int ret = mExtractor.readSampleData(buffer, 0);
                if (ret != -1) {
                    mVideoStopTimeStamp = mExtractor.getSampleTime();
                    mVideoDecoder.queueInputBuffer(mInputIndex, 0, ret, mVideoStopTimeStamp, mExtractor.getSampleFlags());
                    isVideoExtractorEnd = false;
                } else {
                    //可以用!mExtractor.advance，但是貌似会延迟一帧。readSampleData 返回 -1 也表示没有更多数据了
                    isVideoExtractorEnd = true;
                }
                mExtractor.advance();
            }
        }
        //sourceFrame.bindFrameBuffer(mSourceWidth, mSourceHeight);
        //sourceFrame.createFrameBuffer(false,mSourceWidth,mSourceHeight, GLES20.GL_TEXTURE_2D,GLES20.GL_RGBA,
        //        GLES20.GL_LINEAR,GLES20.GL_LINEAR,GLES20.GL_CLAMP_TO_EDGE,GLES20.GL_CLAMP_TO_EDGE);

        while (true) {
            int mOutputIndex = mVideoDecoder.dequeueOutputBuffer(videoDecodeBufferInfo, TIME_OUT);
            if (mOutputIndex >= 0) {
                nowTimeStamp = videoDecodeBufferInfo.presentationTimeUs;
                mVideoDecoder.releaseOutputBuffer(mOutputIndex, true);
                //mFrameSem.release();
                mInputSurfaceTexture.updateTexImage();
                mInputSurfaceTexture.getTransformMatrix(mRenderer.getTextureMatrix());
                boolean saveWhileUpdate = true;
                if (saveWhileUpdate) {
                  //  String outCopy = "/sdcard/VideoEdit/pic/pic_tex_2d_" + saveTextureIndex + ".png";
                  //  LVTextureSave.saveToPng(mInputSurfaceTextureId, 720, 1280, outCopy);
                }
                AvLog.d(TAG, "timestamp:" + mInputSurfaceTexture.getTimestamp());
                GLES20.glViewport(0, 0, mSourceWidth, mSourceHeight);
                createMyOwn();
                mRenderer.draw(mInputSurfaceTextureId);
                boolean saveAfterDraw = true;
                if (saveAfterDraw) {
                    String outCopy = "/sdcard/VideoEdit/pic/pic_tex_2d_draw_" + saveTextureIndex + ".png";
                    int toSave = mFrameTemp[1];
                    Log.v(TAG, "be save tex" + toSave);
                    //LVTextureSave.saveToPng(toSave, 720, 1280, outCopy);
                    MyTextureFrame textureFrame = new MyTextureFrame();
                    textureFrame.texId = toSave;
                    textureFrame.width = 720;
                    textureFrame.height = 1280;
                    textureFrame.nowTimeStamp = nowTimeStamp;
                    try {
                        texQueue.put(textureFrame);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                glBindTexture(GL_TEXTURE_2D, 0);
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                saveTextureIndex++;
                //sourceFrame.unBindFrameBuffer();
                //glBindFramebuffer(GL_FRAMEBUFFER, sourceFrame.mFrameTemp[0]);
                //String out = "/sdcard/VideoEdit/pic/pic_orig_" + saveTextureIndex + ".png";
                //LVTextureSave.saveToPng(mInputSurfaceTextureId, 720, 1280, out);
                //int newId = GpuUtils.createTextureID(false);
                //copyToNew(mInputSurfaceTextureId, newId);
                //MyTextureFrame textureFrame = copyToNew(mInputSurfaceTextureId, sourceFrame.mFrameTemp[0]);
                //String outCopy = "/sdcard/VideoEdit/pic/pic_tex_fbo_" + saveTextureIndex + ".png";
                // glBindFramebuffer(GL_FRAMEBUFFER, fboId);
                // LVTextureSave.saveToPngFrameBuff(textureFrame.texId, textureFrame.fboId,720, 1280, outCopy);
//                glBindTexture(GL_TEXTURE_2D, 0);
//                glBindFramebuffer(GL_FRAMEBUFFER, 0);
//                textureFrame.width = 720;
//                textureFrame.height = 1280;
//                textureFrame.nowTimeStamp = nowTimeStamp;
//                try {
//                    texQueue.put(textureFrame);
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
            } else if (mOutputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

            } else if (mOutputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            }
        }
        return isVideoExtractorEnd || isUserWantToStop;
    }

    int mFrameTemp[];

    private int createMyOwn() {
        mFrameTemp = new int[4];
        GLES20.glGenFramebuffers(1, mFrameTemp, 0);
        GLES20.glGenTextures(1, mFrameTemp, 1);
        Log.v(TAG, "");
        GLES20.glBindTexture(GL_TEXTURE_2D, mFrameTemp[1]);
        GLES20.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, mSourceWidth, mSourceHeight,
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
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_2D, mFrameTemp[1], 0);

        return GLES20.glGetError();
    }

    private void bindByOwn() {
//        GLES20.glGenFramebuffers(1,mFrameTemp,0);
//        GLES20.glGenTextures(1,mFrameTemp,1);
//        Log.v(TAG, "");
//        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,mFrameTemp[1]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GL_RGBA, 720, 1280,
                0, GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        //设置缩小过滤为使用纹理中坐标最接近的一个像素的颜色作为需要绘制的像素颜色
        GLES20.glTexParameteri(GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        //设置放大过滤为使用纹理中坐标最接近的若干个颜色，通过加权平均算法得到需要绘制的像素颜色
        GLES20.glTexParameteri(GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        //设置环绕方向S，截取纹理坐标到[1/2n,1-1/2n]。将导致永远不会与border融合
        GLES20.glTexParameteri(GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        //设置环绕方向T，截取纹理坐标到[1/2n,1-1/2n]。将导致永远不会与border融合
        GLES20.glTexParameteri(GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        //GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING,mFrameTemp,3);
        //GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,mFrameTemp[0]);
        //GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
        //        texType, mFrameTemp[1], 0);
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
//        //环绕（超出纹理坐标范围）  （s==x t==y GL_REPEAT 重复）
//        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
//        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
//        //过滤（纹理像素映射到坐标点）  （缩小、放大：GL_LINEAR线性）
//        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
//        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return textureIds[0];
    }

    private int fboId;

    private MyTextureFrame copyToNew(int oldTex, int oldFboId) {
        int[] fbos = new int[1];
        GLES20.glGenFramebuffers(1, fbos, 0);
        fboId = fbos[0];
        glBindFramebuffer(GL_FRAMEBUFFER, fboId);
        int newTexId = createTexture();
        //绑定纹理和fbo
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, newTexId, 0);
        // 设置内存大小
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GL_RGBA, 720, 1280,
                0, GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
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
//        String out = "/sdcard/VideoEdit/pic/pic_new_" + saveTextureIndex + ".png";
//        LVTextureSave.saveToPng(newTexId, 720, 1280, out);
        MyTextureFrame texFrame = new MyTextureFrame();
        texFrame.texId = newTexId;
        texFrame.fboId = fboId;
        return texFrame;
    }

    public long getMediaDuration() {
        return mVideoTotalTime;
    }

    private void startDecodeThread() {
        //Thread mDecodeThread = new Thread(new Runnable() {
        //    @Override
        //    public void run() {
        checkEnv();

        while (!videoDecodeStep()) {
        }
        if (videoDecodeBufferInfo.flags != MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
            AvLog.d(TAG, "video ------------------ end");
            videoProvideEndFlag = true;
//                    try {
//                        mDecodeSem.acquire();
//                    } catch (InterruptedException e) {
//                        e.printStackTrace();
//                    }
            //释放最后一帧的信号
            videoDecodeBufferInfo.flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM;
            mFrameSem.release();
        }
        mVideoDecoder.stop();
        mVideoDecoder.release();
        mVideoDecoder = null;
        AvLog.d(TAG, "audioStart");
        audioDecodeStep();
        AvLog.d(TAG, "audioStop");
        mExtractor.release();
        mExtractor = null;
        mRenderer.destroy();
        try {
            mStore.close();
        } catch (AvException e) {
            e.printStackTrace();
        }
        //}
        //    });
        //    mDecodeThread.start();
    }

    private void checkEnv() {
        Point size = mVideoSize;
        AvLog.d(TAG, "Provider Opened . data size (x,y)=" + size.x + "/" + size.y);
        if (size.x <= 0 || size.y <= 0) {
            //todo 错误处理
            return;
        }
        mSourceWidth = size.x;
        mSourceHeight = size.y;

        //要求数据源提供者必须同步返回数据大小
        if (mSourceWidth <= 0 || mSourceHeight <= 0) {
            Log.v(TAG, "video source return inaccurate size to SurfaceTextureActuator");
            return;
        }

        if (mRenderer == null) {
            mRenderer = new WrapRenderer(null);
        }
       // sourceFrame = new FrameBuffer();
        mRenderer.create();
        mRenderer.sizeChanged(mSourceWidth, mSourceHeight);
        mRenderer.setFlag(WrapRenderer.TYPE_MOVE);
    }

    private boolean isOpenAudio = true;

    private boolean audioDecodeStep() {
        ByteBuffer buffer = ByteBuffer.allocate(1024 * 64);
        boolean isTimeEnd = false;
        if (isOpenAudio) {
            buffer.clear();
            mExtractor.selectTrack(mAudioDecodeTrack);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int length = mExtractor.readSampleData(buffer, 0);
                if (length != -1) {
                    int flags = mExtractor.getSampleFlags();
                    boolean isAudioEnd = mExtractor.getSampleTime() > mVideoStopTimeStamp;
                    info.size = length;
                    info.flags = isAudioEnd ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : flags;
                    info.presentationTimeUs = mExtractor.getSampleTime();
                    info.offset = 0;
                    AvLog.d(TAG, "audio sampleTime= " + info.presentationTimeUs + "/" + mVideoStopTimeStamp);
                    isTimeEnd = mExtractor.getSampleTime() > mVideoStopTimeStamp;
                    AvLog.d(TAG, "is End= " + isAudioEnd);
                    mStore.addData(mAudioEncodeTrack, new HardMediaData(buffer, info));
                    if (isAudioEnd) {
                        break;
                    }
                } else {
                    AvLog.d(TAG, "is End= " + true);
                    info.size = 0;
                    info.flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                    mStore.addData(mAudioEncodeTrack, new HardMediaData(buffer, info));
                    isTimeEnd = true;
                    break;
                }
                mExtractor.advance();
            }
        }
        return isTimeEnd;
    }

    @Override
    public Point open(SurfaceTexture surface) {
        texPrepare();
        try {
            if (!extractMedia()) {
                return new Point(0, 0);
            }
            mFrameSem = new Semaphore(0);
            mDecodeSem = new Semaphore(1);
            videoProvideEndFlag = false;
            isUserWantToStop = false;
            mAudioEncodeTrack = mStore.addTrack(mExtractor.getTrackFormat(mAudioDecodeTrack));
            MediaFormat format = mExtractor.getTrackFormat(mVideoDecodeTrack);
            mVideoDecoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            mVideoDecoder.configure(format, new Surface(mInputSurfaceTexture), null, 0);
            mVideoDecoder.start();
            startDecodeThread();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return mVideoSize;
    }

    public static int maxQueueSize = 8;
    public static LinkedBlockingQueue<MyTextureFrame> texQueue = null;
    private WrapRenderer mRenderer;
    private int mInputSurfaceTextureId;
    private SurfaceTexture mInputSurfaceTexture;
    FrameBuffer sourceFrame;
    int mSourceWidth = 0;
    int mSourceHeight = 0;

    private void texPrepare() {
        texQueue = new LinkedBlockingQueue<MyTextureFrame>(maxQueueSize);
        EglHelper egl = new EglHelper();
        boolean ret = egl.createGLESWithSurface(new EGLConfigAttrs(), new EGLContextAttrs(), new SurfaceTexture(1));
        if (!ret) {
            //todo 错误处理
            return;
        }
        mInputSurfaceTextureId = GpuUtils.createTextureID(true);
        mInputSurfaceTexture = new SurfaceTexture(mInputSurfaceTextureId);
    }


    @Override
    public void close() {
        isUserWantToStop = true;
    }

    @Override
    public boolean frame() {
        try {
            mFrameSem.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        mDecodeSem.release();
        return videoProvideEndFlag;
    }

    @Override
    public long getTimeStamp() {
        return nowTimeStamp;
    }

    @Override
    public boolean isLandscape() {
        return false;
    }

}
