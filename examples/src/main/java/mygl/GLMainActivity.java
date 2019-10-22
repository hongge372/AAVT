package mygl;

import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.AppCompatActivity;
import android.widget.Toast;

import com.wuwang.aavt.av.Mp4Processor2;
import com.wuwang.aavt.examples.R;

public class GLMainActivity extends AppCompatActivity {

    private GLSurfaceView mGLSurfaceView;
    private DemoRenderer mRenderer;

    private Mp4Processor2 mMp4Processor;
    private String tempPath= Environment.getExternalStorageDirectory().getAbsolutePath()+"/test.mp4";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.gl_activity_main);

        if (!Utils.supportGlEs20(this)) {
            Toast.makeText(this, "GLES 2.0 not supported!", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        mGLSurfaceView = (GLSurfaceView) findViewById(R.id.surface);

        mGLSurfaceView.setEGLContextClientVersion(2);
        mRenderer = new DemoRenderer(this);
        mGLSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        mGLSurfaceView.setRenderer(mRenderer);
        mGLSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);


        mMp4Processor=new Mp4Processor2();
        mMp4Processor.setInputPath(Environment.getExternalStorageDirectory().getAbsolutePath()+"/a.mp4");
        mMp4Processor.setOutputPath(tempPath);
        mMp4Processor.startPreview();
        mMp4Processor.startRecord();
        mMp4Processor.open();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mGLSurfaceView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mGLSurfaceView.onResume();
    }
}
