package com.fenglei.smartpetcollar;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * MainActivity - hosts two top-level Fragments via a BottomNavigationView:
 * Location and Image. The Image tab is an ImageContainerFragment that
 * further splits into Cropped / Pet Raw / Contact Raw sub-tabs, matching
 * the SmartPetCollar reference Android app's three BLE image flows.
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_location) {
                showFragment(new LocationFragment());
                return true;
            } else if (id == R.id.nav_ble_image) {
                showFragment(new ImageContainerFragment());
                return true;
            }
            return false;
        });

        if (savedInstanceState == null) {
            bottomNav.setSelectedItemId(R.id.nav_location);
        }
    }

    private void showFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit();
    }
}
