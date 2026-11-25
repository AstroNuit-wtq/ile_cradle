package com.diplomka.app;

import android.Manifest;
import android.app.NativeActivity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

/**
 * Thin Java shell around NativeActivity.
 * Requests CAMERA (and location) at runtime — pure native cannot show the system dialog.
 */
public class MainActivity extends NativeActivity {
    private static final String TAG = "DiplomkaNative";
    private static final int REQ_PERMS = 1001;

    private static final String[] PERMS = new String[] {
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Keep screen on while flying / navigating.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (!hasPermission(Manifest.permission.CAMERA)) {
            Log.i(TAG, "Requesting runtime permissions...");
            requestPermissions(PERMS, REQ_PERMS);
        } else {
            Log.i(TAG, "CAMERA already granted");
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If user granted permission from Settings and came back, log it.
        if (hasPermission(Manifest.permission.CAMERA)) {
            Log.i(TAG, "onResume: CAMERA granted");
        } else {
            Log.w(TAG, "onResume: CAMERA still denied");
        }
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;
        for (int i = 0; i < permissions.length; i++) {
            boolean granted = i < grantResults.length && grantResults[i] == PackageManager.PERMISSION_GRANTED;
            Log.i(TAG, "Permission " + permissions[i] + " -> " + (granted ? "GRANTED" : "DENIED"));
        }
    }
}
