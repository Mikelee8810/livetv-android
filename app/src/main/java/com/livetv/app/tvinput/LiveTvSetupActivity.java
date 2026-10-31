package com.livetv.app.tvinput;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

public class LiveTvSetupActivity extends Activity {
    // Re-entry guard (audit U2): a configuration change re-ran
    // onCreate and started a second destructive sync.
    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!RUNNING.compareAndSet(false, true)) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        String inputId = getIntent().getStringExtra("android.media.tv.extra.INPUT_ID");
        if (inputId == null) inputId = "com.livetv.app/.tvinput.LiveTvInputService";
        TextView tv = new TextView(this);
        tv.setText("Syncing channels to the Google TV Live tab...\nPlease wait.");
        tv.setTextSize(20.0f);
        tv.setPadding(50, 50, 50, 50);
        setContentView(tv);
        // Pass the app's EPG snapshot when one exists (audit H4):
        // syncing with a null EPG used to wipe the guide's programs.
        com.livetv.app.epg.EpgSnapshot epg = null;
        try {
            epg = com.livetv.app.data.Repo.INSTANCE.getEpg();
        } catch (Throwable ignored) {
        }
        ChannelSyncHelper.syncChannels(this, inputId, epg, new ChannelSyncHelper.SyncCallback() {
            @Override public void onComplete(final int count) {
                RUNNING.set(false);
                runOnUiThread(() -> {
                    Toast.makeText(LiveTvSetupActivity.this, "Synced " + count + " channels to Google TV", Toast.LENGTH_LONG).show();
                    setResult(RESULT_OK);
                    finish();
                });
            }
            @Override public void onError(final Exception e) {
                RUNNING.set(false);
                runOnUiThread(() -> {
                    // Never "Sync failed: null" (audit U3).
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    Toast.makeText(LiveTvSetupActivity.this, "Sync failed: " + msg, Toast.LENGTH_LONG).show();
                    setResult(RESULT_CANCELED);
                    finish();
                });
            }
        });
    }
}
