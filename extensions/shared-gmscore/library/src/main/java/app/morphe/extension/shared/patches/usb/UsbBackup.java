/*
 * USB drive backup for Google Photos.
 *
 * When a USB drive (or any removable volume) is plugged in, copies photos and videos from the
 * device's MediaStore onto the drive, keeping the folder layout (DCIM/Camera, Pictures/..., etc.).
 * Files that already exist on the drive with the same name and size are skipped.
 *
 * Access to the drive uses the Storage Access Framework: the user picks the drive once and the
 * permission is persisted, so later plug-ins need no prompt.
 */

package app.morphe.extension.shared.patches.usb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class UsbBackup {

    private static final String PREFS = "morphe_usb_backup";
    private static final String PREF_TREE_URI = "tree_uri";
    private static final String BACKUP_FOLDER = "Photos Backup";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean syncing = new AtomicBoolean(false);
    private static boolean receiverRegistered;
    /** Set when the user chose "Not now" so a single launch does not ask twice. */
    private static boolean askedThisProcess;

    private UsbBackup() {
    }

    // ---------------------------------------------------------------------------------------
    // Injection point.
    // ---------------------------------------------------------------------------------------

    /**
     * Injection point. Called at the start of Google Photos' HomeActivity.onCreate().
     */
    public static void onHomeActivityCreate(Activity activity) {
        try {
            Context app = activity.getApplicationContext();
            registerReceiver(app);
            // Run after the activity has finished starting.
            MAIN.postDelayed(() -> check(activity), 3000);
        } catch (Throwable t) {
            Logger.printException(() -> "onHomeActivityCreate failure", t);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Drive detection.
    // ---------------------------------------------------------------------------------------

    private static synchronized void registerReceiver(Context app) {
        if (receiverRegistered) return;
        receiverRegistered = true;

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Give the volume a moment to finish mounting.
                MAIN.postDelayed(() -> checkFromBackground(app), 4000);
            }
        };

        IntentFilter mounted = new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
        mounted.addDataScheme("file");
        IntentFilter usb = new IntentFilter("android.hardware.usb.action.USB_DEVICE_ATTACHED");

        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, mounted, Context.RECEIVER_EXPORTED);
                app.registerReceiver(receiver, usb, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, mounted);
                app.registerReceiver(receiver, usb);
            }
        } catch (Throwable t) {
            Logger.printException(() -> "registerReceiver failure", t);
        }
    }

    /** Called when a drive is plugged in while the app process is alive. */
    private static void checkFromBackground(Context app) {
        Uri tree = getSavedTreeUri(app);
        if (tree != null) {
            syncAsync(app);
        }
        // If no drive has been chosen yet, the next app launch will offer to set one up.
    }

    private static void check(Activity activity) {
        try {
            if (activity.isFinishing()) return;
            Context app = activity.getApplicationContext();

            Uri tree = getSavedTreeUri(app);
            if (tree != null) {
                // The drive has been set up before. Back up if it is plugged in right now.
                syncAsync(app);
                return;
            }

            if (askedThisProcess || findRemovableVolume(app) == null) return;
            askedThisProcess = true;

            new AlertDialog.Builder(activity)
                    .setTitle("USB drive detected")
                    .setMessage("Back up your photos and videos to this drive?\n\n"
                            + "On the next screen, open the drive and tap \"Use this folder\".")
                    .setPositiveButton("Choose drive", (d, w) ->
                            activity.startActivity(new Intent(activity, UsbBackupPickerActivity.class)))
                    .setNegativeButton("Not now", null)
                    .show();
        } catch (Throwable t) {
            Logger.printException(() -> "check failure", t);
        }
    }

    static StorageVolume findRemovableVolume(Context context) {
        if (Build.VERSION.SDK_INT < 24) return null;
        StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        if (sm == null) return null;
        for (StorageVolume volume : sm.getStorageVolumes()) {
            if (volume.isRemovable() && !volume.isPrimary()
                    && Environment.MEDIA_MOUNTED.equals(volume.getState())) {
                return volume;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // Saved drive.
    // ---------------------------------------------------------------------------------------

    static void saveTreeUri(Context context, Uri tree) {
        prefs(context).edit().putString(PREF_TREE_URI, tree.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** @return The chosen drive's tree URI if we still hold write permission for it. */
    private static Uri getSavedTreeUri(Context context) {
        String saved = prefs(context).getString(PREF_TREE_URI, null);
        if (saved == null) return null;
        Uri uri = Uri.parse(saved);
        for (UriPermission p : context.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(uri) && p.isWritePermission()) return uri;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // Sync.
    // ---------------------------------------------------------------------------------------

    static void syncAsync(Context context) {
        Context app = context.getApplicationContext();
        Uri tree = getSavedTreeUri(app);
        if (tree == null) return;
        if (!syncing.compareAndSet(false, true)) return;

        Thread thread = new Thread(() -> {
            try {
                sync(app, tree);
            } catch (Throwable t) {
                Logger.printException(() -> "USB backup failure", t);
            } finally {
                syncing.set(false);
            }
        }, "morphe-usb-backup");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static final class Item {
        final Uri source;
        final String name;
        final String mime;
        final long size;
        final Uri destFolder;

        Item(Uri source, String name, String mime, long size, Uri destFolder) {
            this.source = source;
            this.name = name;
            this.mime = mime;
            this.size = size;
            this.destFolder = destFolder;
        }
    }

    private static void sync(Context context, Uri tree) throws IOException {
        ContentResolver resolver = context.getContentResolver();

        Uri rootDoc = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree));

        // If the drive is not plugged in, this query fails or returns nothing: stay silent.
        if (!isReachable(resolver, rootDoc)) {
            Logger.printDebug(() -> "USB drive not present");
            return;
        }

        Uri backupRoot = getOrCreateFolder(resolver, rootDoc, BACKUP_FOLDER);
        if (backupRoot == null) {
            toast(context, "USB backup: could not write to the drive");
            return;
        }

        Map<String, Uri> folderCache = new HashMap<>();
        Map<Uri, Map<String, Long>> listingCache = new HashMap<>();
        List<Item> pending = new ArrayList<>();

        collect(context, resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                backupRoot, folderCache, listingCache, pending);
        collect(context, resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                backupRoot, folderCache, listingCache, pending);

        if (pending.isEmpty()) {
            Logger.printDebug(() -> "USB backup: nothing new");
            return;
        }

        toast(context, "USB backup: copying " + pending.size() + " new files. Keep the drive plugged in.");

        int copied = 0;
        int failed = 0;
        for (Item item : pending) {
            try {
                copyOne(resolver, item);
                copied++;
            } catch (Exception e) {
                failed++;
                Logger.printInfo(() -> "USB backup: failed to copy " + item.name, e);
                if (!isReachable(resolver, rootDoc)) {
                    toast(context, "USB backup stopped: drive removed (" + copied + " copied)");
                    return;
                }
            }
        }

        final int copiedFinal = copied;
        final int failedFinal = failed;
        toast(context, "USB backup done: " + copiedFinal + " copied"
                + (failedFinal > 0 ? ", " + failedFinal + " failed" : ""));
    }

    private static boolean isReachable(ContentResolver resolver, Uri docUri) {
        try (Cursor c = resolver.query(docUri,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            return c != null && c.moveToFirst();
        } catch (Exception e) {
            return false;
        }
    }

    private static void collect(Context context, ContentResolver resolver, Uri collection, Uri backupRoot,
                                Map<String, Uri> folderCache, Map<Uri, Map<String, Long>> listingCache,
                                List<Item> pending) {
        final boolean hasRelativePath = Build.VERSION.SDK_INT >= 29;
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                hasRelativePath ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.MediaColumns.DATA,
        };
        String selection = hasRelativePath ? MediaStore.MediaColumns.IS_PENDING + "=0" : null;

        try (Cursor c = resolver.query(collection, projection, selection, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String name = c.getString(1);
                long size = c.getLong(2);
                String mime = c.getString(3);
                String pathCol = c.getString(4);
                if (name == null || size <= 0) continue;

                String relative = hasRelativePath ? pathCol : relativeFromData(pathCol);
                if (relative == null) relative = "";

                Uri folder = getFolderFor(resolver, backupRoot, relative, folderCache);
                if (folder == null) continue;

                String destName = sanitize(name);
                Map<String, Long> existing = listingCache.get(folder);
                if (existing == null) {
                    existing = listChildren(resolver, folder);
                    listingCache.put(folder, existing);
                }
                Long existingSize = existing.get(destName);
                if (existingSize != null && existingSize == size) continue;

                Uri source = Uri.withAppendedPath(collection, String.valueOf(id));
                pending.add(new Item(source, destName, mime, size, folder));
            }
        } catch (Exception e) {
            Logger.printException(() -> "USB backup: scan failure", e);
        }
    }

    private static String relativeFromData(String data) {
        if (data == null) return null;
        int idx = data.lastIndexOf('/');
        if (idx <= 0) return null;
        String dir = data.substring(0, idx);
        String prefix = Environment.getExternalStorageDirectory().getAbsolutePath() + "/";
        if (dir.startsWith(prefix)) return dir.substring(prefix.length()) + "/";
        return null;
    }

    private static Uri getFolderFor(ContentResolver resolver, Uri backupRoot, String relativePath,
                                    Map<String, Uri> cache) {
        Uri current = backupRoot;
        StringBuilder key = new StringBuilder();
        for (String part : relativePath.split("/")) {
            if (part.isEmpty()) continue;
            key.append('/').append(part);
            Uri cached = cache.get(key.toString());
            if (cached != null) {
                current = cached;
                continue;
            }
            Uri next = getOrCreateFolder(resolver, current, sanitize(part));
            if (next == null) return null;
            cache.put(key.toString(), next);
            current = next;
        }
        return current;
    }

    private static Uri getOrCreateFolder(ContentResolver resolver, Uri parentDoc, String name) {
        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    parentDoc, DocumentsContract.getDocumentId(parentDoc));
            try (Cursor c = resolver.query(childrenUri, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    if (name.equals(c.getString(1))
                            && DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) {
                        return DocumentsContract.buildDocumentUriUsingTree(parentDoc, c.getString(0));
                    }
                }
            }
            return DocumentsContract.createDocument(
                    resolver, parentDoc, DocumentsContract.Document.MIME_TYPE_DIR, name);
        } catch (Exception e) {
            Logger.printInfo(() -> "USB backup: folder failure " + name, e);
            return null;
        }
    }

    private static Map<String, Long> listChildren(ContentResolver resolver, Uri folderDoc) {
        Map<String, Long> result = new HashMap<>();
        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    folderDoc, DocumentsContract.getDocumentId(folderDoc));
            try (Cursor c = resolver.query(childrenUri, new String[]{
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    result.put(c.getString(0), c.isNull(1) ? -1L : c.getLong(1));
                }
            }
        } catch (Exception e) {
            Logger.printInfo(() -> "USB backup: list failure", e);
        }
        return result;
    }

    private static void copyOne(ContentResolver resolver, Item item) throws IOException {
        Uri dest = null;
        boolean done = false;
        try {
            // A leftover partial file with the same name would make the provider rename the new one.
            deleteIfExists(resolver, item.destFolder, item.name);

            dest = DocumentsContract.createDocument(resolver, item.destFolder,
                    item.mime != null ? item.mime : "application/octet-stream", item.name);
            if (dest == null) throw new IOException("createDocument returned null");

            try (InputStream in = resolver.openInputStream(item.source);
                 OutputStream out = resolver.openOutputStream(dest)) {
                if (in == null || out == null) throw new IOException("stream unavailable");
                byte[] buffer = new byte[256 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }
            done = true;
        } finally {
            if (!done && dest != null) {
                try {
                    DocumentsContract.deleteDocument(resolver, dest);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void deleteIfExists(ContentResolver resolver, Uri folderDoc, String name) {
        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    folderDoc, DocumentsContract.getDocumentId(folderDoc));
            try (Cursor c = resolver.query(childrenUri, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    if (name.equals(c.getString(1))) {
                        DocumentsContract.deleteDocument(resolver,
                                DocumentsContract.buildDocumentUriUsingTree(folderDoc, c.getString(0)));
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** FAT/exFAT drives reject these characters in names. */
    private static String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static void toast(Context context, String message) {
        MAIN.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }
}
