/**
 * Copyright 2018 Ricoh Company, Ltd.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.theta360.darumasangakoronda;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceView;

import com.theta360.pluginlibrary.activity.PluginActivity;
import com.theta360.pluginlibrary.callback.KeyCallback;
import com.theta360.pluginlibrary.receiver.KeyReceiver;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

import static org.opencv.imgproc.Imgproc.THRESH_BINARY;

public class MainActivity extends PluginActivity implements CvCameraViewListener2, ThetaController.CFCallback {

    private static final String TAG = "Plug-in::MainActivity";
    final private Object lockGreen = new Object();
    final private Object lockBlue = new Object();
    final private Object lockRed = new Object();
    volatile boolean oniAlive = false; //oniが動作していることを検知｡2重起動を防ぐため
    private ThetaController mOpenCvCameraView;
    private boolean isEnded = false;
    private Mat mOutputFrame;
    private Mat mMask;
    private Mat mStructuringElement;
    private int[] positionNowGreen;
    private int[] positionNowBlue;
    private int[] positionNowRed;
    private int showFrameNumber = 0;
    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            if (status == LoaderCallbackInterface.SUCCESS) {
                mOpenCvCameraView.enableView();
            } else {
                super.onManagerConnected(status);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "OpenCV version: " + Core.VERSION);

        // Set a callback when a button operation event is acquired.
        setKeyCallback(new KeyCallback() {
            @Override
            public void onKeyDown(int keyCode, KeyEvent keyEvent) {
                if (keyCode == KeyReceiver.KEYCODE_CAMERA) {
                    if (!oniAlive) {
                        Oni oni = new Oni();
                        oni.start();
                    }
                }
                if (keyCode == KeyReceiver.KEYCODE_WLAN_ON_OFF) {
                    showFrameNumber += 1;
                }
            }

            @Override
            public void onKeyUp(int keyCode, KeyEvent keyEvent) {

            }

            @Override
            public void onKeyLongPress(int keyCode, KeyEvent keyEvent) {
                if (keyCode == KeyReceiver.KEYCODE_MEDIA_RECORD) {
                    Log.d(TAG, "Do end process.");

                }
                closeCamera();
            }
        });

        notificationCameraClose();

        setContentView(R.layout.activity_main);

        mOpenCvCameraView = (ThetaController)
                findViewById(R.id.opencv_surface_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(MainActivity.this);
        mOpenCvCameraView.setCFCallback(MainActivity.this);

        mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "Internal OpenCV library not found.");
        } else {
            Log.d(TAG, "OpenCV library found inside package.");
            //   mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        super.onPause();
    }

    @Override
    public void onShutter() {
        notificationAudioShutter();
    }

    @Override
    public void onPictureTaken() {

    }

    public void onCameraViewStarted(int width, int height) {
        mOutputFrame = new Mat(height, width, CvType.CV_8UC3);
        mMask = new Mat(height, width, CvType.CV_8UC1);
        mStructuringElement = Imgproc.getStructuringElement(Imgproc.CV_SHAPE_ELLIPSE, new Size(3, 3));
    }

    public void onCameraViewStopped() {
        mStructuringElement.release();
        mMask.release();
        mOutputFrame.release();
    }

    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        // RGBのフレーム
        Mat frameOrg = inputFrame.rgba();

        //リサイズ
        Mat frameMini = frameOrg.clone();
        Imgproc.resize(frameOrg, frameMini, new Size(160, 80), 0, 0, Imgproc.INTER_LINEAR);

        // HSVに変換
        Imgproc.cvtColor(frameMini.clone(), frameMini, Imgproc.COLOR_RGB2HSV);

        // 色を抽出
        final Scalar GreenMin = new Scalar(50,50,10);
        final Scalar GreenMax = new Scalar(90,255,255);
        final Scalar BlueMin = new Scalar(100, 100, 10);
        final Scalar BlueMax = new Scalar(130, 255, 255);
        final Scalar RedMin = new  Scalar(0, 100, 30);
        final Scalar RedMax = new Scalar(5, 255, 255);

        final Mat frameGreen = getColorFrame(frameMini, GreenMin, GreenMax);
        final Mat frameBlue = getColorFrame(frameMini, BlueMin, BlueMax);
        final Mat frameRed = getColorFrame(frameMini, RedMin,RedMax);

        // 最大の輪郭の中心位置を取得
        int[] positionGreen = getMaxContourPosition(frameGreen);
        synchronized (lockGreen) {
            positionNowGreen = positionGreen;
        }

        int[] positionBlue = getMaxContourPosition(frameBlue);
        synchronized (lockBlue) {
            positionNowBlue = positionBlue;
        }

        int[] positionRed = getMaxContourPosition(frameRed);
        synchronized (lockRed) {
            positionNowRed = positionRed;
        }

        // Vysorを使って､色抽出した画面をチェックする
        // Wi-Fiボタンで表示切り替え
        switch (showFrameNumber % 4) {
            case 0:
                Imgproc.resize(frameGreen.clone(), frameOrg, new Size(640, 320), 0, 0, Imgproc.INTER_LINEAR);
                break;
            case 1:
                Imgproc.resize(frameBlue.clone(), frameOrg, new Size(640, 320), 0, 0, Imgproc.INTER_LINEAR);
                break;
            case 2:
                Imgproc.resize(frameRed.clone(), frameOrg, new Size(640, 320), 0, 0, Imgproc.INTER_LINEAR);
                break;
            case 3:
                //do nothing. return original frame
        }

        return frameOrg;
    }

    private Mat getColorFrame(Mat frame, Scalar min, Scalar max) {
        Mat frameColor = frame.clone();

        // color抽出
        Core.inRange(frameColor.clone(), min, max, frameColor);
        Imgproc.threshold(frameColor.clone(), frameColor, 100, 255, THRESH_BINARY);

        //noize除去
        Imgproc.morphologyEx(frameColor.clone(), frameColor, Imgproc.MORPH_OPEN, mStructuringElement);

        return frameColor;
    }

    private int[] getMaxContourPosition(Mat frame) {
        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(frame, contours, new Mat(), Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE);

        double maxArea = 0;
        MatOfPoint maxContour = new MatOfPoint();
        for (MatOfPoint contour : contours) {
            double area = Imgproc.contourArea(contour);
            if (maxArea < area) {
                maxArea = area;
                maxContour = contour;
            }
        }

        Moments p = Imgproc.moments(maxContour);
        int[] position = new int[2];

        position[0] = (int) (p.get_m10() / p.get_m00());
        position[1] = (int) (p.get_m01() / p.get_m00());

        return position;
    }

    private void closeCamera() {
        if (isEnded) {
            return;
        }
        if (mOpenCvCameraView != null) {
            mOpenCvCameraView.disableView();
        }
        close();
        isEnded = true;
    }

    // 色定義
    private enum Color {
        GREEN,
        BLUE,
        RED
    }

    private class Oni extends Thread {
        // どれかが動いたときの音声ファイル
        private static final String FINISH_SPEAK_FILE_GREEN = "ugoita_green.m4a";
        private static final String FINISH_SPEAK_FILE_BLUE = "ugoita_blue.m4a";
        private static final String FINISH_SPEAK_FILE_RED = "ugoita_red.m4a";
        private static final String FINISH_SPEAK_FILE_B_G = "ugoita_blue_green.m4a";
        private static final String FINISH_SPEAK_FILE_B_R = "ugoita_blue_red.m4a";
        private static final String FINISH_SPEAK_FILE_R_G = "ugoita_red_green.m4a";
        private static final String FINISH_SPEAK_FILE_ALL = "ugoita_all.m4a";

        // 動きを検知するしきい値
        private static final int distanceThreshold = 1;

        public void run() {
            oniAlive = true;

            // ｢だるまさんがころんだ｣のいくつかの発話パターン
            String[] allStartSpeakFile = new String[4];
            allStartSpeakFile[0] = "daruma_3.m4a";
            allStartSpeakFile[1] = "daruma_4.m4a";
            allStartSpeakFile[2] = "daruma_5.m4a";
            allStartSpeakFile[3] = "daruma_7.m4a";

            // ｢だるまさんがころんだ｣発話直後の位置
            int[] positionInitGreen;
            int[] positionInitBlue;
            int[] positionInitRed;

            // 移動距離の計算に利用する位置
            int[] positionThisTimeGreen;
            int[] positionThisTimeBlue;
            int[] positionThisTimeRed;

            // 各色が動いたらtrueにする
            Map<Color, Boolean> moveFlag = new HashMap<Color, Boolean>() {
                {
                    put(Color.GREEN, false);
                    put(Color.BLUE, false);
                    put(Color.RED, false);
                }
            };

            int checkCount; // 動きをチェックする回数｡この間に動いてはいけない｡
            Random rnd = new Random();
            int startSpeakIndex;

            int repeatNum = 3; //｢だるまさんがころんだ｣を3回繰り返す
            Speak:
            for (int i = 0; i < repeatNum; i++) {
                startSpeakIndex = rnd.nextInt(allStartSpeakFile.length);
                startPlayer(allStartSpeakFile[startSpeakIndex]);
                try {
                    // ｢だるまさんがころんだ｣発話直後の位置を記録｡
                    synchronized (lockGreen) {
                        positionInitGreen = positionNowGreen;
                    }

                    synchronized (lockBlue) {
                        positionInitBlue = positionNowBlue;
                    }

                    synchronized (lockRed) {
                        positionInitRed = positionNowRed;
                    }

                    // 動きをチェックする回数は6-16回でランダムに振る
                    checkCount = 6 + rnd.nextInt(10);

                    for (int j = 0; j < checkCount; j++) {
                        sleep(500);

                        synchronized (lockGreen) {
                            positionThisTimeGreen = positionNowGreen;
                        }

                        synchronized (lockBlue) {
                            positionThisTimeBlue = positionNowBlue;
                        }

                        synchronized (lockRed) {
                            positionThisTimeRed = positionNowRed;
                        }

                        moveFlag.put(Color.GREEN, checkMove(positionInitGreen, positionThisTimeGreen));
                        moveFlag.put(Color.BLUE, checkMove(positionInitBlue, positionThisTimeBlue));
                        moveFlag.put(Color.RED, checkMove(positionInitRed, positionThisTimeRed));

                        // どれかが動いたら終わりの発話してループ終了
                        if (moveFlag.containsValue(true)) {
                            speakUgoita(moveFlag);
                            break Speak;
                        }
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            }

            // 誰も動いていない場合は｢みんなの勝ち｣と発話して終了
            if (!moveFlag.containsValue(true)) {
                startPlayer("minnanokachi_3.m4a");
            }

            oniAlive = false;
        }

        private void speakUgoita(Map<Color, Boolean> moveFlag) {

            boolean moveGreen = moveFlag.get(Color.GREEN);
            boolean moveBlue = moveFlag.get(Color.BLUE);
            boolean moveRed = moveFlag.get(Color.RED);

            // 動いた色にあわせて発話音声ファイルを起動
            if (moveGreen && moveBlue && moveRed) {
                startPlayer(FINISH_SPEAK_FILE_ALL);
            } else if (moveBlue && moveGreen) {
                startPlayer(FINISH_SPEAK_FILE_B_G);
            } else if (moveBlue && moveRed) {
                startPlayer(FINISH_SPEAK_FILE_B_R);
            } else if (moveRed && moveGreen) {
                startPlayer(FINISH_SPEAK_FILE_R_G);
            } else if (moveBlue) {
                startPlayer(FINISH_SPEAK_FILE_BLUE);
            } else if (moveGreen) {
                startPlayer(FINISH_SPEAK_FILE_GREEN);
            } else if (moveRed) {
                startPlayer(FINISH_SPEAK_FILE_RED);
            }

        }

        private boolean checkMove(int[] positionInit, int[] position) {
            double distance = Math.hypot(positionInit[0] - position[0], positionInit[1] - position[0]);
            return distance > distanceThreshold;
        }

        private void startPlayer(String soundFile) {

            AssetFileDescriptor afdescripter;
            try {
                afdescripter = getResources().getAssets().openFd(soundFile);
            } catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException("failed to open " + soundFile, e);
            }

            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int maxVol = 0;
            if (audioManager != null) {
                maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING);
            }
            if (audioManager != null) {
                audioManager.setStreamVolume(AudioManager.STREAM_RING, maxVol, 0);
            }
            final MediaPlayer mediaPlayer = new MediaPlayer();
            final AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setLegacyStreamType(AudioManager.STREAM_RING)
                    .build();
            final CountDownLatch completeSignal = new CountDownLatch(1);

            mediaPlayer.setAudioAttributes(attributes);
            mediaPlayer.setVolume(1.0f, 1.0f);
            mediaPlayer.setOnSeekCompleteListener(MediaPlayer::release);
            mediaPlayer.setOnCompletionListener(mp -> {
                completeSignal.countDown();
                mp.release();
            });
            mediaPlayer.setOnPreparedListener(MediaPlayer::start);
            try {
                mediaPlayer.setDataSource(afdescripter.getFileDescriptor(),
                        afdescripter.getStartOffset(), afdescripter.getLength());
                mediaPlayer.prepare();
                Log.d(TAG, "Start");
            } catch (IOException e) {
                Log.e(TAG, "Exception starting MediaPlayer: " + e.getMessage());
                mediaPlayer.release();
                notificationError("");
                return;
            }

            try {
                completeSignal.await();
            } catch (InterruptedException e) {
                Log.e(TAG, "Waiting for end of sound is interrupted.", e);
            }
        }

    }

}
