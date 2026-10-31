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
        private Runnable mStallWatchdog;
        // Tune resolution runs off the main thread (audit V3): the
        // ContentResolver query and JSON parse used to run inline in
        // onTune, janking (and risking an ANR) on every zap.
        private final android.os.HandlerThread mResolveThread = new android.os.HandlerThread("tif-resolve");
        private android.os.Handler mResolveHandler;
        private int mRetryCount;
        private String mStreamUrl;
        private String mReconnectUrl;
        private Runnable mPrepareWatchdog;
        private Surface mSurface;

        public LiveTvSession(Context context, String inputId) {
            super(context);
            this.mHandler = new Handler(Looper.getMainLooper());
            this.mRetryCount = 0;
            this.mContext = context;
            this.mInputId = inputId;
            this.mResolveThread.start();
            this.mResolveHandler = new Handler(this.mResolveThread.getLooper());
        }

        @Override // android.media.tv.TvInputService.Session
        public void onRelease() {
            // Kill pending reconnects/watchdogs first: a delayed runnable
            // firing after release used to create a MediaPlayer on a
            // dead session (zombie playback, native leak).
            this.mHandler.removeCallbacksAndMessages(null);
            this.mResolveThread.quitSafely();
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
        public boolean onTune(final Uri channelUri) {
            this.mCurrentChannelUri = channelUri;
            this.mRetryCount = 0;
            notifyVideoUnavailable(3);
            // Resolve on the worker thread, play on the main thread,
            // and only if this tune is still the current one (V3).
            this.mResolveHandler.post(new Runnable() {
                @Override public void run() {
                    final String url = resolveStreamUrl(channelUri);
                    mHandler.post(new Runnable() {
                        @Override public void run() {
                            if (!channelUri.equals(mCurrentChannelUri)) {
                                return;
                            }
                            mStreamUrl = url;
                            if (url == null || url.isEmpty()) {
                                notifyVideoUnavailable(0);
                                return;
                            }
                            playStream(url);
                        }
                    });
                }
            });
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
            // A new stream supersedes every pending callback from the
            // previous one (reconnects and prepare watchdogs alike).
            this.mHandler.removeCallbacksAndMessages(null);
            this.mPrepareWatchdog = null;
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
                // EOF and stalls were invisible before (audit V2):
                // the proxy closing the stream left a frozen frame
                // forever because the prepare watchdog is cancelled
                // at onPrepared and nothing else watched the stream.
                this.mMediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override public void onCompletion(MediaPlayer mp) {
                        Log.w(LiveTvInputService.TAG, "Stream ended (EOF); reconnecting");
                        notifyVideoUnavailable(3);
                        handleAutoReconnect();
                    }
                });
                this.mMediaPlayer.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                    @Override public boolean onInfo(MediaPlayer mp, int what, int extra) {
                        if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                            notifyVideoUnavailable(3);
                            armStallWatchdog(mp);
                        } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                            disarmStallWatchdog();
                            notifyVideoAvailable();
                        }
                        return false;
                    }
                });
                this.mMediaPlayer.prepareAsync();
                // Prepare watchdog: prepareAsync against a stalled
                // localhost proxy can hang forever - neither onPrepared
                // nor onError ever fires, and without this the session
                // sits at VIDEO_UNAVAILABLE_BUFFERING indefinitely.
                final MediaPlayer watched = this.mMediaPlayer;
                this.mPrepareWatchdog = new Runnable() {
                    @Override public void run() {
                        if (mMediaPlayer == watched) {
                            boolean playing = false;
                            try { playing = watched.isPlaying(); } catch (Exception ignored) { }
                            if (!playing) {
                                Log.w(LiveTvInputService.TAG, "Prepare stalled for 20s; treating as a playback failure");
                                notifyVideoUnavailable(3);
                                handleAutoReconnect();
                            }
                        }
                    }
                };
                this.mHandler.postDelayed(this.mPrepareWatchdog, 20000L);
            } catch (Exception e) {
                Log.e(LiveTvInputService.TAG, "Failed to start media player for " + url, e);
                handleAutoReconnect();
            }
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$playStream$0(MediaPlayer mp) {
            if (this.mPrepareWatchdog != null) {
                this.mHandler.removeCallbacks(this.mPrepareWatchdog);
                this.mPrepareWatchdog = null;
            }
            // A successful prepare earns the reconnect budget back:
            // without this, five transient errors across hours of
            // healthy viewing permanently disabled auto-reconnect.
            this.mRetryCount = 0;
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

        private void armStallWatchdog(final MediaPlayer watched) {
            disarmStallWatchdog();
            this.mStallWatchdog = new Runnable() {
                @Override public void run() {
                    if (mMediaPlayer == watched) {
                        boolean playing = false;
                        try { playing = watched.isPlaying(); } catch (Exception ignored) { }
                        if (!playing) {
                            Log.w(LiveTvInputService.TAG, "Stream stalled for 15s; reconnecting");
                            notifyVideoUnavailable(3);
                            handleAutoReconnect();
                        }
                    }
                }
            };
            this.mHandler.postDelayed(this.mStallWatchdog, 15000L);
        }

        private void disarmStallWatchdog() {
            if (this.mStallWatchdog != null) {
                this.mHandler.removeCallbacks(this.mStallWatchdog);
                this.mStallWatchdog = null;
            }
        }

        private void handleAutoReconnect() {
            // The autoRetry setting governs this service too (audit
            // V5): it used to be honoured only by the ExoPlayer side.
            boolean autoRetry = true;
            try {
                autoRetry = new com.livetv.app.data.Prefs(this.mContext).getAutoRetry();
            } catch (Exception ignored) {
            }
            if (autoRetry && this.mRetryCount < 5 && this.mStreamUrl != null) {
                this.mRetryCount++;
                // Capture the URL now: the runnable used to read the
                // mutable mStreamUrl when it fired, so a stale reconnect
                // could restart a NEW channel's player mid-prepare.
                this.mReconnectUrl = this.mStreamUrl;
                Log.i(LiveTvInputService.TAG, "Auto-reconnecting stream (attempt #" + this.mRetryCount + ")...");
                // Exponential backoff (audit V5): a flat 2 s retry
                // burned all five attempts in ten seconds.
                long backoffMs = Math.min(30000L, 2000L << Math.min(this.mRetryCount, 4));
                this.mHandler.postDelayed(new Runnable() { // from class: com.livetv.app.tvinput.LiveTvInputService$LiveTvSession$$ExternalSyntheticLambda2
                    @Override // java.lang.Runnable
                    public final void run() {
                        lambda$handleAutoReconnect$2();
                    }
                }, backoffMs);
                return;
            }
            notifyVideoUnavailable(0);
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$handleAutoReconnect$2() {
            if (this.mReconnectUrl != null) {
                playStream(this.mReconnectUrl);
            }
        }

        @Override // android.media.tv.TvInputService.Session
        public void onSetCaptionEnabled(boolean enabled) {
        }

        private void releasePlayer() {
            if (this.mMediaPlayer != null) {
                // release() directly (audit V1): stop() first threw
                // IllegalStateException in the Preparing and Error
                // states, the empty catch swallowed it, and release()
                // never ran - leaking the player and its hardware
                // decoder on every zap-during-prepare and every
                // error-triggered reconnect, until the decoders ran
                // out.
                try {
                    this.mMediaPlayer.release();
                } catch (Exception e) {
                    Log.w(LiveTvInputService.TAG, "MediaPlayer release failed", e);
                }
                this.mMediaPlayer = null;
            }
        }
    }
}
