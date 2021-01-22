/**
 * Copyright 2018 Ricoh Company, Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.theta360.opencvpreview;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.hardware.usb.UsbDeviceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceView;

import com.theta360.pluginlibrary.activity.PluginActivity;
import com.theta360.pluginlibrary.callback.KeyCallback;
import com.theta360.pluginlibrary.receiver.KeyReceiver;
import com.theta360.pluginlibrary.values.LedColor;
import com.theta360.pluginlibrary.values.LedTarget;
import com.theta360.opencvpreview.oled.Oled;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.android.Utils;

//シリアル通信まわりで使用
import android.app.PendingIntent;
import android.hardware.usb.UsbManager;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.util.HashMap;
import java.util.Map;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;


public class MainActivity extends PluginActivity implements CvCameraViewListener2 {

    private static final String TAG = "Plug-in::MainActivity";

    //シリアル通信関連
    private boolean mFinished;
    private UsbSerialPort port ;

    //USBデバイスへのパーミッション付与関連
    PendingIntent mPermissionIntent;
    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";

    private ThetaView mOpenCvCameraView;
    private boolean isEnded = false;
    private Mat mOutputFrame;
    private Mat mStructuringElement;

    //WebUI用
    private byte[]		latestLvFrame=null;

    //Button Resorce
    private boolean onKeyDownModeButton = false;

    //OLED
    Oled oledDisplay = null;        //OLED描画クラス

    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS:
                    mOpenCvCameraView.enableView();
                    break;
                default:
                    super.onManagerConnected(status);
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //OLED初期化
        oledDisplay = new Oled(getApplicationContext());
        oledDisplay.brightness(100);     //輝度設定
        oledDisplay.clear(oledDisplay.black); //表示領域クリア設定
        oledDisplay.draw();                     //表示領域クリア結果を反映

        Log.d(TAG, "OpenCV version: " + Core.VERSION);

        // Set a callback when a button operation event is acquired.
        setKeyCallback(new KeyCallback() {
            @Override
            public void onKeyDown(int keyCode, KeyEvent keyEvent) {

                if (keyCode == KeyReceiver.KEYCODE_CAMERA) {
                    if (linetraceEnable) {
                        linetraceEnable = false;
                        notificationLedHide(LedTarget.LED3);
                        oledLineTraceStat = LT_STAT_OFF;

                        notificationLedHide(LedTarget.LED7); //REC

                    } else {
                        //制御変数リセット
                        resetControlParam();

                        //操作から3秒後に動かし始める
                        controlWait(3000);

                        linetraceEnable = true;
                        notificationLedBlink(LedTarget.LED3, LedColor.CYAN, 1000);
                        oledLineTraceStat = LT_STAT_ON_WAIT;
                    }
                } else if (keyCode == KeyReceiver.KEYCODE_MEDIA_RECORD) {
                    // Disable onKeyUp of startup operation.
                    onKeyDownModeButton = true;
                }
            }

            @Override
            public void onKeyUp(int keyCode, KeyEvent keyEvent) {
                if (keyCode == KeyReceiver.KEYCODE_MEDIA_RECORD) {
                    if (onKeyDownModeButton) {
                        if (accelerationEnable) {
                            accelerationEnable = false;
                            notificationLedHide(LedTarget.LED6); //Live
                        } else {
                            accelerationEnable = true;
                            notificationLedShow(LedTarget.LED6); //Live
                        }

                        onKeyDownModeButton = false;
                    }
                }
            }

            @Override
            public void onKeyLongPress(int keyCode, KeyEvent keyEvent) {
                if (keyCode == KeyReceiver.KEYCODE_MEDIA_RECORD) {
                    Log.d(TAG, "Do end process.");
                    closeCamera();
                }
            }
        });

        //webUI用のサーバー開始処理
        this.context = getApplicationContext();
        this.webServer = new WebServer(this.context);
        try {
            this.webServer.start();
        } catch (IOException e) {
            e.printStackTrace();
            Log.d(TAG,"web server error : [" + e + "]" );
        }

        notificationCameraClose();

        mOpenCvCameraView = (ThetaView) findViewById(R.id.opencv_surface_view);
        mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
        mOpenCvCameraView.setCvCameraViewListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "Internal OpenCV library not found.");
        } else {
            Log.d(TAG, "OpenCV library found inside package.");
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }

        //---------------  added code ---------------
        notificationLedHide(LedTarget.LED3);

        notificationLedHide(LedTarget.LED4); //Still
        notificationLedHide(LedTarget.LED5); //Move
        notificationLedHide(LedTarget.LED6); //Live
        notificationLedHide(LedTarget.LED7); //REC
        notificationLedHide(LedTarget.LED8); //Card Error

        restoreControlParam();

        mFinished = true;

        usbSerialOpen();
        //-----------------------------------------

    }

    @Override
    protected void onPause() {
        closeCamera();

        //---------------  added code ---------------
        //スレッドを終わらせる指示。終了待ちしていません。
        mFinished = true;

        //シリアル通信の後片付け ポート開けてない場合にはCloseしないこと
        usbSerialColse();
        //-----------------------------------------

        super.onPause();
    }

    protected void onDestroy() {
        super.onDestroy();
        if (this.webServer != null) {
            this.webServer.stop();
        }
    }

    public void onCameraViewStarted(int width, int height) {
        mOutputFrame = new Mat(height, width, CvType.CV_8UC1);
        mStructuringElement = Imgproc.getStructuringElement(Imgproc.CV_SHAPE_ELLIPSE, new Size(9,9));    //15fps OK
    }

    public void onCameraViewStopped() {
        mOutputFrame.release();
        mStructuringElement.release();
    }

    //=======================================================================================
    void usbSerialOpen() {
        // Find all available drivers from attached devices.
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> usb = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        //定義済みのArduinoを利用するため、新たな定義を追加する必要がない
        //final ProbeTable probeTable = UsbSerialProber.getDefaultProbeTable();
        //probeTable.addProduct(0x2341,0x8036,CdcAcmSerialDriver.class);
        //List<UsbSerialDriver> usb = new UsbSerialProber(probeTable).findAllDrivers(manager);

        if (usb.isEmpty()) {
            int usb_num = usb.size();
            Log.d(TAG,"usb num =" + usb_num  );
            Log.d(TAG,"usb device is not connect."  );
            //return;
            //port = null;
        } else {
            // デバッグのため認識したデバイス数をしらべておく
            int usb_num = usb.size();
            Log.d(TAG,"usb num =" + usb_num  );

            // Open a connection to the first available driver.
            UsbSerialDriver driver = usb.get(0);

            //USBデバイスへのパーミッション付与用（機器を刺したときスルーしてもアプリ起動時にチャンスを与えるだけ。なくても良い。）
            mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
            manager.requestPermission( driver.getDevice() , mPermissionIntent);

            UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
            if (connection == null) {
                // You probably need to call UsbManager.requestPermission(driver.getDevice(), ..)
                // パーミッションを与えた後でも、USB機器が接続されたままの電源Off->On だとnullになる... 刺しなおせばOK
                Log.d(TAG,"M:Can't open usb device.\n");

                port = null;
            } else {
                port = driver.getPorts().get(0);

                try {
                    port.open(connection);
                    port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                    port.setDTR(true); // for arduino(ATmega32U4)
                    port.setRTS(true); // for arduino(ATmega32U4)

                    port.purgeHwBuffers(true,true);//念のため

                    mFinished = false;
                    start_read_thread();

                } catch (IOException e) {
                    // Deal with error.
                    e.printStackTrace();
                    Log.d(TAG, "M:IOException");
                    //return;
                }
            }
        }
    }
    void usbSerialColse() {
        if (port != null) {
            try {
                port.close();
                Log.d(TAG, "M:onDestroy() port.close()");
            } catch (IOException e) {
                Log.d(TAG, "M:onDestroy() IOException");
            }
        } else {
            Log.d(TAG, "M:port=null\n");
        }
    }
    //=======================================================================================


    //=======================================================================================
    // <<< 黒線認識処理用 固定値群 >>>
    // 基礎固定値
    final static int NOT_FOUND = -1;
    final static double BIN_WHITE = 255.0;
    final static double BIN_BLACK = 0.0;

    //高さ(上下)方向
    final static int PITCH_SEARCH_HIGHT = 40 ;
    final static int PITCH_SEARCH_START = 280 ;
    final static int PITCH_SEARCH_END = PITCH_SEARCH_START - PITCH_SEARCH_HIGHT ;
    //横(左右)方向
    final static int FRONT_CENTER = 320 ;
    final static int FRONT_LIMIT_HALF_WIDTH = 50 ;
    final static int FRONT_LIMIT_LEFT = (FRONT_CENTER - FRONT_LIMIT_HALF_WIDTH) ;
    final static int FRONT_LIMIT_RIGHT = (FRONT_CENTER + FRONT_LIMIT_HALF_WIDTH) ;

    //黒線ノイズ除去用
    final static int LINE_WIDTH_LIMIT = 8;
    //=======================================================================================

    public Mat onCameraFrame(CvCameraViewFrame inputFrame) {
        // get a frame by rgba() or gray()onCameraFrame
        Mat gray = inputFrame.gray();
        Mat rgba = inputFrame.rgba();

        // do some image processing
        Imgproc.threshold(gray, mOutputFrame, 64.0, 255.0, Imgproc.THRESH_BINARY);

        // Morphology (Opening)
        Imgproc.morphologyEx(mOutputFrame.clone(), mOutputFrame, Imgproc.MORPH_OPEN, mStructuringElement);

        //--------------------------
        // 黒線認識処理
        //--------------------------
        //全般メモ：急な折り返しとか交差は太くなるケースあるので、今回は黒線幅をあまり気にしない。　
        int forwardPos = NOT_FOUND;
        int rotationPos = NOT_FOUND ;
        int detectHight = PITCH_SEARCH_START ;

        forwardPos = searchForwardArea(PITCH_SEARCH_START, mOutputFrame);        // (1) 中央付近 進行方向探索

        if ( forwardPos == NOT_FOUND ) {
            rotationPos = searchRotationalDirection(PITCH_SEARCH_START, mOutputFrame);            //(2)中央付近以外 回転方向探索

        }
        //(3)離れている黒線を探索（進行方向探索を遠方へ→だめなら回転方向探索を遠方へ）
        if ( (forwardPos == NOT_FOUND) && (rotationPos==NOT_FOUND) ) {

            //(3)-1 : 進行方向探索を遠方へ
            for (int i=PITCH_SEARCH_START-1; i>PITCH_SEARCH_END; i--) {
                forwardPos = searchForwardArea(i, mOutputFrame);
                if ( forwardPos >=0 ) {
                    detectHight = i;
                    break;
                }
            }
            if ( forwardPos == NOT_FOUND ) {
                //(3)-2 : 回転方向探索を遠方へ
                for (int i=PITCH_SEARCH_START-1; i>PITCH_SEARCH_END; i-- ) {
                    rotationPos = searchRotationalDirection(i, mOutputFrame);
                    if ( rotationPos >=0 ) {
                        detectHight = i;
                        break;
                    }
                }
            }
        }

        //加減速のための黒線探索をしておく
        int accelerationPos = NOT_FOUND;
        accelerationPos = searchForwardArea(PITCH_SEARCH_ACCELERATION, mOutputFrame);

        //画像認識結果から制御指示（シリアル通信スレッドへ制御コマンド送信要求）
        controlBala(forwardPos, rotationPos, detectHight, accelerationPos);

        //2値画像へデバッグ情報描画
        drawBWResult( forwardPos, rotationPos, detectHight );
        //カラー画像へデバッグ情報描画
        drawColorResult( rgba, forwardPos, rotationPos, detectHight);


        //WebUI用のデータをセット　Mat型 rgba→jpeg
        Bitmap webuiFrameBmp = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(rgba, webuiFrameBmp);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        webuiFrameBmp.compress(Bitmap.CompressFormat.JPEG, 100, baos);
        latestLvFrame = baos.toByteArray();

        // OLED描画
        oledDrawStatus();

        //return mOutputFrame;   //2値画像を描画する場合
        return rgba;   //カラー画像を描画する場合
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

    //=======================================================================================
    // デバッグ描画関連
    //=======================================================================================
    void drawColorResult(Mat rgba, int forwardPos, int rotationPos, int detectHight ){
        //カラー補助線
        Imgproc.line(rgba, new Point(FRONT_CENTER, PITCH_SEARCH_START), new Point(FRONT_CENTER, PITCH_SEARCH_END), new Scalar(255, 0, 0, 255), 2 );
        Imgproc.line(rgba, new Point(FRONT_LIMIT_LEFT, PITCH_SEARCH_START), new Point(FRONT_LIMIT_LEFT, PITCH_SEARCH_END), new Scalar(0,  255, 0, 255), 2 );
        Imgproc.line(rgba, new Point(FRONT_LIMIT_RIGHT, PITCH_SEARCH_START), new Point(FRONT_LIMIT_RIGHT, PITCH_SEARCH_END), new Scalar(0, 255, 0, 255), 2 );
        Imgproc.line(rgba, new Point(FRONT_LIMIT_LEFT,PITCH_SEARCH_START), new Point(FRONT_LIMIT_RIGHT,PITCH_SEARCH_START), new Scalar(0 ,255, 0, 255), 2 );
        Imgproc.line(rgba, new Point(FRONT_LIMIT_LEFT,PITCH_SEARCH_END), new Point(FRONT_LIMIT_RIGHT,PITCH_SEARCH_END), new Scalar(0, 255, 0, 255), 2 );
        Imgproc.line(rgba, new Point(0, PITCH_SEARCH_START), new Point(FRONT_LIMIT_LEFT,PITCH_SEARCH_START), new Scalar(255 ,255, 0, 255), 2 );
        Imgproc.line(rgba, new Point(0, PITCH_SEARCH_END), new Point(FRONT_LIMIT_LEFT,PITCH_SEARCH_END), new Scalar(255, 255, 0, 255), 2 );
        Imgproc.line(rgba, new Point(FRONT_LIMIT_RIGHT, PITCH_SEARCH_START), new Point(639, PITCH_SEARCH_START), new Scalar(255 ,255, 0, 255), 2 );
        Imgproc.line(rgba, new Point(FRONT_LIMIT_RIGHT, PITCH_SEARCH_END), new Point(639, PITCH_SEARCH_END), new Scalar(255, 255, 0, 255), 2 );

        //draw Target Mark
        Scalar targetMarkColor = new Scalar(0,0,0,255);
        int targetX = NOT_FOUND;
        if ( forwardPos != NOT_FOUND ) {
            targetX = forwardPos;
            targetMarkColor = new Scalar(0, 255, 0, 255);
        }
        if ( rotationPos != NOT_FOUND ) {
            targetX = rotationPos;
            targetMarkColor = new Scalar(255, 255, 0, 255);
        }
        if ( targetX != NOT_FOUND ) {
            Log.d(TAG, "Ctrl targetX=" + String.valueOf(targetX) + ", detectHight=" + String.valueOf(detectHight) );

            Imgproc.circle(rgba, new Point(targetX, detectHight), 10, targetMarkColor, 2);
            Imgproc.circle(rgba, new Point(targetX, detectHight), 15, targetMarkColor, 2);
            Imgproc.line(rgba, new Point(targetX, detectHight+5), new Point(targetX,detectHight+15), targetMarkColor, 2 );
            Imgproc.line(rgba, new Point(targetX, detectHight-5), new Point(targetX,detectHight-15), targetMarkColor, 2 );
            Imgproc.line(rgba, new Point(targetX+5, detectHight), new Point(targetX+15,detectHight), targetMarkColor, 2 );
            Imgproc.line(rgba, new Point(targetX-5, detectHight), new Point(targetX-15,detectHight), targetMarkColor, 2 );
        }

        //draw motor L R value
        Scalar rectColor = new Scalar(0,0,0,255);
        Imgproc.rectangle( rgba, new Point(120, 60), new Point(530, 120), rectColor, -1);

        Scalar motorLColor = setPwmColor(pwmMotor0);
        Imgproc.putText( rgba, "L:"+ String.valueOf(pwmMotor0) +"/255", new Point(130, 100), 0, 1, motorLColor,2, 1, false );
        Scalar motorRColor = setPwmColor(pwmMotor1);
        Imgproc.putText( rgba, "R:"+ String.valueOf(pwmMotor1) + "/255", new Point(340, 100), 0, 1, motorRColor,2, 1, false );

    }
    Scalar setPwmColor(int pwmMotorVal) {
        Scalar color =  new Scalar(0,0,0,255);

        int val = (int) Math.abs(pwmMotorVal);
        if ( 0<= val && val < 60) {
            color = new Scalar(0,0,255,255);
        } else if ( 60<= val && val < 120) {
            color = new Scalar(0,255,0,255);
        } else if ( 120<= val && val < 180) {
            color = new Scalar(255,255,0,255);
        } else if ( 180<= val) {
            color = new Scalar(255,0,0,255);
        }

        return color;
    }

    void drawBWResult( int forwardPos, int rotationPos, int detectHight  ){
        //補助線
        Imgproc.line(mOutputFrame, new Point(0,PITCH_SEARCH_START), new Point(639,PITCH_SEARCH_START), new Scalar(0.0,0), 1 );
        Imgproc.line(mOutputFrame, new Point(0,PITCH_SEARCH_END), new Point(639,PITCH_SEARCH_END), new Scalar(0.0,0), 1 );
        Imgproc.line(mOutputFrame, new Point(FRONT_CENTER,PITCH_SEARCH_START), new Point(FRONT_CENTER,PITCH_SEARCH_END), new Scalar(0.0,0), 1 );
        Imgproc.line(mOutputFrame, new Point(FRONT_LIMIT_LEFT,PITCH_SEARCH_START), new Point(FRONT_LIMIT_LEFT,PITCH_SEARCH_END), new Scalar(0.0,0), 1 );
        Imgproc.line(mOutputFrame, new Point(FRONT_LIMIT_RIGHT,PITCH_SEARCH_START), new Point(FRONT_LIMIT_RIGHT,PITCH_SEARCH_END), new Scalar(0.0,0), 1 );

        //draw Target Mark
        //Scalar targetMarkColor = new Scalar(0.0); //黒
        Scalar targetMarkColor = new Scalar(255.0); //白
        int targetX = NOT_FOUND;
        if ( forwardPos != NOT_FOUND ) {
            targetX = forwardPos;
        }
        if ( rotationPos != NOT_FOUND ) {
            targetX = rotationPos;
        }
        if ( targetX != NOT_FOUND ) {
            Imgproc.circle(mOutputFrame, new Point(targetX, detectHight), 10, targetMarkColor, 2);
            Imgproc.circle(mOutputFrame, new Point(targetX, detectHight), 15, targetMarkColor, 2);
            Imgproc.line(mOutputFrame, new Point(targetX, detectHight+5), new Point(targetX,detectHight+15), targetMarkColor, 2 );
            Imgproc.line(mOutputFrame, new Point(targetX, detectHight-5), new Point(targetX,detectHight-15), targetMarkColor, 2 );
            Imgproc.line(mOutputFrame, new Point(targetX+5, detectHight), new Point(targetX+15,detectHight), targetMarkColor, 2 );
            Imgproc.line(mOutputFrame, new Point(targetX-5, detectHight), new Point(targetX-15,detectHight), targetMarkColor, 2 );
        }

    }


    //=======================================================================================
    // 画像認識まわりの部品
    //=======================================================================================
    private int searchForwardArea( int searchHight, Mat inputFrame ) {
        int result=NOT_FOUND;

        int edgeStart=NOT_FOUND;
        int edgeEnd=NOT_FOUND;

        double leftEdge = mOutputFrame.get(searchHight, FRONT_LIMIT_LEFT)[0];
        double rightEdge = mOutputFrame.get(searchHight, FRONT_LIMIT_RIGHT)[0];
        if ( (leftEdge==BIN_WHITE) && (rightEdge==BIN_WHITE) ) {
            // 左端=白、右端=白 → 進行方向エリアを普通に探す
            Point centerEdge = searchRightDownUpEdge(FRONT_LIMIT_LEFT, FRONT_LIMIT_RIGHT, searchHight, mOutputFrame);
            edgeStart = (int) centerEdge.x;
            edgeEnd = (int) centerEdge.y;
        } else if ( (leftEdge==BIN_BLACK) && (rightEdge==BIN_WHITE) ) {
            // 左端=黒、右端=白 → 中央左端から 左右のUpエッジを探す 左は左端頭打ち。右は必ず解あり。
            edgeStart = searchLeftUpEdge(FRONT_LIMIT_LEFT, searchHight, mOutputFrame);
            if (edgeStart==NOT_FOUND) {
                edgeStart=0;
            }
            edgeEnd = searchRightUpEdge(FRONT_LIMIT_LEFT, searchHight, mOutputFrame);

        } else if ( (leftEdge==BIN_WHITE) && (rightEdge==BIN_BLACK ) ) {
            // 左端=白、右端=黒 → 中央右端から 左右のUpエッジを探す 左は必ず解あり。右は右端頭打ち。
            edgeStart = searchLeftUpEdge(FRONT_LIMIT_RIGHT, searchHight, mOutputFrame);
            edgeEnd = searchRightUpEdge(FRONT_LIMIT_RIGHT, searchHight, mOutputFrame);
            if (edgeEnd==NOT_FOUND) {
                edgeEnd = 0;
            }

        } else if (  (leftEdge==BIN_BLACK) && (rightEdge==BIN_BLACK ) ) {
            // 左端=黒、右端=黒 → 中央左端と中央右端それぞれからエッジを探し 中心が中央に近いほうを採用

            // 中央左端からエッジ探索（両側とも解ありかは不明だが、それぞれ画像端で頭打ちさせる）
            int edgeStartLeft = searchLeftUpEdge(FRONT_LIMIT_LEFT, searchHight, mOutputFrame);
            if (edgeStartLeft==NOT_FOUND) { edgeStartLeft=0; }
            int edgeEndLeft=searchRightUpEdge(FRONT_LIMIT_LEFT, searchHight, mOutputFrame);
            if (edgeEndLeft==NOT_FOUND) { edgeEndLeft=(mOutputFrame.width()-1); }

            //中央右端からエッジ探索
            int edgeStartRight = searchLeftUpEdge(FRONT_LIMIT_RIGHT, searchHight, mOutputFrame);
            if (edgeStartRight==NOT_FOUND) { edgeStartRight=0; }
            int edgeEndRight = searchRightUpEdge(FRONT_LIMIT_RIGHT, searchHight, mOutputFrame);
            if (edgeEndRight==NOT_FOUND) { edgeEndRight=(mOutputFrame.width()-1); }

            int axisLeft = (edgeStartLeft+edgeEndLeft)/2;
            int axisRight = (edgeStartRight+edgeEndRight)/2;
            if (  Math.abs(FRONT_CENTER - axisLeft) > Math.abs(FRONT_CENTER - axisRight)  ) {
                edgeStart = edgeStartRight;
                edgeEnd = edgeEndRight;
            } else {
                edgeStart = edgeStartLeft;
                edgeEnd = edgeEndLeft;
            }
        }

        if ( (edgeStart>=0) && (edgeEnd>=0) ) {
            result = (edgeStart + edgeEnd)/2 ;
        }

        return result;
    }

    private int searchRotationalDirection( int searchHight, Mat inputFrame ){
        int result=NOT_FOUND;
        int edgeStart=NOT_FOUND;
        int edgeEnd=NOT_FOUND;

        //(2)-1 回転方向　右方探索
        Point rightSearchEdge = searchRightDownUpEdge(FRONT_LIMIT_RIGHT, (mOutputFrame.width()-1), searchHight, mOutputFrame);
        int edgeStartRight = (int) rightSearchEdge.x;
        int edgeEndRight = (int) rightSearchEdge.y;
        int axisRight = NOT_FOUND;

        if ( (edgeStartRight >= 0) && (edgeEndRight >= 0) ) {
            axisRight = (edgeStartRight + edgeEndRight)/2;
        } else if ( (edgeStartRight >= 0) && (edgeEndRight == NOT_FOUND) ) {
            //左面　左端から中央方向へ　探索を伸ばすのですが、仮で頭打ち
            edgeEndRight=mOutputFrame.width()-1;
            axisRight = (edgeStartRight + edgeEndRight)/2;
        } else {
            //みつからなかった
        }

        //(2)-2 回転方向　左方探索
        Point leftSearchEdge = searchLeftDownUpEdge(FRONT_LIMIT_LEFT, 0, searchHight, mOutputFrame);
        int edgeStartLeft = (int) leftSearchEdge.x;
        int edgeEndLeft = (int) leftSearchEdge.y;
        int axisLeft = NOT_FOUND;

        if ( (edgeStartLeft>=0) && (edgeEndLeft>=0) ) {
            axisLeft = (edgeStartLeft + edgeEndLeft)/2;
        } else if ( (edgeStartLeft>=0) && (edgeEndLeft==NOT_FOUND) ) {
            //右面　右端から中央方向へ　探索を伸ばすのですが、仮で頭打ち
            edgeEndLeft=0;
            axisLeft = (edgeStartLeft + edgeEndLeft)/2;
        } else {
            //みつからなかった
        }

        // (2)-3 回転方向　総合判断
        if ( (axisRight>=0) && (axisLeft>=0) ) {
            // 中央に近いほうを採用
            if (  Math.abs(FRONT_CENTER - axisLeft) > Math.abs(FRONT_CENTER - axisRight)  ) {
                edgeStart = edgeStartRight;
                edgeEnd = edgeEndRight;
            } else {
                edgeStart = edgeStartLeft;
                edgeEnd = edgeEndLeft;
            }

        } else if (axisRight>=0) {
            //右を採用
            edgeStart = edgeStartRight;
            edgeEnd = edgeEndRight;
        } else if (axisLeft>=0) {
            //左を採用
            edgeStart = edgeStartLeft;
            edgeEnd = edgeEndLeft;
        } else {
            //みつからなかった
        }

        if ( (edgeStart>=0) && (edgeEnd>=0) ) {
            result = (edgeStart + edgeEnd)/2 ;
        }
        return result;
    }

    private Point searchLeftDownUpEdge(int startPos, int endPos, int searchHight, Mat inputFrame) {
        double valCur = BIN_WHITE;
        double valPre = BIN_WHITE;

        int edgeStartPos = NOT_FOUND;
        int edgeEndPos = NOT_FOUND;

        for(int i=startPos; i>endPos; i--){
            valCur = inputFrame.get(searchHight, i)[0] ;
            if ( (valCur == BIN_BLACK) && ( valCur != valPre ) ) {
                edgeStartPos = i;
            } else if ( (valCur == BIN_WHITE) && ( valCur != valPre ) && (edgeStartPos!=NOT_FOUND) ) {
                edgeEndPos = i;
                int width = edgeStartPos - edgeEndPos;
                if (width<LINE_WIDTH_LIMIT) {
                    edgeStartPos = NOT_FOUND;
                    edgeEndPos = NOT_FOUND;
                } else {
                    break;
                }
            }
            valPre = valCur;
        }


        Point resultPt = new Point(edgeStartPos, edgeEndPos);
        return resultPt;
    }
    private Point searchRightDownUpEdge(int startPos, int endPos, int searchHight, Mat inputFrame) {
        double valCur = BIN_WHITE;
        double valPre = BIN_WHITE;

        int edgeStartPos = NOT_FOUND;
        int edgeEndPos = NOT_FOUND;

        for(int i=startPos; i<=endPos; i++){
            valCur = inputFrame.get(searchHight, i)[0] ;
            if ( (valCur == BIN_BLACK) && ( valCur != valPre ) ) {
                edgeStartPos = i;
            } else if ( (valCur == BIN_WHITE) && ( valCur != valPre ) && (edgeStartPos!=NOT_FOUND) ) {
                edgeEndPos = i;
                int width = edgeEndPos - edgeStartPos;
                if (width<LINE_WIDTH_LIMIT) {
                    edgeStartPos = NOT_FOUND;
                    edgeEndPos = NOT_FOUND;
                } else {
                    break;
                }
            }
            valPre = valCur;
        }

        Point resultPt = new Point(edgeStartPos, edgeEndPos);
        return resultPt;
    }
    private int searchLeftUpEdge(int startPos, int searchHight, Mat inputFrame) {
        double valCur = BIN_WHITE;
        double valPre = BIN_WHITE;

        int leftPos=NOT_FOUND;
        for(int i=startPos; i>0; i--){
            valCur = inputFrame.get(searchHight, i)[0] ;
            if ( (valCur == BIN_WHITE) && ( valCur != valPre ) ) {
                leftPos = i;
                break;
            }
            valPre = valCur;
        }
        return leftPos;
    }
    private int searchRightUpEdge(int startPos, int searchHight, Mat inputFrame) {
        double valCur = BIN_WHITE;
        double valPre = BIN_WHITE;

        int rightPos=NOT_FOUND;
        for(int i=startPos; i<(inputFrame.width()); i++){
            valCur = inputFrame.get(searchHight, i)[0] ;
            if ( (valCur == BIN_WHITE) && ( valCur != valPre ) ) {
                rightPos = i;
                break;
            }
            valPre = valCur;
        }
        return rightPos;
    }


    //=======================================================================================
    // 画像認識結果からBALAの制御（シリアル通信スレッドへ制御コマンド送信要求）
    //=======================================================================================
    //--- 加減速制御 ---
    boolean accelerationEnable = false;
    boolean accelerationStat = false;

    final static double ACCELERATION_THRESHOLD = 30; //[pixel/sec]
    final static double ACCELERATION_ANGLE_LIMIT = 20; //[pixel]
    final static int PITCH_SEARCH_ACCELERATION = PITCH_SEARCH_START - 41 ;

    final static double FORWARD_OFFSET_NORMAL = 80; //前進オフセット: PID制御量=0 のときの前進量 motor0には1.0倍で適用する
    final static double FORWARD_OFFSET_FAST = 210;  // if (lrDiff>=1) { (255/lrDiff) } else { (255*lrDiff) } を超えないこと かつ 制御マージンも持つこと
    final static double SPEED_ACCEL_STEP = 12;
    final static double SPEED_DECEL_STEP = 32.5;



    //--- 直進<->回転 制御変わり目の　動作安定待ち時間（あとで弄りたくなる項目） ---
    // ※映像のレイテンシ以上を設定 or 動作安定時間　の長いほうを設定-
    int controlSwitchWaitMs;

    //--- 回転（調整項目） ---
    int rotationPwm;
    int rotation180degTime;

    //--- 直進係数（調整項目） ---
    double forwardOffset = FORWARD_OFFSET_NORMAL; //これはそれほど調節が必要な項目ではないのでここで設定している
    double lrDiff;

    //--- PID制御パラメータ（調整項目） ---
    double Kp;
    double Ki;
    double Kd;



    //--- PID制御変数 ---
    double angle = 0;        //[pixel]
    double angleIntegral=0; //[pixel*sec]
    double angleVelocity=0; //[pixel/sec]
    double lastAngle = 0;

    double samplingSec = 1.0/(double)ThetaView.FPS;  //サンプリング周期[sec] = フレームレート依存

    //--- 制御状態 ---
    final static int LAST_CTRL_FORWARD = 0 ;
    final static int LAST_CTRL_ROTATION = 1 ;
    final static int LAST_CTRL_ROTATION_WAIT = 2 ;
    int lastControl = NOT_FOUND;
    boolean linetraceStopReq = false;

    void resetControlParam() {
        linetraceStopReq=false;

        angleIntegral=0;
        angleVelocity=0; //放置しても問題ない
        lastAngle = 0;

        forwardOffset=FORWARD_OFFSET_NORMAL;
    }

    void controlBala( int forwardPos, int rotationPos, int detectHight, int accelerationPos ) {
        if (linetraceEnable) {
            if (!sendReq) { //制御可能

                //webUIからの停止要求対応
                boolean webUiStop = false;
                if ( (linetraceStopReq) && (forwardOffset == FORWARD_OFFSET_NORMAL) ) {
                    linetraceStopReq=false;

                    controlWait(controlSwitchWaitMs);
                    linetraceEnable = false;

                    notificationLedHide(LedTarget.LED3);
                    notificationLedHide(LedTarget.LED7); //REC

                    forwardPos=NOT_FOUND;
                    rotationPos=NOT_FOUND;
                    webUiStop = true;
                }

                //前進
                if ( forwardPos != NOT_FOUND ) {
                    notificationLedBlink(LedTarget.LED3, LedColor.GREEN, 1000);
                    oledLineTraceStat = LT_STAT_ON_FORWARD;

                    if ( lastControl == LAST_CTRL_ROTATION ) {
                        //回転動作後の安定待ち
                        controlWait(controlSwitchWaitMs);
                        lastControl = LAST_CTRL_ROTATION_WAIT;
                    } else {
                        controlForward( forwardPos, detectHight, accelerationPos );
                        lastControl = LAST_CTRL_FORWARD;
                    }
                }

                //回転
                if (rotationPos != NOT_FOUND){
                    notificationLedBlink(LedTarget.LED3, LedColor.YELLOW, 1000);
                    oledLineTraceStat = LT_STAT_ON_ROTATION;

                    if ( lastControl != LAST_CTRL_ROTATION_WAIT ) {
                        //制御変数リセット
                        resetControlParam();
                        //直進動作後の安定待ち
                        controlWait(controlSwitchWaitMs);

                        lastControl = LAST_CTRL_ROTATION_WAIT;
                    } else {
                        controlRotation(rotationPos);

                        lastControl = LAST_CTRL_ROTATION;
                    }
                }

                //黒線が見つからない
                if ( (forwardPos==NOT_FOUND) && (rotationPos==NOT_FOUND) ) {
                    if (!webUiStop) {
                        notificationLedBlink(LedTarget.LED3, LedColor.MAGENTA, 1000);
                        oledLineTraceStat = LT_STAT_ON_LOST;
                    }
                    lastControl = NOT_FOUND;
                }

            }
        }
    }

    void controlForward( int forwardPos, int detectHight, int accelerationPos ) {
        double ctrlVal0 = 0;
        double ctrlVal1 = 0;

        //操舵のPID制御
        angle = FRONT_CENTER - forwardPos;  //偏差
        angleIntegral += (angle * samplingSec); //偏差の積分
        angleVelocity = (angle - lastAngle)/samplingSec; //偏差の微分
        lastAngle = angle;

        double pidResult = Kp*angle + Ki*angleIntegral + Kd*angleVelocity;

        //加減速制御
        int accelerationAngle;
        if (accelerationPos==NOT_FOUND) {
            accelerationAngle = (int)ACCELERATION_ANGLE_LIMIT + 1;
        } else {
            accelerationAngle = FRONT_CENTER-accelerationPos;
        }

        if ( (accelerationEnable) &&
                (!linetraceStopReq) &&
                ( lastControl == LAST_CTRL_FORWARD ) &&
                ( detectHight  == PITCH_SEARCH_START ) &&
                ( Math.abs(angleVelocity) <= ACCELERATION_THRESHOLD ) &&
                ( Math.abs(accelerationAngle) <= ACCELERATION_ANGLE_LIMIT ) ) {
            //加速
            accelerationStat=true;
            notificationLedShow(LedTarget.LED7); //REC

            forwardOffset+=SPEED_ACCEL_STEP;
            if (forwardOffset>=FORWARD_OFFSET_FAST) {
                forwardOffset = FORWARD_OFFSET_FAST;
            }
        } else {
            //減速
            accelerationStat=false;
            notificationLedHide(LedTarget.LED7); //REC

            forwardOffset-=SPEED_DECEL_STEP;
            if (forwardOffset<=FORWARD_OFFSET_NORMAL) {
                forwardOffset = FORWARD_OFFSET_NORMAL;
            }
        }

        //制御量決定
        if ( pidResult < 0 ) {
            //右へいきたいので motor0加算, motor1減算
            ctrlVal0 = forwardOffset + Math.abs(pidResult/2);
            ctrlVal1 = forwardOffset - Math.abs(pidResult/2);
        } else {
            //左へいきたいので　motor0減算, motor1加算
            ctrlVal0 = forwardOffset - Math.abs(pidResult/2);
            ctrlVal1 = forwardOffset + Math.abs(pidResult/2);
        }
        pwmMotor0 = (int)(ctrlVal0);
        pwmMotor1 = (int)(ctrlVal1 * lrDiff);

        driveTimeMs = 5; //Step制御をしない場合意味はないが、accept受信までのwait時間(余裕量)にはなる
        //driveTimeMs = 100; //Step Mode用
        //driveTimeMs = 150; //Step Modeで1回の動作を長く実験をしたとき用

        Log.d(TAG, "Ctrl F: m0=" + String.valueOf(pwmMotor0) + ", m1=" + String.valueOf(pwmMotor1) + ", PID=" + String.valueOf(pidResult) );
        sendReq = true;
    }

    void controlRotation( int rotationPos ) {
        int val = FRONT_CENTER - rotationPos;
        if ( val < 0 ) {
            //右回転
            pwmMotor0 = rotationPwm;
            pwmMotor1 = -1*rotationPwm;
        } else {
            //左回転/
            pwmMotor0 = -1 * rotationPwm;
            pwmMotor1 = rotationPwm;
        }
        double ctrlVal = 10 + rotation180degTime * ( (double)Math.abs(val)/(double)FRONT_CENTER );
        driveTimeMs =  (int)ctrlVal  ;

        Log.d(TAG, "Ctrl R: m0=" + String.valueOf(pwmMotor0) + ", m1=" + String.valueOf(pwmMotor1) + ", time=" + String.valueOf(driveTimeMs) + ", ctrlVal=" + String.valueOf(ctrlVal) );
        sendReq = true;
    }

    void controlWait(int waitMs) {
        pwmMotor0 = 0;
        pwmMotor1 = 0;
        driveTimeMs = waitMs;
        Log.d(TAG, "Ctrl Stop: time=" + String.valueOf(driveTimeMs) );

        sendReq = true;
    }


    //=======================================================================================
    // シリアル通信まわり
    //=======================================================================================
    boolean linetraceEnable = false;
    boolean sendReq = false;
    int pwmMotor0 = 0;
    int pwmMotor1 = 0;
    int driveTimeMs = 0;

    //シリアル受信スレッド
    public void start_read_thread(){
        new Thread(new Runnable(){
            @Override
            public void run() {
                //android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND);

                try {
                    Log.d(TAG, "Thread Start");

                    while(mFinished==false){

                        try {
                            //シリアル送信（制御コマンド） -> 受信(応答チェック)
                            if (sendReq == true){

                                if ( (pwmMotor0>=0) && (pwmMotor1>=0) ) { //前進
                                    String setCmd = "mov_w " + String.valueOf(pwmMotor0) + " " + String.valueOf(pwmMotor1) + " " + String.valueOf(driveTimeMs) + "\n";
                                    port.write(setCmd.getBytes(), setCmd.length());
                                    Log.d(TAG, "Ctrl T:" + setCmd + ", ms=" + String.valueOf(driveTimeMs));
                                    readWait(1000);

                                } else { //回転
                                    String setCmd = "mov_s " + String.valueOf(pwmMotor0) + " " + String.valueOf(pwmMotor1) + " " + String.valueOf(driveTimeMs) + "\n";
                                    port.write(setCmd.getBytes(), setCmd.length());
                                    Log.d(TAG, "Ctrl T:" + setCmd);
                                    readWait(1000);
                                }

                                sendReq = false;
                            }

                        } catch (IOException e) {
                            // Deal with error.
                            e.printStackTrace();
                            Log.d(TAG, "Ctrl T:IOException [" + e + "]");
                            notificationLedBlink(LedTarget.LED3, LedColor.RED, 1000);
                            oledLineTraceStat = LT_STAT_ERROR;

                            break;
                        }

                        //ポーリングが高頻度になりすぎないよう5msスリープする
                        Thread.sleep(5);
                    }

                    Log.d(TAG, "Thread End");

                } catch (InterruptedException e) {
                    // Deal with error.
                    e.printStackTrace();
                    Log.d(TAG, "T:InterruptedException");
                }
            }
        }).start();
    }

    void readWait(int TimeOutMs){
        int timeoutCount = TimeOutMs;

        String rcvStr = "";
        while (timeoutCount>0) {
            byte buff[] = new byte[256];
            int num=0;
            try {
                num= port.read(buff, buff.length);

            } catch (IOException e) {
                e.printStackTrace();
                Log.d(TAG, "T:read() IOException");
                num = -1;
            }

            if (num!=0) {
                rcvStr = new String(buff, 0, num);
                rcvStr = rcvStr.trim();
                Log.d(TAG, "Ctrl len=" + rcvStr.length() + ", RcvDat=[" + rcvStr + "]" );

                if ( rcvStr.equals("accept") ) {
                    break;
                }
            }
            timeoutCount--;
        }
        if ( timeoutCount == 0 ) {
            Log.d(TAG, "Ctrl:Timeout!!!" );
        }
    }

    //=======================================================================================
    // 調整値 保存と復帰
    //=======================================================================================

    private static final String SAVE_KEY_SWITCH_WAIT = "controlSwitchWaitMs";

    private static final String SAVE_KEY_R_PWM = "rotationPwm";
    private static final String SAVE_KEY_R_180_TIME = "rotation180degTime";

    private static final String SAVE_KEY_LR_DIFF = "lrDiff";

    private static final String SAVE_KEY_KP = "Kp";
    private static final String SAVE_KEY_KI = "Ki";
    private static final String SAVE_KEY_KD = "Kd";

    SharedPreferences sharedPreferences;

    void restoreControlParam() {
        //保存されていたデータの読み込み
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        //--- 直進<->回転 制御変わり目の　動作安定待ち時間（映像のレイテンシ以上を設定 or 動作安定時間　の長いほうを設定） ---
        controlSwitchWaitMs = sharedPreferences.getInt(SAVE_KEY_SWITCH_WAIT, 220);

        //--- 回転（調整項目） ---
        rotationPwm = sharedPreferences.getInt(SAVE_KEY_R_PWM, 128);
        rotation180degTime = sharedPreferences.getInt(SAVE_KEY_R_180_TIME, 745);//バランスウェイト 単三電池 1本時

        //--- 直進係数（調整項目） ---
        //※トレッドの中心が車体中心からずれると変わる。組み立て注意。
        lrDiff = sharedPreferences.getFloat(SAVE_KEY_LR_DIFF, (float) 1.1625);

        //--- PID制御パラメータ（調整項目） ---
        Kp = sharedPreferences.getFloat(SAVE_KEY_KP, (float)2.25);
        Ki = sharedPreferences.getFloat(SAVE_KEY_KI, (float)0.25);
        Kd = sharedPreferences.getFloat(SAVE_KEY_KD, (float)0.195);
    }

    void saveControlParam() {
        SharedPreferences.Editor editor = sharedPreferences.edit();

        editor.putInt(SAVE_KEY_SWITCH_WAIT, controlSwitchWaitMs);
        editor.putInt(SAVE_KEY_R_PWM, rotationPwm);
        editor.putInt(SAVE_KEY_R_180_TIME, rotation180degTime);
        editor.putFloat(SAVE_KEY_LR_DIFF, (float) lrDiff);
        editor.putFloat(SAVE_KEY_KP, (float) Kp);
        editor.putFloat(SAVE_KEY_KI, (float) Ki);
        editor.putFloat(SAVE_KEY_KD, (float) Kd);

        editor.commit();
    }

    //=======================================================================================
    //<<< web server processings >>>
    //=======================================================================================

    private Context context;
    private WebServer webServer;

    private class WebServer extends NanoHTTPD {
        private static final int PORT = 8888;
        private Context context;

        public WebServer(Context context) {
            super(PORT);
            this.context = context;
        }

        @Override
        public Response serve(IHTTPSession session) {
            Method method = session.getMethod();
            String uri = session.getUri();
            Log.d(TAG, "serve:[method]=" + method + ", [uri]=" + uri );

            switch (method) {
                case GET:
                    if ( uri.equals("/js/preview.js") ) {
                        return serveAssetFiles(uri);
                    } else if ( uri.equals("/favicon.ico") ) {
                        return serveAssetFiles("/img/theta_logo.jpg");
                    } else {
                        return this.serveHtml(uri);
                    }
                case POST:
                    if (uri.equals("/preview/commands/execute")) {
                        String postData = getPostData(session);
                        Log.d(TAG, "serve:[postData]=" + postData );

                        return servePreviewFrame();
                    } else {
                        Map<String, List<String>> parameters = this.parseBodyParameters(session);
                        execButtonAction(parameters);
                        return this.serveHtml(uri);
                    }
                default:
                    return newFixedLengthResponse(Status.METHOD_NOT_ALLOWED, "text/plain",
                            "Method [" + method + "] is not allowed.");
            }
        }

        private String getPostData(IHTTPSession session ) {
            String postData="";
            Map<String, String> tmpRequestFile = new HashMap<>();
            try {
                session.parseBody(tmpRequestFile);
                postData = tmpRequestFile.get("postData");
            } catch (IOException e) {
                e.printStackTrace();
            } catch (ResponseException e) {
                e.printStackTrace();
            }
            return postData;
        }

        private Map<String, List<String>> parseBodyParameters(IHTTPSession session) {
            Map<String, String> tmpRequestFile = new HashMap<String, String>();
            try {
                session.parseBody(tmpRequestFile);
            } catch (IOException e) {
                e.printStackTrace();
            } catch (ResponseException e) {
                e.printStackTrace();
            }
            return session.getParameters();
        }

        private Response servePreviewFrame() {
            if ( latestLvFrame == null ) {
                Bitmap blackBmp = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                blackBmp.compress(Bitmap.CompressFormat.JPEG, 100, baos);

                byte[] frame = baos.toByteArray();
                InputStream is = new ByteArrayInputStream( frame );
                return newChunkedResponse(Response.Status.OK, "image/jpeg", is);
            } else {
                InputStream is = new ByteArrayInputStream( latestLvFrame );
                return newChunkedResponse(Response.Status.OK, "image/jpeg", is);
            }
        }

        private Response serveAssetFiles(String uri) {
            if (uri.equals("/")){
                uri = "/index.html";
            }

            ContentResolver contentResolver = context.getContentResolver();
            String mimeType = contentResolver.getType(Uri.parse(uri));
            String filePath = uri.replaceAll("(^\\/)(.+)","$2");

            try {
                AssetManager assetManager = context.getAssets();
                InputStream is = assetManager.open(filePath);
                return newChunkedResponse(Response.Status.OK, mimeType, is);
            } catch (IOException e ) {
                e.printStackTrace();
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
            }
        }

        private Response serveHtml(String uri) {
            String html="";

            switch (uri) {
                case "/":
                    html = editHtml();
                    return newFixedLengthResponse(Status.OK, "text/html", html);
                default:
                    html = "URI [" + uri + "] is not found.";
                    return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", html);
            }
        }

        public static final String buttonName1 = "Reflect" ;
        public static final String buttonName2 = "Save" ;
        public static final String buttonName3 = "Start" ;
        public static final String buttonName4 = "Stop" ;
        public static final String buttonName5 = "Acceleration On/Off" ;
        public static final String buttonName6 = "turn 180" ;

        private void execButtonAction( Map<String, List<String>> inParameters ) {
            if (inParameters.containsKey("button")) {
                List<String> button = inParameters.get("button");
                Log.d(TAG, "button=" + button.toString() );

                if ( button.get(0).equals(buttonName1) ) {         //Reflect
                    reflectParam( inParameters );
                } else if ( button.get(0).equals(buttonName2) ) { //Save
                    reflectParam( inParameters );
                    saveControlParam();
                } else if ( button.get(0).equals(buttonName3) ) { //Start
                    if ( (!linetraceEnable) && (!linetraceStopReq) ) {
                        resetControlParam(  );
                        linetraceEnable = true;
                    }
                } else if ( button.get(0).equals(buttonName4) ) { //Stop
                    if (!linetraceStopReq) {
                        linetraceStopReq=true;
                    }
                } else if ( button.get(0).equals(buttonName5) ) { //Acceleration
                    if (accelerationEnable) {
                        accelerationEnable = false;
                        notificationLedHide(LedTarget.LED6); //Live
                    } else {
                        accelerationEnable = true;
                        notificationLedShow(LedTarget.LED6); //Live
                    }
                } else if ( button.get(0).equals(buttonName6) ) { //turn 180
                    if (!linetraceEnable) {
                        controlRotation(0);
                    }
                }
            }
        }

        private void reflectParam( Map<String, List<String>> inParameters ) {
            List<String> inSwitchWait = inParameters.get(SAVE_KEY_SWITCH_WAIT);
            List<String> inRotationPwm = inParameters.get(SAVE_KEY_R_PWM);
            List<String> inRotation180degTime = inParameters.get(SAVE_KEY_R_180_TIME);
            List<String> inLrDiff = inParameters.get(SAVE_KEY_LR_DIFF);
            List<String> inKp = inParameters.get(SAVE_KEY_KP);
            List<String> inKi = inParameters.get(SAVE_KEY_KI);
            List<String> inKd = inParameters.get(SAVE_KEY_KD);

            controlSwitchWaitMs =  Integer.parseInt( inSwitchWait.get(0) );
            rotationPwm = Integer.parseInt( inRotationPwm.get(0) );
            rotation180degTime = Integer.parseInt( inRotation180degTime.get(0) );
            lrDiff = Double.parseDouble( inLrDiff.get(0) );
            Kp = Double.parseDouble( inKp.get(0) );
            Ki = Double.parseDouble( inKi.get(0) );
            Kd = Double.parseDouble( inKd.get(0) );
        }

        private String editHtml() {
            String html = "";

            html += "<html>";
            html += "<head>";
            //html += "  <meta name='viewport' content='width=device-width,initial-scale=1'>";
            html += "  <meta name='viewport' content='width=480,initial-scale=0.55'>";
            html += "  <title>M5BALA Line Tracer 360</title>";
            html += "  <script src=\"js/preview.js\"></script>";
            html += "</head>";
            html += "<body onLoad=\"updatePreviwFrame();\">";
            html += "";
            html += "<form action='/' method='post' name='SettingForm'>";

            html += "  <hr>";
            html += "  <h2>[First Person View]</h2>";
            html += "  <hr>";
            html += "  <img id=\"lvimg\" src=\"\" width=\"640\" height=\"320\">";
            html += "  <hr>";
            html += "  <h2>[Parameter Settings]</h2>";
            html += "  <hr>";
            html += "  <table>";
            html += "    <tr>";
            html += "      <td> </td>";
            html += "      <td> </td>";
            html += "      <td><input type='submit' name='button' value='" + buttonName1 + "'></td>";
            html += "      <td> </td>";
            html += "      <td> </td>";
            html += "      <td><input type='submit' name='button' value='" + buttonName2 + "'></td>";
            html += "      <td> </td>";
            html += "      <td> </td>";
            html += "    </tr>";
            html += "  </table>";
            html += "  <hr>";

            html += editParameterList();

            html += "  <hr>";
            html += "  <table>";
            html += "    <tr>";
            html += "      <td> </td>";
            html += "      <td> </td>";
            html += "      <td><input type='submit' name='button' value='" + buttonName3 + "'></td>";
            html += "      <td> </td>";
            html += "      <td> </td>";
            html += "      <td><input type='submit' name='button' value='" + buttonName4 + "'></td>";
            html += "      <td> </td>";
            html += "      <td> </td>";
            html += "      <td> </td>";
            html += "      <td> </td>";
            html += "      <td><input type='submit' name='button' value='" + buttonName5 + "'></td>";
            html += "      <td> </td>";
            html += "      <td> </td>";
            html += "      <td> </td>";
            html += "      <td> </td>";
            html += "      <td><input type='submit' name='button' value='" + buttonName6 + "'></td>";
            html += "    </tr>";
            html += "  </table>";
            html += "  <hr>";

            html += "</form>";
            html += "";
            html += "</body>";
            html += "</html>";

            return html;
        }

        private String editParameterList() {
            String html = "";

            html += "  <table>";
            html += "    <tr>";
            html += "      <td style='background:#CCCCCC'>Parameter Name</td>";
            html += "      <td style='background:#CCCCCC'>Value</td>";
            html += "    </tr>";

            html += "    <tr>";
            html += "      <td>" + SAVE_KEY_SWITCH_WAIT + "</td>";
            html += "      <td><input type='number' name='" + SAVE_KEY_SWITCH_WAIT +  "' value='" + String.valueOf(controlSwitchWaitMs) + "' autocomplete='off'></td>";
            html += "    </tr>";

            html += "    <tr>";
            html += "      <td></td>";
            html += "      <td></td>";
            html += "    </tr>";

            html += "    <tr>";
            html += "      <td>" + SAVE_KEY_R_PWM + "</td>";
            html += "      <td><input type='number' name='" + SAVE_KEY_R_PWM +  "' value='" + String.valueOf(rotationPwm) + "' autocomplete='off'></td>";
            html += "    </tr>";
            html += "    <tr>";
            html += "      <td>" + SAVE_KEY_R_180_TIME + "</td>";
            html += "      <td><input type='number' name='" + SAVE_KEY_R_180_TIME +  "' value='" + String.valueOf(rotation180degTime) + "' autocomplete='off'></td>";
            html += "    </tr>";

            html += "    <tr>";
            html += "      <td></td>";
            html += "      <td></td>";
            html += "    </tr>";

            html += "    <tr>";
            html += "      <td>" + SAVE_KEY_LR_DIFF + "</td>";
            html += "      <td><input type='number' name='" + SAVE_KEY_LR_DIFF +  "' value='" + String.valueOf(lrDiff) + "' autocomplete='off' step='any' min='0.0'></td>";
            html += "    </tr>";

            html += "    <tr>";
            html += "      <td></td>";
            html += "      <td></td>";
            html += "    </tr>";

            html += "    <tr>";
            html += "      <td>" + SAVE_KEY_KP + "</td>";
            html += "      <td><input type='number' name='" + SAVE_KEY_KP +  "' value='" + String.valueOf(Kp) + "' autocomplete='off' step='any'min='0.0'></td>";
            html += "    </tr>";
            html += "    <tr>";
            html += "      <td>" + SAVE_KEY_KI + "</td>";
            html += "      <td><input type='number' name='" + SAVE_KEY_KI +  "' value='" + String.valueOf(Ki) + "' autocomplete='off' step='any' min='0.0'></td>";
            html += "    </tr>";
            html += "    <tr>";
            html += "      <td>" + SAVE_KEY_KD + "</td>";
            html += "      <td><input type='number' name='" + SAVE_KEY_KD +  "' value='" + String.valueOf(Kd) + "' autocomplete='off' step='any' min='0.0'></td>";
            html += "    </tr>";
            html += "  </table>";

            return html;
        }

    }

    //=======================================================================================
    // OLED表示
    //=======================================================================================
    //LedTarget
    private static final String LT_STAT_OFF = "Off";
    private static final String LT_STAT_ON_WAIT = "Wait";
    private static final String LT_STAT_ON_FORWARD  = "Forward";
    private static final String LT_STAT_ON_ROTATION = "Rotation";
    private static final String LT_STAT_ON_LOST = "Lost";
    private static final String LT_STAT_ERROR = "Error";

    private String oledLineTraceStat = LT_STAT_OFF;

    private void oledDrawStatus() {
        oledDisplay.clear();

        int textLine1  = 0;
        int textLine2  = 8;
        int textLine3  = 16;

        String oledAccMode = "";
        String oledAccStat = "";
        if ( accelerationEnable ){
            oledAccMode = "On";
        } else {
            oledAccMode = "Off";
        }
        if ( accelerationStat ){
            oledAccStat = "On";
        } else {
            oledAccStat = "Off";
        }

        Log.d(TAG, "OLED : LT STAT [" + oledLineTraceStat + "], ACC MODE [" + oledAccMode + "], ACC STAT [" + oledAccStat + "]");

        oledDisplay.setString(0, textLine1, "Line Trace :" + oledLineTraceStat);
        oledDisplay.setString(0, textLine2, "Accele Mode:"  + oledAccMode );
        oledDisplay.setString(0, textLine3, "Accele Stat:"  + oledAccStat);

        oledDisplay.draw();
    }

}
