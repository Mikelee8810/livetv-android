package com.livetv.app.tvinput;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

public class LiveTvSetupActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String inputId = getIntent().getStringExtra("android.media.tv.extra.INPUT_ID");
        if (inputId == null) inputId = "com.livetv.app/.tvinput.LiveTvInputService";
        TextView tv = new TextView(this);
        tv.setText("Syncing channels to the Google TV Live tab...\nPlease wait.");
        tv.setTextSize(20.0f);
        tv.setPadding(50, 50, 50, 50);
        setContentView(tv);
        ChannelSyncHelper.syncChannels(this, inputId, new ChannelSyncHelper.SyncCallback() {
            @Override public void onComplete(final int count) {
                runOnUiThread(() -> {
                    Toast.makeText(LiveTvSetupActivity.this, "Synced " + count + " channels to Google TV", Toast.LENGTH_LONG).show();
                    setResult(RESULT_OK);
                    finish();
                });
            }
            @Override public void onError(final Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(LiveTvSetupActivity.this, "Sync failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    setResult(RESULT_CANCELED);
                    finish();
                });
            }
        });
    }
}
