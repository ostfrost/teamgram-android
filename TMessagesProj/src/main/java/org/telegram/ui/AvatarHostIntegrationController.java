package org.telegram.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.media.ExifInterface;
import android.net.Uri;

import com.sousa.feature_avatar.bridge.TelegramAvatarHostCallback;
import com.sousa.feature_avatar.bridge.TelegramAvatarReactionDeliveryRequest;
import com.sousa.feature_avatar.bridge.TelegramProfilePhotoUpdateCompletion;
import com.sousa.feature_avatar.bridge.TelegramProfilePhotoMode;
import com.sousa.feature_avatar.bridge.TelegramProfilePhotoUpdateRequest;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;

final class AvatarHostIntegrationController implements TelegramAvatarHostCallback,
        NotificationCenter.NotificationCenterDelegate {
    static final int REQUEST_REAL_PHOTO = 0x4156;
    private static final int COMPOSITE_SIZE = 1024;
    private static final int MAX_REAL_PHOTO_EDGE = 2048;
    // Light-theme AvatarGradientTopExist / AvatarSurfaceBackground: the avatar webp has
    // transparent areas, and JPEG has no alpha, so without this backdrop it flattens to black.
    private static final int GRADIENT_TOP_COLOR = 0xFF008B97;
    private static final int GRADIENT_BOTTOM_COLOR = 0xFFEAEAEA;
    private static final long PROFILE_PHOTO_TIMEOUT_MS = 60_000L;

    private final WeakReference<AvatarBridgeActivity> fragmentRef;
    private final int currentAccount;
    private PendingProfilePhoto pendingComposite;
    private TelegramProfilePhotoUpdateCompletion pendingUploadCompletion;
    private String pendingUploadPath;
    private long photoIdBeforeUpload;
    private Runnable pendingUploadTimeout;

    private static final class PendingProfilePhoto {
        final TelegramProfilePhotoUpdateRequest request;
        final TelegramProfilePhotoUpdateCompletion completion;

        PendingProfilePhoto(
                TelegramProfilePhotoUpdateRequest request,
                TelegramProfilePhotoUpdateCompletion completion
        ) {
            this.request = request;
            this.completion = completion;
        }
    }

    AvatarHostIntegrationController(
            AvatarBridgeActivity fragment,
            int currentAccount
    ) {
        this.fragmentRef = new WeakReference<>(fragment);
        this.currentAccount = currentAccount;
    }

    @Override
    public void updateTelegramProfilePhoto(
            TelegramProfilePhotoUpdateRequest request,
            TelegramProfilePhotoUpdateCompletion completion
    ) {
        if (request == null || request.getAvatarWebp().length == 0) {
            completion.complete(false);
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (request.getMode() == TelegramProfilePhotoMode.WITH_REAL_PHOTO) {
                if (pendingComposite != null) {
                    pendingComposite.completion.complete(false);
                }
                pendingComposite = new PendingProfilePhoto(request, completion);
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                AvatarBridgeActivity fragment = fragmentRef.get();
                if (fragment != null && fragment.getParentActivity() != null) {
                    fragment.startActivityForResult(intent, REQUEST_REAL_PHOTO);
                } else {
                    pendingComposite = null;
                    completion.complete(false);
                }
            } else {
                org.telegram.messenger.Utilities.globalQueue.postRunnable(
                        () -> applyProfileBitmap(
                                flattenOnGradient(decodeAvatar(request)),
                                completion
                        )
                );
            }
        });
    }

    @Override
    public void deliverTelegramAvatarReaction(TelegramAvatarReactionDeliveryRequest request) {
        // Message-bound reactions are applied by ChatActivity's compact picker. This controller
        // intentionally cannot fall back to sendSticker: that would create a separate message.
        FileLog.w("Avatar reaction ignored outside the message-bound picker");
    }

    boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_REAL_PHOTO) {
            return false;
        }
        PendingProfilePhoto pending = pendingComposite;
        pendingComposite = null;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null || pending == null) {
            if (pending != null) pending.completion.complete(false);
            return true;
        }
        Uri uri = data.getData();
        org.telegram.messenger.Utilities.globalQueue.postRunnable(
                () -> composeAndApply(uri, pending.request, pending.completion)
        );
        return true;
    }

    void clear() {
        if (pendingComposite != null) {
            pendingComposite.completion.complete(false);
        }
        pendingComposite = null;
        finishProfilePhoto(false);
    }

    /**
     * Closes the avatar module once a profile photo has been applied so the user lands back on the
     * profile with the freshly uploaded photo already visible.
     */
    private void finishHostFragment() {
        AndroidUtilities.runOnUIThread(() -> {
            AvatarBridgeActivity fragment = fragmentRef.get();
            if (fragment != null && fragment.getParentActivity() != null) {
                fragment.finishFragment();
            }
        });
    }

    /**
     * The whole profile-photo hand-off runs off the UI thread and fire-and-forget, so a swallowed
     * exception looks exactly like "I picked a photo and nothing happened". Always say something.
     */
    private void reportFailure(Throwable error) {
        if (error != null) {
            FileLog.e(error);
        }
        AndroidUtilities.runOnUIThread(() -> {
            AvatarBridgeActivity fragment = fragmentRef.get();
            if (fragment == null || fragment.getParentActivity() == null) {
                return;
            }
            try {
                BulletinFactory.of(fragment)
                        .createErrorBulletin(LocaleController.getString(R.string.UnknownError))
                        .show();
            } catch (Exception ignored) {
                // A missing bulletin container must not turn a reported failure into a crash.
            }
        });
    }

    private void composeAndApply(
            Uri realPhotoUri,
            TelegramProfilePhotoUpdateRequest request,
            TelegramProfilePhotoUpdateCompletion completion
    ) {
        Bitmap realPhoto = null;
        Bitmap avatar = null;
        Bitmap composite = null;
        try {
            realPhoto = decodeSampled(realPhotoUri);
            avatar = decodeAvatar(request);
            if (realPhoto == null || avatar == null) {
                reportFailure(null);
                completion.complete(false);
                return;
            }
            composite = Bitmap.createBitmap(COMPOSITE_SIZE, COMPOSITE_SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(composite);
            canvas.drawBitmap(realPhoto, centerCrop(realPhoto), new Rect(0, 0, COMPOSITE_SIZE, COMPOSITE_SIZE), null);
            canvas.drawBitmap(avatar, null, new Rect(0, 0, COMPOSITE_SIZE, COMPOSITE_SIZE), null);
            applyProfileBitmap(composite, completion);
            composite = null;
        } catch (Exception error) {
            reportFailure(error);
            completion.complete(false);
        } finally {
            if (realPhoto != null) realPhoto.recycle();
            if (avatar != null) avatar.recycle();
            if (composite != null) composite.recycle();
        }
    }

    private Bitmap flattenOnGradient(Bitmap avatar) {
        if (avatar == null) {
            return null;
        }
        int width = avatar.getWidth();
        int height = avatar.getHeight();
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(result);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new LinearGradient(
                    0, 0, 0, height,
                    GRADIENT_TOP_COLOR, GRADIENT_BOTTOM_COLOR,
                    Shader.TileMode.CLAMP
            ));
            canvas.drawRect(0, 0, width, height, paint);
            canvas.drawBitmap(avatar, 0, 0, null);
            return result;
        } finally {
            avatar.recycle();
        }
    }

    private Bitmap decodeAvatar(TelegramProfilePhotoUpdateRequest request) {
        byte[] bytes = request.getAvatarWebp();
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    private Bitmap decodeSampled(Uri uri) throws Exception {
        AvatarBridgeActivity fragment = fragmentRef.get();
        if (fragment == null || fragment.getParentActivity() == null) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = fragment.getParentActivity().getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        // BitmapFactory.Options leaves inSampleSize at 0, so this must be seeded before dividing.
        options.inSampleSize = 1;
        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        while (largest / options.inSampleSize > MAX_REAL_PHOTO_EDGE) {
            options.inSampleSize *= 2;
        }
        int rotation;
        try (InputStream input = fragment.getParentActivity().getContentResolver().openInputStream(uri)) {
            rotation = exifRotation(input);
        }
        Bitmap decoded;
        try (InputStream input = fragment.getParentActivity().getContentResolver().openInputStream(uri)) {
            decoded = BitmapFactory.decodeStream(input, null, options);
        }
        return rotate(decoded, rotation);
    }

    /** Gallery pictures routinely carry their orientation in EXIF rather than in the pixels. */
    private int exifRotation(InputStream input) {
        if (input == null) {
            return 0;
        }
        try {
            switch (new ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (Exception error) {
            return 0;
        }
    }

    private Bitmap rotate(Bitmap source, int degrees) {
        if (source == null || degrees == 0) {
            return source;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        try {
            Bitmap rotated = Bitmap.createBitmap(
                    source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
            if (rotated != source) {
                source.recycle();
            }
            return rotated;
        } catch (OutOfMemoryError error) {
            // An upright-but-sideways photo still composes; a crash does not.
            return source;
        }
    }

    private Rect centerCrop(Bitmap bitmap) {
        int size = Math.min(bitmap.getWidth(), bitmap.getHeight());
        int left = (bitmap.getWidth() - size) / 2;
        int top = (bitmap.getHeight() - size) / 2;
        return new Rect(left, top, left + size, top + size);
    }

    private void applyProfileBitmap(
            Bitmap bitmap,
            TelegramProfilePhotoUpdateCompletion completion
    ) {
        if (bitmap == null) {
            completion.complete(false);
            return;
        }
        try {
            int localId = SharedConfig.getLastLocalId();
            TLRPC.TL_fileLocationToBeDeprecated location = new TLRPC.TL_fileLocationToBeDeprecated();
            location.volume_id = Integer.MIN_VALUE;
            location.dc_id = Integer.MIN_VALUE;
            location.local_id = localId;
            location.file_reference = new byte[0];
            File file = new File(
                    FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE),
                    location.volume_id + "_" + location.local_id + ".jpg"
            );
            try (FileOutputStream output = new FileOutputStream(file)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    throw new IllegalStateException("Could not encode avatar profile photo");
                }
            }
            String uploadPath = file.getAbsolutePath();
            AndroidUtilities.runOnUIThread(() -> {
                awaitProfilePhotoApplied(uploadPath, completion);
                MessagesController.getInstance(currentAccount).uploadAndApplyUserAvatar(location);
            });
        } catch (Exception error) {
            reportFailure(error);
            completion.complete(false);
        } finally {
            bitmap.recycle();
        }
    }

    /**
     * The bridge promises `applied == true` only once Telegram has really taken the photo, but
     * MessagesController reports nothing back and must not be modified. So watch the state it
     * publishes instead: the profile photo id changes on success, and a failed upload is announced.
     * The timeout exists because neither signal is guaranteed to arrive at all.
     */
    private void awaitProfilePhotoApplied(
            String uploadPath,
            TelegramProfilePhotoUpdateCompletion completion
    ) {
        finishProfilePhoto(false);
        pendingUploadPath = uploadPath;
        pendingUploadCompletion = completion;
        photoIdBeforeUpload = currentProfilePhotoId();
        NotificationCenter center = NotificationCenter.getInstance(currentAccount);
        center.addObserver(this, NotificationCenter.mainUserInfoChanged);
        center.addObserver(this, NotificationCenter.updateInterfaces);
        center.addObserver(this, NotificationCenter.fileUploadFailed);
        pendingUploadTimeout = () -> finishProfilePhoto(false);
        AndroidUtilities.runOnUIThread(pendingUploadTimeout, PROFILE_PHOTO_TIMEOUT_MS);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (pendingUploadCompletion == null) {
            return;
        }
        if (id == NotificationCenter.fileUploadFailed) {
            if (args.length > 0 && pendingUploadPath != null && pendingUploadPath.equals(args[0])) {
                finishProfilePhoto(false);
            }
        } else if (id == NotificationCenter.mainUserInfoChanged || id == NotificationCenter.updateInterfaces) {
            // Both fire for reasons that have nothing to do with us, so the photo id is what
            // actually says the upload landed.
            if (currentProfilePhotoId() != photoIdBeforeUpload) {
                finishProfilePhoto(true);
            }
        }
    }

    private long currentProfilePhotoId() {
        TLRPC.User user = UserConfig.getInstance(currentAccount).getCurrentUser();
        return user == null || user.photo == null ? 0L : user.photo.photo_id;
    }

    private void finishProfilePhoto(boolean applied) {
        TelegramProfilePhotoUpdateCompletion completion = pendingUploadCompletion;
        if (completion == null) {
            return;
        }
        pendingUploadCompletion = null;
        pendingUploadPath = null;
        NotificationCenter center = NotificationCenter.getInstance(currentAccount);
        center.removeObserver(this, NotificationCenter.mainUserInfoChanged);
        center.removeObserver(this, NotificationCenter.updateInterfaces);
        center.removeObserver(this, NotificationCenter.fileUploadFailed);
        if (pendingUploadTimeout != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingUploadTimeout);
            pendingUploadTimeout = null;
        }
        if (applied) {
            finishHostFragment();
        } else {
            reportFailure(null);
        }
        completion.complete(applied);
    }
}
