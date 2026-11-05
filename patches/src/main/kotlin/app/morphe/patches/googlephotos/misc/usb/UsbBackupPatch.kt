/*
 * Adds backup of photos and videos to a USB drive in Google Photos.
 */
package app.morphe.patches.googlephotos.misc.usb

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.googlephotos.misc.extension.sharedExtensionPatch
import app.morphe.patches.googlephotos.misc.gms.HomeActivityOnCreateFingerprint
import app.morphe.patches.shared.compat.AppCompatibilities
import org.w3c.dom.Element

private const val USB_BACKUP_CLASS = "Lapp/morphe/extension/shared/patches/usb/UsbBackup;"
private const val PICKER_ACTIVITY = "app.morphe.extension.shared.patches.usb.UsbBackupPickerActivity"

private val usbBackupManifestPatch = resourcePatch(
    description = "Registers the USB drive picker activity.",
) {
    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0) as Element
            val activity = document.createElement("activity")
            activity.setAttribute("android:name", PICKER_ACTIVITY)
            activity.setAttribute("android:exported", "false")
            activity.setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
            application.appendChild(activity)
        }
    }
}

@Suppress("unused")
val usbBackupPatch = bytecodePatch(
    name = "USB drive backup",
    description = "Plug in a USB drive and Google Photos copies your photos and videos onto it. " +
        "You pick the drive once; after that, new photos are copied whenever it is plugged in.",
) {
    compatibleWith(AppCompatibilities.GOOGLE_PHOTOS)

    dependsOn(sharedExtensionPatch, usbBackupManifestPatch)

    execute {
        // The shared extension hook (which provides the context) is added in finalize,
        // so it still runs before this call at the start of onCreate.
        HomeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, " +
                "$USB_BACKUP_CLASS->onHomeActivityCreate(Landroid/app/Activity;)V",
        )
    }
}
