/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.actions;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import app.hushpinterest.extension.pinterest.settings.FamilyNames;
import app.hushpinterest.extension.pinterest.settings.Settings;
import app.hushpinterest.extension.shared.L10n;
import app.hushpinterest.extension.shared.Utils;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;

/**
 * Saves the pins Android's Downloads holds back (#27, #29).
 *
 * <p>DownloadManager runs every request as a job it schedules for the app that asked. A phone that
 * restricts Pinterest in the background (a restricted standby bucket, a maker's battery saver) let
 * those jobs wait, so a pin sat at Queued for an hour or more. When none of this app's requests is
 * running a few seconds after the last one was queued, and the network is one the download settings
 * allow, HushPinterest takes the waiting ones back: it makes a MediaStore entry in the same folder
 * with the same name, removes Android's request, and saves the file itself with one GET. Android's
 * request is removed only once the entry exists, so a pin is never left with neither.
 */
public final class InAppDownloads {
    private InAppDownloads() {}

    /** How long after the last request is queued Android gets to start it. */
    static final long GRACE_MS = 5000;
    /** How often the waiting requests are looked at again while Android or the network holds them. */
    static final long RECHECK_MS = 30000;
    /** The largest image saved, and the time its GET may take in all. */
    static final long IMAGE_BYTES = 128L << 20;
    static final long IMAGE_MS = 120_000;
    /** The same for a video. */
    static final long VIDEO_BYTES = 1L << 30;
    static final long VIDEO_MS = 30 * 60_000;

    static volatile PinTransfer.Connection connection = PinTransfer.NETWORK;
    static volatile LongSupplier clock = PinTransfer.CLOCK;

    /** History IDs a save in this process is writing right now. */
    private static final Set<Long> SAVING = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean checking = new AtomicBoolean();
    private static final AtomicBoolean again = new AtomicBoolean();
    private static volatile Context watched;
    private static Handler handler;
    private static final Runnable CHECK = () -> {
        Context app = watched;
        if (app != null) check(app);
    };

    /** Whether a save in this process is writing [id]. One recorded as running that isn't stopped part way. */
    static boolean saving(long id) {
        return SAVING.contains(id);
    }

    /** Looks at this app's requests {@link #GRACE_MS} from now, or from the latest call. */
    static void watch(Context context) {
        later(context, GRACE_MS);
    }

    private static void later(Context context, long delayMs) {
        if (context == null || !Utils.isMainProcess()) return;
        Context app = context.getApplicationContext();
        watched = app == null ? context : app;
        Handler main = main();
        main.removeCallbacks(CHECK);
        main.postDelayed(CHECK, delayMs);
    }

    private static synchronized Handler main() {
        Looper looper = Looper.getMainLooper();
        if (handler == null || handler.getLooper() != looper) handler = new Handler(looper);
        return handler;
    }

