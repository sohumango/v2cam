package com.urobot.camtoo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;

/***
 * カメラとストレージのパーミッションを収集するための補助クラス
 */
@SuppressLint("Registered")
public class GetPermission extends AppCompatActivity {

    /*** 内部リクエストで使用するリクエストコード */
    //protected final int REQUEST_CODE = 838861;
    protected final int REQUEST_CODE = 8;

    /***
     * アプリケーションがすでにパーミッションを取得しているかどうかを調べる
     * @return 取得している場合はtrue
     */
    protected boolean hasPermission() {
        int permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        if ( permissionCheck != PackageManager.PERMISSION_GRANTED ) {
            return false;
        }
        permissionCheck =  ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if ( permissionCheck != PackageManager.PERMISSION_GRANTED ) {
            return false;
        }
        return true;
    }

    /***
     * パーミッションを取得する
     * @return 必ずtrue
     */
    protected boolean getPermission() {
        if ( hasPermission() ) {
            afterGetPermission();
            return true;
        }
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CODE);
        return true;
    }

    /***
     * パーミッション取得後の処理を制御する
     * @param requestCode 継承元クラスに既定
     * @param permissions 継承元クラスに既定
     * @param grantResults 継承元クラスに既定
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_CODE) {
            return;
        }
        if (grantResults.length == 0 ) {
            return;
        }
        if ( grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        this.afterGetPermission();
    }

    /***
     * パーミッション取得後に呼び出される。<br>
     *     必要であれば継承先でオーバーライドして使用する。
     */
    public void afterGetPermission()
    {
    }

    private static final int REQUEST_CAMERA_PERMISSION = 1;

    protected void requestCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            //new ConfirmationDialog().show(getChildFragmentManager(), FRAGMENT_DIALOG);
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }
}
