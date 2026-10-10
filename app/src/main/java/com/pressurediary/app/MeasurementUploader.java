package com.pressurediary.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Silent, coalesced full-state sync. Durable pending state is the local database revision. */
final class MeasurementUploader {
    interface ConnectionResult { void complete(String memberName, String error); }
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "pressure-upload");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static boolean running, requested, forceRequested;

    static void onDataChanged(Context context) { requestSync(context, false); }

    static void requestSync(Context context, boolean force) {
        Context app = context.getApplicationContext();
        try {
            if (!new UploadSettings(app).enabled() || !connected(app)) return;
            synchronized (LOCK) {
                requested = true;
                forceRequested |= force;
                if (running) return;
                running = true;
                try { WORKER.execute(() -> drain(app)); }
                catch (RejectedExecutionException error) {
                    running = false;
                    throw error;
                }
            }
        } catch (Exception error) {
            Log.w("BP_UPLOAD", "sync.pending reason=" + error.getClass().getSimpleName());
        }
    }

    private static void drain(Context app) {
        boolean completed = false;
        try (MeasurementDatabase database = new MeasurementDatabase(app)) {
            while (true) {
                boolean force;
                synchronized (LOCK) {
                    force = forceRequested;
                    forceRequested = false;
                    requested = false;
                }
                UploadSettings settings = new UploadSettings(app);
                UploadSettings.Configuration configuration = settings.read();
                if (!configuration.enabled || !connected(app)) return;
                MeasurementSnapshot snapshot = database.snapshot();
                if (force || settings.acknowledged(configuration) < snapshot.version) {
                    new MeasurementUploadProtocol().sync(configuration.baseUrl, configuration.token,
                            configuration.installationId, snapshot, () -> {
                                try {
                                    if (!connected(app) || !settings.isCurrent(configuration)) {
                                        throw new java.io.IOException("Synchronization interrupted");
                                    }
                                } catch (java.security.GeneralSecurityException error) {
                                    throw new java.io.IOException("Configuration unavailable", error);
                                }
                            });
                    settings.acknowledge(configuration, snapshot.version);
                    Log.i("BP_UPLOAD", "sync.committed");
                }
                if (database.revision() != snapshot.version) continue;
                synchronized (LOCK) { if (!requested) { completed = true; return; } }
            }
        } catch (Exception error) {
            // Keep all data pending; logs contain no readings or credentials.
            Log.w("BP_UPLOAD", "sync.pending reason=" + error.getClass().getSimpleName());
        } finally {
            boolean retry;
            boolean force;
            synchronized (LOCK) {
                running = false;
                retry = completed && requested;
                force = forceRequested;
            }
            if (retry) requestSync(app, force);
        }
    }

    static void testConnection(Context context, String baseUrl, String token, ConnectionResult callback) {
        Context app = context.getApplicationContext();
        try {
            WORKER.execute(() -> {
                String member = null, message = null;
                try {
                    if (!connected(app)) throw new java.io.IOException("请连接互联网后再测试");
                    member = new MeasurementUploadProtocol().testConnection(baseUrl, token);
                } catch (javax.net.ssl.SSLException error) {
                    message = "HTTPS 证书验证失败，请检查服务器证书和手机日期";
                } catch (java.net.SocketTimeoutException error) {
                    message = "连接超时，请检查服务器地址和网络";
                } catch (java.net.UnknownHostException error) {
                    message = "找不到服务器，请检查域名解析和网络";
                } catch (java.io.IOException error) {
                    message = error.getMessage();
                } catch (Exception error) {
                    message = "无法连接，请检查地址和上传密钥";
                }
                String result = member, failure = message;
                MAIN.post(() -> callback.complete(result, failure));
            });
        } catch (RejectedExecutionException error) {
            MAIN.post(() -> callback.complete(null, "正在处理其他请求，请稍后再试"));
        }
    }

    private static boolean connected(Context app) {
        ConnectivityManager manager = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        Network network = manager.getActiveNetwork();
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}
