package com.shieldrj.civic5mt;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Must be registered before super.onCreate, which is where the bridge is built.
        registerPlugin(ObdSerialPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
