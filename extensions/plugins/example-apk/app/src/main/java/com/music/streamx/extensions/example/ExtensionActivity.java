package com.music.streamx.extensions.example;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class ExtensionActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        text.setPadding(padding, padding, padding, padding);
        text.setTextSize(20);
        text.setText("Ocean Extension\n\nOpen StreamX → Settings → Skins & Plugins to apply Ocean.\n\nThe source provider is a developer scaffold with no catalogue or audio. Add your service integration and its own sign-in here.");
        setContentView(text);
    }
}
