package com.fenglei.smartpetcollar;

import android.app.Application;

import com.amap.api.maps.MapsInitializer;

import dagger.hilt.android.HiltAndroidApp;

@HiltAndroidApp
public class SmartPetCollarApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // AMap privacy compliance (required since 2021) - must be called before any
        // MapView/MapsInitializer/CoordinateConverter is used, otherwise the SDK refuses to render.
        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);
    }
}
