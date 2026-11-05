package app.morphe.extension.shared.patches.usb;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.storage.StorageVolume;
import android.widget.Toast;

import app.morphe.extension.shared.Logger;

/**
 * Transparent activity that asks the user to pick the USB drive once (Storage Access Framework)
 * and keeps the permission so later backups need no prompt.
 */
public class UsbBackupPickerActivity extends Activity {

    private static final int REQUEST_PICK_DRIVE = 4711;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;

        try {
            Intent intent = null;
            if (Build.VERSION.SDK_INT >= 24) {
                StorageVolume volume = UsbBackup.findRemovableVolume(this);
                if (volume != null) intent = volume.createOpenDocumentTreeIntent();
            }
            if (intent == null) intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);

            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_PICK_DRIVE);
        } catch (Exception e) {
            Logger.printException(() -> "Could not open drive picker", e);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        try {
            if (requestCode == REQUEST_PICK_DRIVE && resultCode == RESULT_OK && data != null) {
                Uri tree = data.getData();
                if (tree != null) {
                    getContentResolver().takePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    UsbBackup.saveTreeUri(this, tree);
                    Toast.makeText(this, "USB drive saved. Starting backup.", Toast.LENGTH_LONG).show();
                    UsbBackup.syncAsync(this);
                }
            }
        } catch (Exception e) {
            Logger.printException(() -> "Could not save USB drive", e);
            Toast.makeText(this, "Could not use that folder. Pick the drive itself.", Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
