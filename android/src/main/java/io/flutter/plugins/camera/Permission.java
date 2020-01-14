package io.flutter.plugins.camera;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;

import io.flutter.plugin.common.PluginRegistry;

public class Permission {
    private PluginRegistry.Registrar registrar;
    private static String NO_ACTIVITY = "No activity available!";
    private Activity activity;

    public Permission(PluginRegistry.Registrar registrar) {
        this.registrar = registrar;
        this.activity = registrar.activity();
    }

    private void checkActivity() {
        if (this.activity == null) {
            throw new IllegalStateException(NO_ACTIVITY);
        }
    }

    private boolean checkPermission(String permissionType) {
        checkActivity();

        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || activity.checkSelfPermission(permissionType)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean hasCamera() {
        return checkPermission(Manifest.permission.CAMERA);
    }

    public boolean hasAudio() {
        return checkPermission(Manifest.permission.RECORD_AUDIO);
    }
}