    /** One pass at a time. A call during a pass runs another once it ends. */
    private static void check(Context app) {
        if (!checking.compareAndSet(false, true)) {
            again.set(true);
            return;
        }
        boolean queued = Utils.runOnBackgroundThread(() -> {
            try {
                pass(app);
            } catch (RuntimeException failure) {
                HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "save held downloads", failure);
            } finally {
                checking.set(false);
                if (again.getAndSet(false)) check(app);
            }
        });
        if (!queued) checking.set(false);
    }

    private static void pass(Context app) {
        if (!PinDownloads.active()) return;
        DownloadLedger.Queue queue = DownloadLedger.queue(app);
        // A save the app stopped part way leaves a hidden, unfinished entry. It goes, and the pin reads failed.
        for (DownloadLedger.Saved left : queue.abandoned) {
            if (left.address != null) delete(app.getContentResolver(), left.address);
            DownloadLedger.failed(app, left.id, left.mime);
        }
        if (queue.stalled.isEmpty()) return;
        // Android is working through them, or waits for a network the settings allow. It's asked again later.
        if (queue.running || !online(app)) {
            later(app, RECHECK_MS);
            return;
        }
        int saved = 0, failed = 0;
        String folder = null;
        for (DownloadLedger.Stalled one : queue.stalled) {
            if (!PinDownloads.active()) break;
            Boolean result = takeOver(app, one);
            if (result == null) continue;
            if (result) {
                if (saved++ == 0) folder = one.folder;
            } else {
                failed++;
            }
        }
        if (failed > 0) {
            HookStatus.counted(FamilyNames.DOWNLOAD_PINS, "held download not saved");
            Utils.showToastLong(L10n.t("Couldn't save this pin."));
        } else if (saved > 0) {
            Utils.showToastLong(L10n.f("Saved in %s.", folder != null ? folder : Environment.DIRECTORY_DOWNLOADS));
        }
    }

    /** Whether the active network is one the download settings let a download use. */
    static boolean online(Context app) {
        try {
            ConnectivityManager connectivity = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = connectivity == null ? null : connectivity.getActiveNetwork();
            NetworkCapabilities abilities = network == null ? null : connectivity.getNetworkCapabilities(network);
            if (abilities == null || !abilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false;
            if (Settings.DOWNLOAD_UNMETERED_ONLY.get()
                    && !abilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return false;
            return !Settings.DOWNLOAD_NO_ROAMING.get()
                    || abilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
        } catch (RuntimeException refused) {
            HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "read the network for a held download", refused);
            return false;
        }
    }

    /**
     * Saves [one] in place of Android. TRUE once it's saved, FALSE when it was taken over but not
     * saved, null when Android keeps it: no entry could be made, or the request or its history is gone.
     */
    @Nullable
    private static Boolean takeOver(Context app, DownloadLedger.Stalled one) {
        Uri collection = collection(one.folder, one.source.mime);
        if (collection == null) return null;
        ContentResolver resolver = app.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, one.name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, one.source.mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, (one.folder != null ? one.folder : Environment.DIRECTORY_DOWNLOADS) + "/");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri entry;
        try {
            entry = resolver.insert(collection, values);
        } catch (RuntimeException refused) {
            HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "make an entry for a held download", refused);
            return null;
        }
        if (entry == null) return null;
        SAVING.add(one.id);
        try {
            if (!DownloadLedger.saving(app, one.id, one.source.mime, entry)) {
                delete(resolver, entry);
                return null;
            }
            if (!remove(app, one.id)) {
                DownloadLedger.forget(app, one.id);
                delete(resolver, entry);
                return null;
            }
            HookStatus.counted(FamilyNames.DOWNLOAD_PINS, "held download taken over");
            if (write(resolver, one.source, entry)) {
                DownloadLedger.saved(app, one.id, one.source.mime, entry);
                HookStatus.counted(FamilyNames.DOWNLOAD_PINS, "held download saved");
                return Boolean.TRUE;
            }
            delete(resolver, entry);
            DownloadLedger.failed(app, one.id, one.source.mime);
            return Boolean.FALSE;
        } catch (RuntimeException failure) {
            HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "save a held download", failure);
            delete(resolver, entry);
            try {
                DownloadLedger.failed(app, one.id, one.source.mime);
            } catch (RuntimeException unrecorded) {
                HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "record a held download that failed", unrecorded);
            }
            return Boolean.FALSE;
        } finally {
            SAVING.remove(one.id);
        }
    }

    /** Removes Android's request. False when Android no longer had it, so there's nothing to take over. */
    private static boolean remove(Context app, long id) {
        DownloadManager manager = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) return false;
        try {
            return manager.remove(id) > 0;
        } catch (RuntimeException refused) {
            HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "remove a held download", refused);
            return false;
        }
    }

    /** Writes the file at [source] into [entry] and shows it. False when the media host didn't give it. */
    private static boolean write(ContentResolver resolver, PinMedia.Source source, Uri entry) {
        boolean video = source.mime.startsWith("video/");
        try {
            PinTransfer.Fetched got = PinTransfer.fetch(source, new PinTransfer.Sink() {
                @Override public OutputStream open() throws IOException {
                    ParcelFileDescriptor file = resolver.openFileDescriptor(entry, "w");
                    if (file == null) throw new IOException("No file for the saved entry");
                    return new ParcelFileDescriptor.AutoCloseOutputStream(file);
                }

                // The caller deletes the whole entry.
                @Override public void discard() {}
            }, video ? VIDEO_BYTES : IMAGE_BYTES, type -> sameType(type, source.mime), connection, clock,
                    video ? VIDEO_MS : IMAGE_MS);
            if (got.status != 200 || got.written == 0) return false;
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            return resolver.update(entry, done, null, null) > 0;
        } catch (IOException failed) {
            HookStatus.counted(FamilyNames.DOWNLOAD_PINS, "held download fetch failed");
            return false;
        }
    }

    /** Whether a Content-Type header names [mime], reading image/jpg as JPEG. */
    static boolean sameType(@Nullable String header, String mime) {
        if (header == null) return false;
        String type = header.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (type.equals("image/jpg")) type = "image/jpeg";
        return type.equals(mime);
    }

    /**
     * The MediaStore collection that takes a file of [mime] in [folder] (null for Download), or null
     * when this Android has none. Pictures and DCIM take images and, from Android 11, videos;
     * Documents takes any file from Android 11.
     */
    @Nullable
    static Uri collection(@Nullable String folder, String mime) {
        String top = DownloadFolder.directory(folder);
        String volume = MediaStore.VOLUME_EXTERNAL_PRIMARY;
        boolean video = mime.startsWith("video/");
        if (top.equals(Environment.DIRECTORY_DOWNLOADS)) return MediaStore.Downloads.getContentUri(volume);
        if (top.equals(Environment.DIRECTORY_DCIM)) {
            return video ? MediaStore.Video.Media.getContentUri(volume) : MediaStore.Images.Media.getContentUri(volume);
        }
        if (top.equals(Environment.DIRECTORY_PICTURES)) {
            if (!video) return MediaStore.Images.Media.getContentUri(volume);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? MediaStore.Video.Media.getContentUri(volume) : null;
        }
        if (top.equals(Environment.DIRECTORY_DOCUMENTS)) {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? MediaStore.Files.getContentUri(volume) : null;
        }
        return null;
    }

    private static void delete(ContentResolver resolver, Uri entry) {
        try {
            resolver.delete(entry, null, null);
        } catch (RuntimeException refused) {
            HookStatus.threw(FamilyNames.DOWNLOAD_PINS, "delete an unfinished saved entry", refused);
        }
    }

    /** Puts the network and the clock back and forgets every pass. */
    static void forgetForTests() {
        connection = PinTransfer.NETWORK;
        clock = PinTransfer.CLOCK;
        SAVING.clear();
        checking.set(false);
        again.set(false);
        main().removeCallbacks(CHECK);
        watched = null;
    }
}
