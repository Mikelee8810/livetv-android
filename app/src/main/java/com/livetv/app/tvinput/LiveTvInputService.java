package com.livetv.app.tvinput;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.media.tv.TvInputService;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import androidx.media3.exoplayer.ExoPlayer;
import com.livetv.app.OnDeviceStreamServer;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes5.dex */
public class LiveTvInputService extends TvInputService {
    private static final String TAG = "LiveTvInputService";

    @Override // android.app.Service
    public void onCreate() {
        super.onCreate();
        try {
            OnDeviceStreamServer.ensureStarted();
        } catch (Exception e) {
            Log.e(TAG, "On-device stream resolver unavailable", e);
        }
    }

    @Override // android.media.tv.TvInputService
    public TvInputService.Session onCreateSession(String inputId) {
        return new LiveTvSession(this, inputId);
    }

    public static class LiveTvSession extends TvInputService.Session {
        private final Context mContext;
        private Uri mCurrentChannelUri;
        private final Handler mHandler;
        private final String mInputId;
        private MediaPlayer mMediaPlayer;
        private int mRetryCount;
        private String mStreamUrl;
        private Surface mSurface;

        public LiveTvSession(Context context, String inputId) {
            super(context);
            this.mHandler = new Handler(Looper.getMainLooper());
            this.mRetryCount = 0;
            this.mContext = context;
            this.mInputId = inputId;
        }

        @Override // android.media.tv.TvInputService.Session
        public void onRelease() {
            releasePlayer();
        }

        @Override // android.media.tv.TvInputService.Session
        public boolean onSetSurface(Surface surface) {
            this.mSurface = surface;
            if (this.mMediaPlayer != null) {
                this.mMediaPlayer.setSurface(surface);
                return true;
            }
            return true;
        }

        @Override // android.media.tv.TvInputService.Session
        public void onSetStreamVolume(float volume) {
            if (this.mMediaPlayer != null) {
                this.mMediaPlayer.setVolume(volume, volume);
            }
        }

        @Override // android.media.tv.TvInputService.Session
        public boolean onTune(Uri channelUri) {
            this.mCurrentChannelUri = channelUri;
            this.mRetryCount = 0;
            notifyVideoUnavailable(3);
            this.mStreamUrl = resolveStreamUrl(channelUri);
            if (this.mStreamUrl == null || this.mStreamUrl.isEmpty()) {
                notifyVideoUnavailable(0);
                return false;
            }
            playStream(this.mStreamUrl);
            return true;
        }

        private String resolveStreamUrl(Uri channelUri) {
            int dataCol;
            String streamUrl = null;
            String[] projection = {"internal_provider_data", "display_name"};
            try {
                Cursor cursor = this.mContext.getContentResolver().query(channelUri, projection, null, null, null);
                if (cursor != null) {
                    try {
                        if (cursor.moveToFirst() && (dataCol = cursor.getColumnIndex("internal_provider_data")) != -1 && (streamUrl = cursor.getString(dataCol)) != null && streamUrl.trim().startsWith("{")) {
                            streamUrl = new JSONObject(streamUrl).optString("streamUrl", "");
                        }
                    } catch (Throwable th) {
                        if (cursor != null) {
                            try {
                                cursor.close();
                            } catch (Throwable th2) {
                                th.addSuppressed(th2);
                            }
                        }
                        throw th;
                    }
                }
                if (cursor != null) {
                    cursor.close();
                }
            } catch (Exception e) {
                Log.e(LiveTvInputService.TAG, "Error resolving stream URL from TV contract", e);
            }
            return streamUrl;
        }

        private void playStream(String url) {
            releasePlayer();
            try {
                this.mMediaPlayer = new MediaPlayer();
                if (this.mSurface != null) {
                    this.mMediaPlayer.setSurface(this.mSurface);
                }
                if (url.startsWith("/")) {
                    url = "http://127.0.0.1:48913" + url;
                }
                this.mMediaPlayer.setDataSource(this.mContext, Uri.parse(url));
                this.mMediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() { // from class: com.livetv.app.tvinput.LiveTvInputService$LiveTvSession$$ExternalSyntheticLambda0
                    @Override // android.media.MediaPlayer.OnPreparedListener
                    public final void onPrepared(MediaPlayer mediaPlayer) {
                        lambda$playStream$0(mediaPlayer);
                    }
                });
                this.mMediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() { // from class: com.livetv.app.tvinput.LiveTvInputService$LiveTvSession$$ExternalSyntheticLambda1
                    @Override // android.media.MediaPlayer.OnErrorListener
                    public final boolean onError(MediaPlayer mediaPlayer, int i, int i2) {
                        return lambda$playStream$1(mediaPlayer, i, i2);
                    }
                });
                this.mMediaPlayer.prepareAsync();
            } catch (Exception e) {
                Log.e(LiveTvInputService.TAG, "Failed to start media player for " + url, e);
                handleAutoReconnect();
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$playStream$0(MediaPlayer mp) {
            mp.start();
            notifyVideoAvailable();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ boolean lambda$playStream$1(MediaPlayer mp, int what, int extra) {
            Log.w(LiveTvInputService.TAG, "Playback error: what=" + what + ", extra=" + extra);
            notifyVideoUnavailable(3);
            handleAutoReconnect();
            return true;
        }

        private void handleAutoReconnect() {
            if (this.mRetryCount < 5 && this.mStreamUrl != null) {
                this.mRetryCount++;
                Log.i(LiveTvInputService.TAG, "Auto-reconnecting stream (attempt #" + this.mRetryCount + ")...");
                this.mHandler.postDelayed(new Runnable() { // from class: com.livetv.app.tvinput.LiveTvInputService$LiveTvSession$$ExternalSyntheticLambda2
                    @Override // java.lang.Runnable
                    public final void run() {
                        lambda$handleAutoReconnect$2();
                    }
                }, ExoPlayer.DEFAULT_DETACH_SURFACE_TIMEOUT_MS);
                return;
            }
            notifyVideoUnavailable(0);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$handleAutoReconnect$2() {
            if (this.mStreamUrl != null) {
                playStream(this.mStreamUrl);
            }
        }

        @Override // android.media.tv.TvInputService.Session
        public void onSetCaptionEnabled(boolean enabled) {
        }

        private void releasePlayer() {
            if (this.mMediaPlayer != null) {
                try {
                    this.mMediaPlayer.stop();
                    this.mMediaPlayer.reset();
                    this.mMediaPlayer.release();
                } catch (Exception e) {
                }
                this.mMediaPlayer = null;
            }
        }
    }
}
