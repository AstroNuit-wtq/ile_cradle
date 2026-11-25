package com.diplomka.app;

import android.Manifest;
import android.app.NativeActivity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Thin Java shell around NativeActivity.
 * Requests CAMERA (and location) at runtime — pure native hasCode=false cannot do this.
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
        // Request permissions BEFORE native code starts the camera.
        if (!hasAllPermissions()) {
            requestPermissions(PERMS, REQ_PERMS);
        } else {
            Log.i(TAG, "All permissions already granted");
        }
        super.onCreate(savedInstanceState);
    }

    private boolean hasAllPermissions() {
        for (String p : PERMS) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;
        boolean cameraOk = false;
        for (int i = 0; i < permissions.length; i++) {
            boolean granted = i < grantResults.length && grantResults[i] == PackageManager.PERMISSION_GRANTED;
            Log.i(TAG, "Permission " + permissions[i] + " -> " + (granted ? "GRANTED" : "DENIED"));
            if (Manifest.permission.CAMERA.equals(permissions[i]) && granted) {
                cameraOk = true;
            }
        }
        if (!cameraOk) {
            Log.e(TAG, "CAMERA permission denied — preview will stay blank");
        }
    }
}
